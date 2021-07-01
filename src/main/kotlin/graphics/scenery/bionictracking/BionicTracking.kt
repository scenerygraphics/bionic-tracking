package graphics.scenery.bionictracking

//import cleargl.GLTypeEnum
//import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.textures.Texture
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.ShaderType
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.eyetracking.PupilEyeTrackerNew
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.controls.TrackerRole
import graphics.scenery.controls.behaviours.ControllerDrag
import graphics.scenery.numerics.Random
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.xyz
import graphics.scenery.utils.extensions.xyzw
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedByteType
import org.joml.*
import org.joml.Math.sqrt
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import java.awt.image.DataBufferByte
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.collections.ArrayList
import kotlin.concurrent.thread
import kotlin.math.PI

/**
 * Example demonstrating bionic tracking, track objects by looking at them.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class BionicTracking: SceneryBase("BionicTracking", 640, 480) {
	val pupilTracker = PupilEyeTrackerNew(calibrationType = PupilEyeTrackerNew.CalibrationType.WorldSpace, port = System.getProperty("PupilPort", "50020").toInt())
	val hmd = OpenVRHMD(seated = false, useCompositor = true)
	val referenceTarget = Icosphere(0.004f, 2)
	val calibrationTarget = Icosphere(0.02f, 2)
	val laser = Cylinder(0.005f, 0.2f, 10)

	lateinit var sessionId: String
	lateinit var sessionDirectory: Path

	val hedgehogs = Mesh()
	enum class HedgehogVisibility { Hidden, PerTimePoint, Visible }
	var hedgehogVisibility = HedgehogVisibility.Hidden

	lateinit var volume: Volume

	val confidenceThreshold = 0.60f

	enum class PlaybackDirection {
		Forward,
		Backward
	}

	@Volatile var tracking = false
	var playing = false
    var direction = PlaybackDirection.Forward
	var volumesPerSecond = 4
	var skipToNext = false
	var skipToPrevious = false
//	var currentVolume = 0

	var volumeScaleFactor = 1.0f



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

		val directory = Paths.get(files.first())
		val datasetName = directory.fileName.toString()
		sessionId = "BionicTracking-$datasetName-${SystemHelpers.formatDateTime()}"
		sessionDirectory = Files.createDirectory(Paths.get(System.getProperty("user.home"), "Desktop", sessionId))

		hub.add(SceneryElement.HMDInput, hmd)
		renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
		renderer?.toggleVR()
		hub.add(SceneryElement.Renderer, renderer!!)

		repl?.addAccessibleObject(this)

		val cam: Camera = DetachedHeadCamera(hmd)
		with(cam) {
			position = Vector3f(0.0f, 0.5f, 5.0f)
			perspectiveCamera(50.0f, windowWidth, windowHeight)
//			active = true
			scene.addChild(this)
		}
		cam.disableCulling = true

		referenceTarget.visible = false
		referenceTarget.material.roughness = 1.0f
		referenceTarget.material.metallic = 0.0f
		referenceTarget.material.diffuse = Vector3f(0.8f, 0.8f, 0.8f)
		cam.addChild(referenceTarget)

//		calibrationTarget.readFromOBJ(BionicTracking::class.java.getResource("StanfordBunny.obj").file)
//		calibrationTarget.scale = Vector3f(0.3f, 0.3f, 0.3f)
		calibrationTarget.visible = false
		calibrationTarget.material.roughness = 1.0f
		calibrationTarget.material.metallic = 0.0f
		calibrationTarget.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)
		calibrationTarget.runRecursive { it.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f) }
		cam.addChild(calibrationTarget)

		laser.visible = false
		laser.material.diffuse = Vector3f(1.0f, 1.0f, 1.0f)
		scene.addChild(laser)

		val shell = Box(Vector3f(20.0f, 20.0f, 20.0f), insideNormals = true)
		shell.material.cullingMode = Material.CullingMode.Front
		shell.material.diffuse = Vector3f(0.4f, 0.4f, 0.4f)
//		shell.material.specular = Vector3f(0.0f)
//		shell.material.ambient = Vector3f(0.0f)
		shell.position = Vector3f(0.0f, 0.0f, 0.0f)
		scene.addChild(shell)


		val folder = File(files.first())

		volume = Volume.fromPath(folder.toPath(),hub)
		System.out.println("the number of volume timestamp:"+volume.timepointCount);
		volume.name = "volume"
		volume.position = Vector3f(0.0f, 1.0f, 0.0f)
		volume.colormap = Colormap.get("jet")
		volume.scale = Vector3f(10.0f, 10.0f,30.0f)
		volume.transferFunction = TransferFunction.ramp(0.05f, 0.8f)
//		volume.transferFunction = TransferFunction.ramp(0.5f, 0.8f)
		volume.metadata["animating"] = true
		volume.converterSetups.firstOrNull()?.setDisplayRange(0.0, 1500.0)
		volume.visible = false
		scene.addChild(volume)

		val bb = BoundingGrid()
		bb.node = volume
		bb.visible = false

		scene.addChild(hedgehogs)

		val eyeFrames = Mesh("eyeFrames")
		val left = Box(Vector3f(1.0f, 1.0f, 0.001f))
		val right = Box(Vector3f(1.0f, 1.0f, 0.001f))
		left.position = Vector3f(-1.0f, 1.5f, 0.0f)
		left.rotation = left.rotation.rotationZ(PI.toFloat())
		//left.rotation = left.rotation.rotateByAngleZ(PI.toFloat())
		right.position = Vector3f(1.0f, 1.5f, 0.0f)
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

			node.material.textures["diffuse"] = Texture(
					Vector3i(image.width, image.height, 1),
					3,
					UnsignedByteType(),
					BufferUtils.allocateByteAndPut(data)
			)

			lastFrame = System.nanoTime()
		}

		val debugBoard = TextBoard()
		debugBoard.name = "debugBoard"
		debugBoard.scale = Vector3f(0.05f, 0.05f, 0.05f)
		debugBoard.position = Vector3f(0.0f, -0.3f, -0.9f)
		debugBoard.text = ""
		debugBoard.visible = false
		cam.addChild(debugBoard)

		val lights = Light.createLightTetrahedron<PointLight>(Vector3f(0.0f, 0.0f, 0.0f), spread = 5.0f, radius = 15.0f, intensity = 5.0f)
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
					val oldTimepoint = volume.viewerState.currentTimepoint
					val newVolume = if(skipToNext || playing) {
						skipToNext = false
                        if(direction == PlaybackDirection.Forward) {
							volume.nextTimepoint()
//							nextVolume()
						} else {
//							previousVolume()
							volume.previousTimepoint()
						}
					} else {
						skipToPrevious = false
						if(direction == PlaybackDirection.Forward) {
							volume.previousTimepoint()
//							previousVolume()
						} else {
//							nextVolume()
							volume.nextTimepoint()
						}
					}
					val newTimepoint = volume.viewerState.currentTimepoint


					if(hedgehogs.visible) {
						if(hedgehogVisibility == HedgehogVisibility.PerTimePoint) {
							hedgehogs.children.forEach { hedgehog ->
								hedgehog.instances.forEach {
									it.visible = (it.metadata["spine"] as SpineMetadata).timepoint == volume.viewerState.currentTimepoint
								}
							}
						} else {
							hedgehogs.children.forEach { hedgehog ->
								hedgehog.instances.forEach { it.visible = true }
							}
						}
					}



					if(tracking && oldTimepoint == (volume.timepointCount-1) && newTimepoint == 0) {
						tracking = false

						referenceTarget.material.diffuse = Vector3f(0.5f, 0.5f, 0.5f)
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
		hedgehog.instancedProperties["Metadata"] = { Vector4f(0.0f, 0.0f, 0.0f, 0.0f) }
		parent.addChild(hedgehog)
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

		val nextTimepoint = ClickBehaviour { _, _ ->
			skipToNext = true
		}

		val prevTimepoint = ClickBehaviour { _, _ ->
			skipToPrevious = true
		}

		val fasterOrScale = ClickBehaviour { _, _ ->
			if(playing) {
				volumesPerSecond = maxOf(minOf(volumesPerSecond+1, 20), 1)
				cam.showMessage("Speed: $volumesPerSecond vol/s")
			} else {
				volumeScaleFactor = minOf(volumeScaleFactor * 1.2f, 3.0f)
				volume.scale =Vector3f(1.0f) .mul(volumeScaleFactor)
			}
		}

		val slowerOrScale = ClickBehaviour { _, _ ->
			if(playing) {
				volumesPerSecond = maxOf(minOf(volumesPerSecond-1, 20), 1)
				cam.showMessage("Speed: $volumesPerSecond vol/s")
			} else {
				volumeScaleFactor = maxOf(volumeScaleFactor / 1.2f, 0.1f)
				volume.scale = Vector3f(1.0f) .mul(volumeScaleFactor)
			}
		}

		val playPause = ClickBehaviour { _, _ ->
			playing = !playing
			if(playing) {
				cam.showMessage("Playing")
			} else {
				cam.showMessage("Paused")
			}
		}

		val move = ControllerDrag(TrackerRole.LeftHand, hmd) { volume }

		val deleteLastHedgehog = ConfirmableClickBehaviour(
				armedAction = { timeout ->
					cam.showMessage("Deleting last track, press again to confirm.",
							messageColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
							backgroundColor = Vector4f(1.0f, 0.2f, 0.2f, 1.0f),
							duration = timeout.toInt())

				},
				confirmAction = {
					hedgehogs.children.removeAt(hedgehogs.children.size-1)
					volume.children.last { it.name.startsWith("Track-") }?.let { lastTrack ->
						volume.removeChild(lastTrack)
					}

					val hedgehogId = hedgehogIds.get()
					val hedgehogFile = sessionDirectory.resolve("Hedgehog_${hedgehogId}_${SystemHelpers.formatDateTime()}.csv").toFile()
					val hedgehogFileWriter = BufferedWriter(FileWriter(hedgehogFile, true))
					hedgehogFileWriter.newLine()
					hedgehogFileWriter.newLine()
					hedgehogFileWriter.write("# WARNING: TRACK $hedgehogId IS INVALID\n")
					hedgehogFileWriter.close()

					cam.showMessage("Last track deleted.",
							messageColor = Vector4f(1.0f, 0.2f, 0.2f, 1.0f),
							backgroundColor = Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
							duration = 1000)
				})

		hmd.addBehaviour("playback_direction", ClickBehaviour { _, _ ->
			direction = if(direction == PlaybackDirection.Forward) {
				PlaybackDirection.Backward
			} else {
				PlaybackDirection.Forward
			}
			cam.showMessage("Playing: ${direction}")
		})

		val cellDivision = ClickBehaviour { _, _ ->
			cam.showMessage("Adding cell division", duration = 1000)
			dumpHedgehog()
			addHedgehog(hedgehogs)
		}

		hmd.addBehaviour("skip_to_next", nextTimepoint)
		hmd.addBehaviour("skip_to_prev", prevTimepoint)
		hmd.addBehaviour("faster_or_scale", fasterOrScale)
		hmd.addBehaviour("slower_or_scale", slowerOrScale)
		hmd.addBehaviour("play_pause", playPause)
		hmd.addBehaviour("toggle_hedgehog", toggleHedgehog)
		hmd.addBehaviour("trigger_move", move)
		hmd.addBehaviour("delete_hedgehog", deleteLastHedgehog)
		hmd.addBehaviour("cell_division", cellDivision)

		hmd.addKeyBinding("toggle_hedgehog", "X")
		hmd.addKeyBinding("delete_hedgehog", "Y")
		hmd.addKeyBinding("skip_to_next", "D")
		hmd.addKeyBinding("skip_to_prev", "A")
		hmd.addKeyBinding("faster_or_scale", "W")
		hmd.addKeyBinding("slower_or_scale", "S")
		hmd.addKeyBinding("play_pause", "M")
		hmd.addKeyBinding("playback_direction", "N")
		hmd.addKeyBinding("cell_division", "T")

		hmd.allowRepeats += OpenVRHMD.OpenVRButton.Trigger to TrackerRole.LeftHand

		setupCalibration()
	}

	private fun setupCalibration(keybindingCalibration: String = "N", keybindingTracking: String = "U") {
		val startCalibration = ClickBehaviour { _, _ ->
			thread {
				val cam = scene.findObserver() as? DetachedHeadCamera ?: return@thread
				pupilTracker.gazeConfidenceThreshold = confidenceThreshold
				if (!pupilTracker.isCalibrated) {
					pupilTracker.onCalibrationInProgress = {
						cam.showMessage("Crunching equations ...", messageColor = Vector4f(1.0f, 0.8f, 0.0f,1.0f), duration = 15000)
					}

					pupilTracker.onCalibrationFailed = {
						cam.showMessage("Calibration failed.", messageColor = Vector4f(1.0f, 0.0f, 0.0f,1.0f))
					}

					pupilTracker.onCalibrationSuccess = {
						cam.showMessage("Calibration succeeded!", messageColor = Vector4f(0.0f, 1.0f, 0.0f,1.0f))
//						cam.children.find { it.name == "debugBoard" }?.visible = true

						for (i in 0 until 20) {
							referenceTarget.material.diffuse = Vector3f(0.0f, 1.0f, 0.0f)
							Thread.sleep(100)
							referenceTarget.material.diffuse = Vector3f(0.8f, 0.8f, 0.8f)
							Thread.sleep(30)
						}

						hmd.removeBehaviour("start_calibration")
						hmd.removeKeyBinding("start_calibration")

						val toggleTracking = ClickBehaviour { _, _ ->
							if(tracking) {
								referenceTarget.material.diffuse = Vector3f(0.5f, 0.5f, 0.5f)
								cam.showMessage("Tracking deactivated.")
								dumpHedgehog()
							} else {
								addHedgehog(hedgehogs)
								referenceTarget.material.diffuse = Vector3f(1.0f, 0.0f, 0.0f)
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

						PupilEyeTrackerNew.CalibrationType.WorldSpace -> { gaze ->
							if (gaze.confidence > confidenceThreshold) {
								val p = gaze.gazePoint()
								referenceTarget.visible = true
								// Pupil has mm units, so we divide by 1000 here to get to scenery units
								referenceTarget.position = p
								(cam.children.find { it.name == "debugBoard" } as? TextBoard)?.text = "${String.format("%.2f", p.x())}, ${String.format("%.2f", p.y())}, ${String.format("%.2f", p.z())}"

								val headCenter = cam.viewportToWorld(Vector2f(0.0f, 0.0f))
								val pointWorld = Matrix4f(cam.world).transform(p.xyzw()).xyz()
								val direction = (pointWorld - headCenter).normalize()

								if(tracking) {
									logger.info("Starting spine from $headCenter to $pointWorld")
									addSpine(headCenter, direction, volume, gaze.confidence, volume.viewerState.currentTimepoint)
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



	fun addSpine(center: Vector3f, direction: Vector3f, volume: Volume, confidence: Float, timepoint: Int) {
		val cam = scene.findObserver() as? DetachedHeadCamera ?: return
		val sphere = volume.boundingBox?.getBoundingSphere() ?: return

		val sphereDirection = sphere.origin.minus(center)
		val sphereDist = sqrt(sphereDirection.x*sphereDirection.x+sphereDirection.y*sphereDirection.y+sphereDirection.z*sphereDirection.z) - sphere.radius

		val p1 = center
		val temp = direction.mul(sphereDist + 2.0f * sphere.radius)
		val p2 = Vector3f(center).add(temp)
//		val p2 = center + direction * (sphereDist + 2.0f * sphere.radius)
		System.out.println(p1);
		System.out.println(p2);
		val spine = Cylinder.betweenPoints(p1, p2, 1.0f, segments = 1)
		spine.visible = false

		val intersection = volume.intersectAABB(p1, (p2 - p1).normalize())
		System.out.println(intersection);
		if(intersection is MaybeIntersects.Intersection) {
			// get local entry and exit coordinates, and convert to UV coords
			val localEntry = (intersection.relativeEntry .add(Vector3f(1.0f)) ) .mul (1.0f / 2.0f)
			val localExit = (intersection.relativeExit .add (Vector3f(1.0f)) ).mul  (1.0f / 2.0f)
			System.out.println(localEntry);
			System.out.println(localExit);
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
				spine.instancedProperties["Metadata"] = { Vector4f(confidence, timepoint.toFloat()/volume.timepointCount, count.toFloat(), 0.0f) }

				hedgehogs.children.last().instances.add(spine)
			}
		}
	}

	val hedgehogIds = AtomicInteger(0)
	/**
	 * Dumps a given hedgehog including created tracks to a file.
	 * If [hedgehog] is null, the last created hedgehog will be used, otherwise the given one.
	 * If [hedgehog] is not null, the cell track will not be added to the scene.
	 */
	fun dumpHedgehog(hedgehog: Node? = null) {
		val lastHedgehog = hedgehog ?: hedgehogs.children.last()
        val hedgehogId = hedgehogIds.incrementAndGet()

		val hedgehogFile = sessionDirectory.resolve("Hedgehog_${hedgehogId}_${SystemHelpers.formatDateTime()}.csv").toFile()
		val hedgehogFileWriter = hedgehogFile.bufferedWriter()
		hedgehogFileWriter.write("Timepoint,Origin,Direction,LocalEntry,LocalExit,LocalDirection,HeadPosition,HeadOrientation,Position,Confidence,Samples\n")

		val trackFile = sessionDirectory.resolve("Tracks.tsv").toFile()
		val trackFileWriter = BufferedWriter(FileWriter(trackFile, true))
		if(!trackFile.exists()) {
			trackFile.createNewFile()
            trackFileWriter.write("# BionicTracking cell track listing for ${sessionDirectory.fileName}\n")
			trackFileWriter.write("# TIME\tX\tYt\t\tZ\tTRACK_ID\tPARENT_TRACK_ID\tSPOT\tLABEL\n")
		}

		val spines = lastHedgehog.instances.mapNotNull { spine ->
			spine.metadata["spine"] as? SpineMetadata
		}

		spines.forEach { metadata ->
			hedgehogFileWriter.write("${metadata.timepoint};${metadata.origin};${metadata.direction};${metadata.localEntry};${metadata.localExit};${metadata.localDirection};${metadata.headPosition};${metadata.headOrientation};${metadata.position};${metadata.position};${metadata.confidence};${metadata.samples.joinToString(";")}\n")
		}
		hedgehogFileWriter.close()

		logger.info("Written hedgehog to ${hedgehogFile.absolutePath}")

		val existingAnalysis = lastHedgehog.metadata["HedgehogAnalysis"] as? HedgehogAnalysis.Track
		val track = if(existingAnalysis is HedgehogAnalysis.Track) {
			existingAnalysis
		} else {
			val h = HedgehogAnalysis(spines, Matrix4f(volume.world))
			h.run()
		}

		if(track == null) {
			logger.warn("No track returned")
			scene.findObserver()?.showMessage("No track returned", messageColor = Vector4f(1.0f, 0.0f, 0.0f,1.0f))
			return
		}

		lastHedgehog.metadata["HedgehogAnalysis"] = track
		lastHedgehog.metadata["Spines"] = spines

		logger.info("---\nTrack: ${track.points.joinToString("\n")}\n---")

		val master = if(hedgehog == null) {
			val m = Cylinder(0.005f, 1.0f, 10)
			m.material = ShaderMaterial.fromFiles("DefaultDeferredInstanced.vert", "DefaultDeferred.frag")
			m.material.diffuse = Random.random3DVectorFromRange(0.2f, 0.8f)
			m.material.roughness = 1.0f
			m.material.metallic = 0.0f
			m.material.cullingMode = Material.CullingMode.None
			m.instancedProperties["ModelMatrix"] = { m.world }
			m.name = "Track-$hedgehogId"
            m
		} else {
			null
		}

		val parentId = 0
		val volumeDimensions = Vector3f(volume.viewerState.sources.firstOrNull()?.spimSource?.getSource(0,0)?.dimension(0)!!.toFloat(), volume.viewerState.sources.firstOrNull()?.spimSource?.getSource(0,0)?.dimension(1)!!.toFloat(), volume.viewerState.sources.firstOrNull()?.spimSource?.getSource(0,0)?.dimension(2)!!.toFloat())

		trackFileWriter.newLine()
		trackFileWriter.newLine()
		trackFileWriter.write("# START OF TRACK $hedgehogId, child of $parentId\n")
		track.points.windowed(2, 1).forEach { pair ->
			if(master != null) {
				val element = Mesh()
				element.orientBetweenPoints(pair[0].first, pair[1].first, rescale = true, reposition = true)
				element.parent = volume
				element.instancedProperties["ModelMatrix"] = { element.world }
				master.instances.add(element)
			}

			val p = Vector3f(pair[0].first).mul(volumeDimensions)//direct product
			val tp = pair[0].second.timepoint
			trackFileWriter.write("$tp\t${p.x()}\t${p.y()}\t${p.z()}\t${hedgehogId}\t$parentId\t0\t0\n")
		}

		master?.let { volume.addChild(it) }

		trackFileWriter.close()
	}

}

fun main(args: Array<String>) {
	BionicTracking().main()
}
