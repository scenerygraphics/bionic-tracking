package graphics.scenery.attentivetracking

import cleargl.GLTypeEnum
import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.ShaderType
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.PupilEyeTracker
import graphics.scenery.controls.TrackedDeviceType
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
 * Example demonstrating attentive tracking, track objects by looking at them.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class AttentiveTracking: SceneryBase("Attentive Tracking Example", 1280, 720) {
	val pupilTracker = PupilEyeTracker(calibrationType = PupilEyeTracker.CalibrationType.ScreenSpace, port = 52262)
	val hmd = OpenVRHMD(seated = false, useCompositor = true)
	val referenceTarget = Icosphere(0.002f, 2)
	val laser = Cylinder(0.005f, 0.2f, 10)

	val hedgehog = Cylinder(0.001f, 1.0f, 16)
	lateinit var volume: Volume

	val confidenceThreshold = 0.60f

	var tracking = false
	var playing = false
	var skipToNext = false
	var skipToPrevious = false
	var currentVolume = 0

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
		scene.addChild(referenceTarget)

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
		volume.metadata["volumesPerSecond"] = 8
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

		hedgehog.visible = false
		hedgehog.material = ShaderMaterial.fromClass(AttentiveTracking::class.java,
				listOf(ShaderType.VertexShader, ShaderType.FragmentShader))
		hedgehog.instancedProperties["ModelMatrix"] = { hedgehog.world }
		hedgehog.instancedProperties["Metadata"] = { GLVector(0.0f, 0.0f, 0.0f, 0.0f) }
		scene.addChild(hedgehog)


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
					val newVolume = if(skipToNext || playing) {
						skipToNext = false
						nextVolume()
					} else {
						skipToPrevious = false
						previousVolume()
					}

					logger.debug("Loading volume $newVolume")
					if(newVolume.toLowerCase().endsWith("raw")) {
						volume.readFromRaw(Paths.get(newVolume), autorange = false, cache = true, replace = false)
					} else {
						volume.readFrom(Paths.get(newVolume), replace = false)
					}

					if(hedgehog.visible) {
						hedgehog.instances.forEach {
							it.visible = (it.metadata["spine"] as SpineMetadata).timepoint == currentVolume
						}
					}

					volume.trangemax = 1500.0f
				}

				val sleepDuration = 1000L / (volume.metadata["volumesPerSecond"] as Int)
				Thread.sleep(sleepDuration)
			}
		}
	}

	fun nextVolume(): String {
		currentVolume = (currentVolume + 1) % volumes.size
		return volumes[currentVolume]
	}

	fun previousVolume(): String {
		currentVolume = (currentVolume - 1) % volumes.size
		return volumes[currentVolume]
	}

	val messages = ArrayList<TextBoard>(5)

	fun showMessage(message: String, scene: Scene, distance: Float = 1.5f, size: Float = 0.1f, duration: Int = 3000, color: GLVector = GLVector.getOneVector(3), background: GLVector = GLVector.getNullVector(3)) {
		val cam = scene.findObserver() ?: return
		val tb = TextBoard()
		tb.fontColor = color
		tb.backgroundColor = background
		tb.text = message
		tb.scale = GLVector(size, size, size)
		tb.position = GLVector(0.0f, 1.5f, -distance)

		messages.forEach { cam.removeChild(it) }
		messages.clear()

		messages.add(tb)
		cam.addChild(tb)

		thread {
			Thread.sleep(duration.toLong())

			cam.removeChild(tb)
			messages.remove(tb)
		}
	}

	override fun inputSetup() {
		inputHandler?.let { handler ->
			hashMapOf(
					"move_forward_fast" to "W",
					"move_back_fast" to "S",
					"move_left_fast" to "A",
					"move_right_fast" to "D").forEach { name, key ->
				handler.getBehaviour(name)?.let { b ->
					hmd.addBehaviour(name, b)
					hmd.addKeyBinding(name, key)
				}
			}
		}

		val toggleHedgehog = ClickBehaviour { _, _ ->
			if(hedgehog.visible) {
				showMessage("Hedgehog hidden", scene)
			} else {
				showMessage("Hedgehog visible", scene)
			}
			hedgehog.visible = !hedgehog.visible
		}

		hmd.addBehaviour("toggle_hedgehog", toggleHedgehog)
		hmd.addKeyBinding("toggle_hedgehog", "X")

		setupCalibration()
	}

	private fun setupCalibration() {
		val startCalibration = ClickBehaviour { _, _ ->
			thread {
				val cam = scene.findObserver() as? DetachedHeadCamera ?: return@thread
				pupilTracker.gazeConfidenceThreshold = confidenceThreshold
				if (!pupilTracker.isCalibrated) {
					pupilTracker.onCalibrationFailed = {
						showMessage("Calibration failed.", scene, background = GLVector(1.0f, 0.0f, 0.0f))
					}

					pupilTracker.onCalibrationSuccess = {
						showMessage("Calibration succeeded!", scene)
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
								showMessage("Tracking deactivated.", scene)
								dumpHedgehog()
							} else {
								referenceTarget.material.diffuse = GLVector(1.0f, 0.0f, 0.0f)
								showMessage("Tracking active.", scene)
							}
							tracking = !tracking
						}
						hmd.addBehaviour("toggle_tracking", toggleTracking)
						hmd.addKeyBinding("toggle_tracking", "T")

						volume.visible = true
						playing = true
					}

					pupilTracker.unsubscribeFrames()
					scene.removeChild("eyeFrames")

					logger.info("Starting eye tracker calibration")
					showMessage("Starting calibration", scene, duration = 1500)
					pupilTracker.calibrate(cam, hmd,
							generateReferenceData = true,
							calibrationTarget = referenceTarget)

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
							when {
								gaze.confidence < confidenceThreshold -> referenceTarget.material.diffuse = GLVector(1.0f, 0.0f, 0.0f)
								gaze.confidence < 0.85f && gaze.confidence > confidenceThreshold -> referenceTarget.material.diffuse = GLVector(0.0f, 0.3f, 0.3f)
								gaze.confidence > 0.85f -> referenceTarget.material.diffuse = GLVector(0.0f, 0.5f, 0.5f)
								gaze.confidence > 0.95f -> referenceTarget.material.diffuse = GLVector(0.0f, 1.0f, 0.0f)
							}

							if (gaze.confidence > confidenceThreshold) {
								referenceTarget.visible = true
								referenceTarget.position = gaze.gazePoint()
							}
						}
					}
				}
			}

			logger.info("Calibration routine done.")
		}

		// bind calibration start to menu key on controller
		hmd.addBehaviour("start_calibration", startCalibration)
		hmd.addKeyBinding("start_calibration", "T")
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

			val samples = volume.sampleRay(localEntry, localExit)

			if (samples != null) {
				val metadata = SpineMetadata(
						timepoint,
						center,
						direction,
						localEntry,
						localExit,
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

				hedgehog.instances.add(spine)
			}
		}
	}

	fun dumpHedgehog() {
		val f = File(System.getProperty("user.home") + "/Desktop/Hedgehog_${SystemHelpers.formatDateTime()}.csv")
		val writer = f.bufferedWriter()
		writer.write("Timepoint,Confidence,Samples\n")
		hedgehog.instances.forEach { spine ->
			val metadata = spine.metadata["spine"] as? SpineMetadata ?: return@forEach
			val confidence = metadata.confidence
			val timepoint = metadata.timepoint

			writer.write("$timepoint,$confidence,${metadata.samples.joinToString(",")}\n")
		}
		writer.close()

		logger.info("Written hedgehog to ${f.absolutePath}")
	}
}

fun main(args: Array<String>) {
	AttentiveTracking().main()
}
