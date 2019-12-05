package graphics.scenery.bionictracking

import cleargl.GLTypeEnum
import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.ShaderType
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.eyetracking.PupilEyeTracker
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.numerics.Random
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.PI

/**
 * Example demonstrating bionic tracking, track objects by looking at them.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BionicTracking: SceneryBase("BionicTracking", 1280, 720) {
	val pupilTracker = PupilEyeTracker(calibrationType = PupilEyeTracker.CalibrationType.WorldSpace, port = System.getProperty("PupilPort", "50020").toInt())
	val hmd = OpenVRHMD(seated = false, useCompositor = true)
	val referenceTarget = Icosphere(0.004f, 2)
	val calibrationTarget = Icosphere(0.02f, 2)
	val laser = Cylinder(0.005f, 0.2f, 10)

	val hedgehogs = Mesh()
	enum class HedgehogVisibility { Hidden, PerTimePoint, Visible }
	var hedgehogVisibility = HedgehogVisibility.Hidden

	lateinit var volume: Volume

	val confidenceThreshold = 0.60f

	@Volatile var tracking = false
	var playing = false
	var volumesPerSecond = 4
	var skipToNext = false
	var skipToPrevious = false
	var currentVolume = 0

	var volumeScaleFactor = 1.0f

	lateinit var volumes: List<String>

	override fun init() {
		val files = ArrayList<String>()
		val fileFromProperty = System.getProperty("dataset")
		if(fileFromProperty != null) {
			files.add(fileFromProperty)
		} else {
			val c = Context()
			val ui = c.getService(UIService::class.java)
			val file = ui.chooseFile(null, FileWidget.DIRECTORY_STYLE)
			files.add(file.absolutePath)
		}

		if(files.size == 0) {
			throw IllegalStateException("You have to select a file, sorry.")
		}

		logger.info("Loading dataset from ${files.first()}")

		hub.add(SceneryElement.HMDInput, hmd)
		renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
		renderer?.toggleVR()
		hub.add(SceneryElement.Renderer, renderer!!)

		repl?.addAccessibleObject(this)

		val cam: Camera = DetachedHeadCamera(hmd)
		with(cam) {
			position = GLVector(0.0f, 0.5f, 5.0f)
			perspectiveCamera(50.0f, 1.0f*windowWidth, 1.0f*windowHeight)
			active = true

			scene.addChild(this)
		}

		referenceTarget.visible = false
		referenceTarget.material.roughness = 1.0f
		referenceTarget.material.metallic = 0.0f
		referenceTarget.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
		cam.addChild(referenceTarget)

//		calibrationTarget.readFromOBJ(BionicTracking::class.java.getResource("StanfordBunny.obj").file)
//		calibrationTarget.scale = GLVector(0.3f, 0.3f, 0.3f)
		calibrationTarget.visible = false
		calibrationTarget.material.roughness = 1.0f
		calibrationTarget.material.metallic = 0.0f
		calibrationTarget.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
		calibrationTarget.runRecursive { it.material.diffuse = GLVector(1.0f, 1.0f, 1.0f) }
		cam.addChild(calibrationTarget)

		laser.visible = false
		laser.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
		scene.addChild(laser)

		val shell = Box(GLVector(20.0f, 20.0f, 20.0f), insideNormals = true)
		shell.material.cullingMode = Material.CullingMode.None
		shell.material.diffuse = GLVector(0.01f, 0.01f, 0.01f)
		shell.material.specular = GLVector.getNullVector(3)
		shell.material.ambient = GLVector.getNullVector(3)
		shell.position = GLVector(0.0f, 0.0f, 0.0f)
		scene.addChild(shell)


		val folder = File(files.first())
		val stackfiles = folder.listFiles()
		volumes = stackfiles.filter { it.isFile && it.name.toLowerCase().endsWith("raw") || it.name.substringAfterLast(".").toLowerCase().startsWith("tif") }.map { it.absolutePath }.sorted()
		logger.info("Found volumes: ${volumes.joinToString(", ")}")

		volume = Volume()
		volume.name = "volume"
		volume.position = GLVector(0.0f, 1.0f, 0.0f)
		volume.colormap = "jet"
		volume.voxelSizeX = 10.0f
		volume.voxelSizeY = 10.0f
		volume.voxelSizeZ = 30.0f
		volume.transferFunction = TransferFunction.ramp(0.05f, 0.8f)
		volume.metadata["animating"] = true
		volume.trangemax = 1500.0f
		volume.visible = false
		scene.addChild(volume)

		val bb = BoundingGrid()
		bb.node = volume
		bb.visible = false

		scene.addChild(hedgehogs)

		val eyeFrames = Mesh("eyeFrames")
		val left = Box(GLVector(1.0f, 1.0f, 0.001f))
		val right = Box(GLVector(1.0f, 1.0f, 0.001f))
		left.position = GLVector(-1.0f, 1.5f, 0.0f)
		left.rotation = left.rotation.rotateByAngleZ(PI.toFloat())
		right.position = GLVector(1.0f, 1.5f, 0.0f)
		eyeFrames.addChild(left)
		eyeFrames.addChild(right)

		scene.addChild(eyeFrames)

		// limit frame rate for the frame publisher
		val pupilFrameLimit = 20
		var lastFrame = System.nanoTime()

		pupilTracker.subscribeFrames { eye, texture ->
			if(System.nanoTime() - lastFrame < pupilFrameLimit*10e5) {
				return@subscribeFrames
			}

			val node = if(eye == 1) {
				left
			} else {
				right
			}

			val stream = ByteArrayInputStream(texture)
			val image = ImageIO.read(stream)
			val data = (image.raster.dataBuffer as DataBufferByte).data

			node.material.textures["diffuse"] = "fromBuffer:eye_$eye"
			node.material.transferTextures["eye_$eye"] = GenericTexture(
					"eye_${eye}_${System.currentTimeMillis()}",
					GLVector(image.width.toFloat(), image.height.toFloat(), 1.0f),
					3,
					GLTypeEnum.UnsignedByte,
					BufferUtils.allocateByteAndPut(data)
			)
			node.material.needsTextureReload = true

			lastFrame = System.nanoTime()
		}

		val debugBoard = TextBoard()
		debugBoard.name = "debugBoard"
		debugBoard.scale = GLVector(0.05f, 0.05f, 0.05f)
		debugBoard.position = GLVector(0.0f, -0.3f, -0.9f)
		debugBoard.text = ""
		debugBoard.visible = false
		cam.addChild(debugBoard)

		val lights = Light.createLightTetrahedron<PointLight>(GLVector(0.0f, 0.0f, 0.0f), spread = 5.0f, radius = 15.0f, intensity = 5.0f)
		lights.forEach { scene.addChild(it) }

		thread {
			while(!running) {
				Thread.sleep(200)
			}

			hmd.events.onDeviceConnect.add { hmd, device, timestamp ->
				if(device.type == TrackedDeviceType.Controller) {
					logger.info("Got device ${device.name} at $timestamp")
					device.model?.let { hmd.attachToNode(device, it, cam) }
				}
			}
		}

		thread {
			while(!scene.initialized) { Thread.sleep(200) }

			while(running) {
				if(playing || skipToNext || skipToPrevious) {
					val oldTimepoint = currentVolume
					val newVolume = if(skipToNext || playing) {
						skipToNext = false
						nextVolume()
					} else {
						skipToPrevious = false
						previousVolume()
					}
					val newTimepoint = currentVolume

					logger.debug("Loading volume $newVolume")
					if(newVolume.toLowerCase().endsWith("raw")) {
						volume.readFromRaw(Paths.get(newVolume), autorange = false, cache = true, replace = false)
					} else {
						volume.readFrom(Paths.get(newVolume), replace = false)
					}

					if(hedgehogs.visible) {
						if(hedgehogVisibility == HedgehogVisibility.PerTimePoint) {
							hedgehogs.children.forEach { hedgehog ->
								hedgehog.instances.forEach {
									it.visible = (it.metadata["spine"] as SpineMetadata).timepoint == currentVolume
								}
							}
						} else {
							hedgehogs.children.forEach { hedgehog ->
								hedgehog.instances.forEach { it.visible = true }
							}
						}
					}

					volume.trangemax = 1500.0f

					if(tracking && oldTimepoint == (volumes.size-1) && newTimepoint == 0) {
						tracking = false

						referenceTarget.material.diffuse = GLVector(0.5f, 0.5f, 0.5f)
						cam.showMessage("Tracking deactivated.")
						dumpHedgehog()
					}
				}

				Thread.sleep((1000.0f/volumesPerSecond).toLong())
			}
		}
	}

	fun addHedgehog(parent: Node) {
		val hedgehog = Cylinder(0.005f, 1.0f, 16)
		hedgehog.visible = false
		hedgehog.material = ShaderMaterial.fromClass(BionicTracking::class.java,
				listOf(ShaderType.VertexShader, ShaderType.FragmentShader))
		hedgehog.instancedProperties["ModelMatrix"] = { hedgehog.world }
		hedgehog.instancedProperties["Metadata"] = { GLVector(0.0f, 0.0f, 0.0f, 0.0f) }
		parent.addChild(hedgehog)
	}

	fun nextVolume(): String {
		currentVolume = (currentVolume + 1) % volumes.size
		currentVolume = minOf(currentVolume, volumes.size)
		return volumes[currentVolume]
	}

	fun previousVolume(): String {
		currentVolume = (currentVolume - 1) % volumes.size
		currentVolume = maxOf(0, currentVolume)
		return volumes[currentVolume]
	}

	override fun inputSetup() {
		val cam = scene.findObserver() ?: throw IllegalStateException("Could not find camera")

		inputHandler?.let { handler ->
			hashMapOf(
					"move_forward_fast" to "K",
					"move_back_fast" to "J",
					"move_left_fast" to "H",
					"move_right_fast" to "L").forEach { (name, key) ->
				handler.getBehaviour(name)?.let { b ->
					hmd.addBehaviour(name, b)
					hmd.addKeyBinding(name, key)
				}
			}
		}

		val toggleHedgehog = ClickBehaviour { _, _ ->
			val current = HedgehogVisibility.values().indexOf(hedgehogVisibility)
			hedgehogVisibility = HedgehogVisibility.values().get((current + 1) % 3)

			when(hedgehogVisibility) {
				HedgehogVisibility.Hidden -> {
					hedgehogs.visible = false
					hedgehogs.runRecursive { it.visible = false }
					cam.showMessage("Hedgehogs hidden")
				}

				HedgehogVisibility.PerTimePoint -> {
					hedgehogs.visible = true
					cam.showMessage("Hedgehogs shown per timepoint")
				}

				HedgehogVisibility.Visible -> {
					hedgehogs.visible = true
					cam.showMessage("Hedgehogs visible")
				}
			}
		}

		hmd.addBehaviour("toggle_hedgehog", toggleHedgehog)
		hmd.addKeyBinding("toggle_hedgehog", "X")

		val nextTimepoint = ClickBehaviour { _, _ ->
			skipToNext = true
		}

		val prevTimepoint = ClickBehaviour { _, _ ->
			skipToPrevious = true
		}

		hmd.addBehaviour("skip_to_next", nextTimepoint)
		hmd.addKeyBinding("skip_to_next", "D")
		hmd.addBehaviour("skip_to_prev", prevTimepoint)
		hmd.addKeyBinding("skip_to_prev", "A")

		val fasterOrScale = ClickBehaviour { _, _ ->
			if(playing) {
				volumesPerSecond = maxOf(minOf(volumesPerSecond+1, 20), 1)
				cam.showMessage("Speed: $volumesPerSecond vol/s")
			} else {
				volumeScaleFactor = minOf(volumeScaleFactor * 1.2f, 3.0f)
				volume.scale = GLVector.getOneVector(3) * volumeScaleFactor
			}
		}

		hmd.addBehaviour("faster_or_scale", fasterOrScale)
		hmd.addKeyBinding("faster_or_scale", "W")

		val slowerOrScale = ClickBehaviour { _, _ ->
			if(playing) {
				volumesPerSecond = maxOf(minOf(volumesPerSecond-1, 20), 1)
				cam.showMessage("Speed: $volumesPerSecond vol/s")
			} else {
				volumeScaleFactor = maxOf(volumeScaleFactor / 1.2f, 0.1f)
				volume.scale = GLVector.getOneVector(3) * volumeScaleFactor
			}
		}

		hmd.addBehaviour("slower_or_scale", slowerOrScale)
		hmd.addKeyBinding("slower_or_scale", "S")

		val playPause = ClickBehaviour { _, _ ->
			playing = !playing
			if(playing) {
				cam.showMessage("Playing")
			} else {
				cam.showMessage("Paused")
			}
		}

		hmd.addBehaviour("play_pause", playPause)
		hmd.addKeyBinding("play_pause", "M")

		val move = ControllerDrag(TrackerRole.LeftHand, hmd) { volume }

		hmd.addBehaviour("trigger_move", move)
		hmd.addKeyBinding("trigger_move", "T")

		hmd.allowRepeats += OpenVRHMD.OpenVRButton.Trigger to TrackerRole.LeftHand

		setupCalibration()
	}

	class ControllerDrag(val handedness: TrackerRole, val hmd: OpenVRHMD, val draggedObjectFinder: () -> Node?): ClickBehaviour {
		/**
		 * A click occuered at the specified location, where click can mean a
		 * regular mouse click or a typed key.
		 *
		 * @param x
		 * mouse x.
		 * @param y
		 * mouse y.
		 */
		val logger by LazyLogger()
		var lastPosition: GLVector? = null
		// half a second of timeout
		val timeout = 500000000

		var lastTime = System.nanoTime()

		override fun click(x : Int, y : Int) {
			val currentTime = System.nanoTime()
			if(currentTime - lastTime > timeout) {
				lastPosition = null
				lastTime = currentTime
			}

			val pose = hmd.getPose(TrackedDeviceType.Controller).find { it.role == handedness } ?: return
			val last = lastPosition
			val current = pose.position.clone()

			if(last != null) {
				val node = draggedObjectFinder.invoke() ?: return
				node.position = node.position + (current - last)
				logger.debug("Node ${node.name} moved with $current - $last!")
			}

			lastPosition = current
		}
	}


	private fun setupCalibration(keybindingCalibration: String = "N", keybindingTracking: String = "U") {
		val startCalibration = ClickBehaviour { _, _ ->
			thread {
				val cam = scene.findObserver() as? DetachedHeadCamera ?: return@thread
				pupilTracker.gazeConfidenceThreshold = confidenceThreshold
				if (!pupilTracker.isCalibrated) {
					pupilTracker.onCalibrationInProgress = {
						cam.showMessage("Crunching equations ...", messageColor = GLVector(1.0f, 0.8f, 0.0f), duration = 15000)
					}

					pupilTracker.onCalibrationFailed = {
						cam.showMessage("Calibration failed.", messageColor = GLVector(1.0f, 0.0f, 0.0f))
					}

					pupilTracker.onCalibrationSuccess = {
						cam.showMessage("Calibration succeeded!", messageColor = GLVector(0.0f, 1.0f, 0.0f))
//						cam.children.find { it.name == "debugBoard" }?.visible = true

						for (i in 0 until 20) {
							referenceTarget.material.diffuse = GLVector(0.0f, 1.0f, 0.0f)
							Thread.sleep(100)
							referenceTarget.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
							Thread.sleep(30)
						}

						hmd.removeBehaviour("start_calibration")
						hmd.removeKeyBinding("start_calibration")

						val toggleTracking = ClickBehaviour { _, _ ->
							if(tracking) {
								referenceTarget.material.diffuse = GLVector(0.5f, 0.5f, 0.5f)
								cam.showMessage("Tracking deactivated.")
								dumpHedgehog()
							} else {
								addHedgehog(hedgehogs)
								referenceTarget.material.diffuse = GLVector(1.0f, 0.0f, 0.0f)
								cam.showMessage("Tracking active.")
							}
							tracking = !tracking
						}
						hmd.addBehaviour("toggle_tracking", toggleTracking)
						hmd.addKeyBinding("toggle_tracking", keybindingTracking)

						volume.visible = true
						volume.runRecursive { it.visible = true }
						playing = true
					}

					pupilTracker.unsubscribeFrames()
					scene.removeChild("eyeFrames")

					logger.info("Starting eye tracker calibration")
					cam.showMessage("Follow the white rabbit.", duration = 1500)
					pupilTracker.calibrate(cam, hmd,
							generateReferenceData = true,
							calibrationTarget = calibrationTarget)

					pupilTracker.onGazeReceived = when (pupilTracker.calibrationType) {
						PupilEyeTracker.CalibrationType.ScreenSpace -> { gaze ->
							if (gaze.confidence > confidenceThreshold) {
								referenceTarget.visible = true
//								laser.visible = true
								val referencePosition = cam.viewportToWorld(
										GLVector(
												gaze.normalizedPosition().x() * 2.0f - 1.0f,
												gaze.normalizedPosition().y() * 2.0f - 1.0f),
										offset = 0.5f) + cam.forward * 0.15f


								val headCenter = cam.viewportToWorld(GLVector(0.0f, 0.0f))
								val direction = (referencePosition - headCenter).normalize()

								referenceTarget.position = referencePosition.clone() + direction * 0.3f

//								laser.orientBetweenPoints(headCenter, referencePosition, rescale = false, reposition = true)

								if(tracking) {
									addSpine(headCenter, direction, volume, gaze.confidence, currentVolume)
								}
							}
						}

						PupilEyeTracker.CalibrationType.WorldSpace -> { gaze ->
							if (gaze.confidence > confidenceThreshold) {
								val p = gaze.gazePoint()
								referenceTarget.visible = true
								// Pupil has mm units, so we divide by 1000 here to get to scenery units
								referenceTarget.position = p
								(cam.children.find { it.name == "debugBoard" } as? TextBoard)?.text = "${String.format("%.2f", p.x())}, ${String.format("%.2f", p.y())}, ${String.format("%.2f", p.z())}"

								val headCenter = cam.viewportToWorld(GLVector(0.0f, 0.0f))
								val pointWorld = cam.world.mult(p.xyzw()).xyz()
								val direction = (pointWorld - headCenter).normalize()

								if(tracking) {
									logger.debug("Starting spine from $headCenter to $pointWorld")
									addSpine(headCenter, direction, volume, gaze.confidence, currentVolume)
								}
							}
						}
					}
				}
			}

			logger.info("Calibration routine done.")
		}

		// bind calibration start to menu key on controller
		hmd.addBehaviour("start_calibration", startCalibration)
		hmd.addKeyBinding("start_calibration", keybindingCalibration)
	}

	fun addSpine(center: GLVector, direction: GLVector, volume: Volume, confidence: Float, timepoint: Int) {
		val cam = scene.findObserver() as? DetachedHeadCamera ?: return
		val sphere = volume.boundingBox?.getBoundingSphere() ?: return

		val sphereDirection = (sphere.origin - center)
		val sphereDist = sphereDirection.magnitude() - sphere.radius

		val p1 = center
		val p2 = center + direction * (sphereDist + 2.0f * sphere.radius)

		val spine = Cylinder.betweenPoints(p1, p2, 1.0f, segments = 1)
		spine.visible = false

		val intersection = volume.intersectAABB(p1, (p2 - p1).normalize())

		if(intersection is MaybeIntersects.Intersection) {
			// get local entry and exit coordinates, and convert to UV coords
			val localEntry = (intersection.relativeEntry + GLVector.getOneVector(3)) * (1.0f / 2.0f)
			val localExit = (intersection.relativeExit + GLVector.getOneVector(3)) * (1.0f / 2.0f)

			val (samples, localDirection) = volume.sampleRay(localEntry, localExit) ?: null to null

			if (samples != null && localDirection != null) {
				val metadata = SpineMetadata(
						timepoint,
						center,
						direction,
						intersection.distance,
						localEntry,
						localExit,
						localDirection,
						cam.headPosition,
						cam.headOrientation,
						cam.position,
						confidence,
						samples.map { it ?: 0.0f }
				)
				val count = samples.filterNotNull().count { it > 0.2f }

				spine.metadata["spine"] = metadata
				spine.instancedProperties["ModelMatrix"] = { spine.world }
				spine.instancedProperties["Metadata"] = { GLVector(confidence, timepoint.toFloat()/volumes.size, count.toFloat(), 0.0f) }

				hedgehogs.children.last().instances.add(spine)
			}
		}
	}

	fun dumpHedgehog() {
		val f = File(System.getProperty("user.home") + "/Desktop/Hedgehog_${SystemHelpers.formatDateTime()}.csv")
		val writer = f.bufferedWriter()
		writer.write("Timepoint,Origin,Direction,LocalEntry,LocalExit,LocalDirection,HeadPosition,HeadOrientation,Position,Confidence,Samples\n")

		val spines = hedgehogs.children.last().instances.mapNotNull { spine ->
			spine.metadata["spine"] as? SpineMetadata
		}

		spines.forEach { metadata ->
			writer.write("${metadata.timepoint};${metadata.origin};${metadata.direction};${metadata.localEntry};${metadata.localExit};${metadata.localDirection};${metadata.headPosition};${metadata.headOrientation};${metadata.position};${metadata.position};${metadata.confidence};${metadata.samples.joinToString(";")}")
		}
		writer.close()

		logger.info("Written hedgehog to ${f.absolutePath}")

		val h = HedgehogAnalysis(spines)
		val track = h.run()

		if(track == null) {
			logger.warn("No track returned")
			scene.findObserver()?.showMessage("No track returned", messageColor = GLVector(1.0f, 0.0f, 0.0f))
			return
		}

		logger.info("---\nTrack: ${track.points.joinToString("\n")}\n---")

		val master = Cylinder(0.005f, 1.0f, 10)
		master.material = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
		master.material.diffuse = Random.randomVectorFromRange(3, 0.2f, 0.8f)
		master.material.roughness = 1.0f
		master.material.metallic = 0.0f
		master.material.cullingMode = Material.CullingMode.None
		master.instancedProperties["ModelMatrix"] = { master.world }

		track.points.windowed(2, 1).forEach { pair ->
			val element = Mesh()
			element.orientBetweenPoints(pair[0].first, pair[1].first, rescale = true, reposition = true)
			element.parent = volume
			element.instancedProperties["ModelMatrix"] = { element.world }
			master.instances.add(element)
		}

		volume.addChild(master)
	}
}

fun main(args: Array<String>) {
	BionicTracking().main()
}
