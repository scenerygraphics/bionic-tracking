package graphics.scenery.attentivetracking

import cleargl.GLVector
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.ShaderType
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.PupilEyeTracker
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.scijava.Context
import org.scijava.ui.UIService
import org.scijava.ui.behaviour.ClickBehaviour
import org.scijava.widget.FileWidget
import java.io.File
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Example demonstrating attentive tracking, track objects by looking at them.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class AttentiveTracking: SceneryBase("Attentive Tracking Example", 1280, 720) {
	val pupilTracker = PupilEyeTracker(calibrationType = PupilEyeTracker.CalibrationType.ScreenSpace, port = 52262)
	val hmd = OpenVRHMD(seated = false, useCompositor = true)
	val referenceTarget = Icosphere(0.005f, 2)
	val laser = Cylinder(0.005f, 0.2f, 10)

	val hedgehog = Cylinder(0.005f, 1.0f, 3)
	lateinit var volume: Volume

	val confidenceThreshold = 0.60f

	var tracking = false
	var playing = true
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

		val shell = Box(GLVector(10.0f, 10.0f, 10.0f), insideNormals = true)
		shell.material.cullingMode = Material.CullingMode.None
		shell.material.diffuse = GLVector(0.01f, 0.01f, 0.01f)
		shell.material.specular = GLVector.getNullVector(3)
		shell.material.ambient = GLVector.getNullVector(3)
		shell.position = GLVector(0.0f, 4.0f, 0.0f)
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

		hedgehog.visible = false
		hedgehog.material = ShaderMaterial.fromClass(AttentiveTracking::class.java,
				listOf(ShaderType.VertexShader, ShaderType.FragmentShader))
		hedgehog.instancedProperties["ModelMatrix"] = { hedgehog.world }
		hedgehog.instancedProperties["Metadata"] = { GLVector(0.0f, 0.0f, 0.0f, 0.0f) }
		scene.addChild(hedgehog)

		val lights = Light.createLightTetrahedron<PointLight>(GLVector(0.0f, 4.0f, 0.0f), spread = 8.0f, radius = 15.0f)
		lights.forEach { scene.addChild(it) }

		fun nextVolume(): String {
			val v = volumes[currentVolume % volumes.size]
			currentVolume++

			return v
		}

		fun previousVolume(): String {
			val v = volumes[currentVolume % volumes.size]
			currentVolume--

			return v
		}

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

					volume.trangemax = 1500.0f
				}

				Thread.sleep(250)
			}
		}
	}

	val messages = ArrayList<TextBoard>(5)

	fun showMessage(message: String, scene: Scene, distance: Float = 0.5f, size: Float = 0.2f, duration: Int = 3000, color: GLVector = GLVector.getOneVector(3), background: GLVector = GLVector.getNullVector(3)) {
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
					}

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

								referenceTarget.position = referencePosition.clone()

//								laser.orientBetweenPoints(headCenter, referencePosition, rescale = false, reposition = true)

								if(tracking) {
									addSpine(headCenter, direction, volume, gaze.confidence, currentVolume.toFloat()/volumes.size.toFloat())
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

	fun addSpine(center: GLVector, direction: GLVector, volume: Volume, confidence: Float, timepoint: Float) {
		val sphere = volume.boundingBox?.getBoundingSphere() ?: return

		val sphereDirection = (sphere.origin - center)
		val sphereDist = sphereDirection.magnitude() - sphere.radius

		val p1 = center + direction * sphereDist
		val p2 = center + direction * (sphereDist + 2.2f * sphere.radius)

		val spine = Cylinder.betweenPoints(p1, p2, 0.01f, segments = 3)
		spine.visible = false

		val intersection = volume.intersectAABB(p1, (p2 - p1).normalize())

		if(intersection is MaybeIntersects.Intersection) {
			// get local entry and exit coordinates, and convert to UV coords
			val localEntry = (intersection.relativeEntry + GLVector.getOneVector(3)) * (1.0f / 2.0f)
			val localExit = (intersection.relativeExit + GLVector.getOneVector(3)) * (1.0f / 2.0f)

			val samples = volume.sampleRay(localEntry, localExit)

			if (samples != null) {
				spine.metadata["ray"] = samples.map { it ?: 0.0f }
				spine.instancedProperties["ModelMatrix"] = { spine.world }
				spine.instancedProperties["Metadata"] = { GLVector(confidence, timepoint, 0.0f, 0.0f) }

				val count = samples.filterNotNull().count { it > 100.0f }
				when {
					count in 0 .. 10 -> spine.material.diffuse = GLVector(0.0f, 0.5f, 0.0f)
					count in 11 .. 29 -> spine.material.diffuse = GLVector(0.0f, 0.8f, 0.0f)
					count in 30 .. 50 -> spine.material.diffuse = GLVector(0.0f, 0.5f, 0.5f)
					count in 50 .. 80 -> spine.material.diffuse = GLVector(0.0f, 1.0f, 1.0f)
					count in 80 .. 100 -> spine.material.diffuse = GLVector(0.5f, 0.0f, 0.0f)
					count > 100 -> spine.material.diffuse = GLVector(100.0f, 0.0f, 0.0f)
				}

				hedgehog.instances.add(spine)
			}
		}
	}

	fun dumpHedgehog() {
		val f = File(System.getProperty("user.home") + "/Desktop/Hedgehog_${System.nanoTime()}.csv")
		val writer = f.bufferedWriter()
		writer.write("Timepoint,Confidence,Samples\n")
		hedgehog.instances.forEach { spine ->
			val samples = spine.metadata["ray"] as? List<Float> ?: emptyList()
			val metadata = spine.instancedProperties["Metadata"]?.invoke() as? GLVector ?: GLVector.getNullVector(4)
			val confidence = metadata.x()
			val timepoint = metadata.y()

			writer.write("$timepoint,$confidence,${samples.joinToString(",")}\n")
		}
		writer.close()

		logger.info("Written hedgehog to ${f.absolutePath}")
	}

	@Test override fun main() {
		super.main()
	}
}
