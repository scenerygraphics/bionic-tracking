package graphics.scenery.attentivetracking

import cleargl.GLVector
import coremem.enums.NativeTypeEnum
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.OpenVRHMD
import graphics.scenery.controls.PupilEyeTracker
import graphics.scenery.controls.TrackedDeviceType
import graphics.scenery.numerics.Random
import graphics.scenery.utils.RingBuffer
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.lwjgl.system.MemoryUtil.memAlloc
import org.scijava.ui.behaviour.ClickBehaviour
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Example demonstrating attentive tracking, track objects by looking at them.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class AttentiveTrackingExample: SceneryBase("Attentive Tracking Example", 1280, 720) {
    val bitsPerVoxel = 8
    val pupilTracker = PupilEyeTracker(calibrationType = PupilEyeTracker.CalibrationType.ScreenSpace)
    val hmd = OpenVRHMD(seated = false, useCompositor = true)
    val referenceTarget = Icosphere(0.005f, 2)
	val laser = Cylinder(0.005f, 0.2f, 10)

	val confidenceThreshold = 0.60f

    override fun init() {
        hub.add(SceneryElement.HMDInput, hmd)
        renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
        renderer?.toggleVR()
        hub.add(SceneryElement.Renderer, renderer!!)

        val cam: Camera = DetachedHeadCamera(hmd)
        with(cam) {
            position = GLVector(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, 1.0f*windowWidth, 1.0f*windowHeight)
            active = true

            scene.addChild(this)
        }

        referenceTarget.visible = false
        referenceTarget.material.roughness = 1.0f
        referenceTarget.material.metallic = 0.5f
        referenceTarget.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
        scene.addChild(referenceTarget)

		laser.visible = false
		laser.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
		scene.addChild(laser)

        val shell = Box(GLVector(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = GLVector(0.2f, 0.2f, 0.2f)
        shell.material.specular = GLVector.getNullVector(3)
        shell.material.ambient = GLVector.getNullVector(3)
        shell.position = GLVector(0.0f, 4.0f, 0.0f)
        scene.addChild(shell)

        val p1 = Icosphere(0.2f, 2)
        p1.position = GLVector(-2.0f, 0.0f, 0.0f)
        p1.material.diffuse = GLVector(0.3f, 0.3f, 0.8f)
        scene.addChild(p1)

        val p2 = Icosphere(0.2f, 2)
        p2.position = GLVector(2.0f, 0.0f, 0.0f)
        p2.material.diffuse = GLVector(0.3f, 0.8f, 0.3f)
        scene.addChild(p2)

        val connector = Cylinder.betweenPoints(p1.position, p2.position)
        connector.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
        scene.addChild(connector)

        p1.update.add {
            connector.orientBetweenPoints(p1.position, p2.position, true, true)
        }

        p2.update.add {
            connector.orientBetweenPoints(p1.position, p2.position, true, true)
        }

        val volume = Volume()
        volume.name = "volume"
        volume.position = GLVector(0.0f, 0.0f, 0.0f)
        volume.colormap = "viridis"
        volume.voxelSizeX = 20.0f
        volume.voxelSizeY = 20.0f
        volume.voxelSizeZ = 20.0f
        with(volume.transferFunction) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.2f, 0.0f)
            addControlPoint(0.4f, 0.5f)
            addControlPoint(0.8f, 0.5f)
            addControlPoint(1.0f, 0.0f)
        }

        volume.metadata["animating"] = true
        scene.addChild(volume)

        val bb = BoundingGrid()
        bb.node = volume

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.position = GLVector(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = GLVector(1.0f, 1.0f, 1.0f)
            light.intensity = 0.8f
            scene.addChild(light)
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

            val volumeSize = 128L
            val volumeBuffer = RingBuffer<ByteBuffer>(2) { memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) }

            val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
            var shift = GLVector.getNullVector(3)

            val dataType = if(bitsPerVoxel == 8) {
                NativeTypeEnum.UnsignedByte
            } else {
                NativeTypeEnum.UnsignedShort
            }

            while(running) {
                if(volume.metadata["animating"] == true) {
					val shiftDelta = Random.randomVectorFromRange(3, -1.5f, 1.5f)
                    val currentBuffer = volumeBuffer.get()

                    Volume.generateProceduralVolume(volumeSize, 0.01f, seed = seed,
                        intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

                    volume.readFromBuffer(
                        "procedural-cloud-${shift.hashCode()}", currentBuffer,
                        volumeSize, volumeSize, volumeSize, 1.0f, 1.0f, 1.0f,
                        dataType = dataType, bytesPerVoxel = bitsPerVoxel / 8)

                    shift = shift + shiftDelta
                }

				/*
                val intersection = volume.intersectAABB(p1.position, (p2.position - p1.position).normalize())
                if(intersection is MaybeIntersects.Intersection) {
                    val localEntry = (intersection.relativeEntry + GLVector.getOneVector(3)) * (1.0f/2.0f)
                    val localExit = (intersection.relativeExit + GLVector.getOneVector(3)) * (1.0f/2.0f)

                    val samples = volume.sampleRay(localEntry, localExit) ?: continue

                    connector.children.firstOrNull()?.visible = false
                    connector.removeChild("diagram")
                    val diagram = Line(capacity = samples.size)
                    diagram.name = "diagram"
                    diagram.edgeWidth = 0.005f
                    diagram.material.diffuse = GLVector(0.05f, 0.05f, 0.05f)
                    diagram.position = GLVector(0.0f, 0.0f, -0.5f)
                    diagram.updateWorld(true)
                    diagram.addPoint(GLVector(0.0f, 0.0f, 0.0f))
                    var point = GLVector.getNullVector(3)
                    samples.filterNotNull().forEachIndexed { i, sample ->
                        point = GLVector(0.0f, i.toFloat()/samples.size, -sample/255.0f * 0.2f)
                        diagram.addPoint(point)
                    }
                    diagram.addPoint(point)
                    connector.addChild(diagram)
                }
                */

                Thread.sleep(20)
            }
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

        val trigger = ClickBehaviour { p0, p1 -> logger.info("Trigger pushed, awesome!") }

        hmd.addBehaviour("push_trigger", trigger)
        hmd.addKeyBinding("push_trigger", "T")
        setupCalibration()
    }

    private fun setupCalibration() {
        val startCalibration = ClickBehaviour { _, _ ->
            thread {
                val cam = scene.findObserver() as? DetachedHeadCamera ?: return@thread
				pupilTracker.gazeConfidenceThreshold = confidenceThreshold
                if (!pupilTracker.isCalibrated) {
                    pupilTracker.onCalibrationFailed = {
                        for (i in 0 until 2) {
                            referenceTarget.material.diffuse = GLVector(1.0f, 0.0f, 0.0f)
                            Thread.sleep(300)
                            referenceTarget.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
                            Thread.sleep(300)
                        }
                    }

                    pupilTracker.onCalibrationSuccess = {
                        for (i in 0 until 20) {
                            referenceTarget.material.diffuse = GLVector(0.0f, 1.0f, 0.0f)
                            Thread.sleep(100)
                            referenceTarget.material.diffuse = GLVector(0.8f, 0.8f, 0.8f)
                            Thread.sleep(30)
                        }
                    }

                    logger.info("Starting eye tracker calibration")
                    pupilTracker.calibrate(cam, hmd,
                        generateReferenceData = true,
                        calibrationTarget = referenceTarget)

                    pupilTracker.onGazeReceived = when (pupilTracker.calibrationType) {
                        PupilEyeTracker.CalibrationType.ScreenSpace -> { gaze ->
                            when {
								gaze.confidence < confidenceThreshold -> referenceTarget.material.diffuse = GLVector(1.0f, 0.0f, 0.0f)
                                gaze.confidence < 0.85f && gaze.confidence > confidenceThreshold -> referenceTarget.material.diffuse = GLVector(0.8f, 0.0f, 0.0f)
                                gaze.confidence > 0.85f -> referenceTarget.material.diffuse = GLVector(0.0f, 0.5f, 0.5f)
                                gaze.confidence > 0.95f -> referenceTarget.material.diffuse = GLVector(0.0f, 1.0f, 0.0f)
                            }

                            if (gaze.confidence > confidenceThreshold) {
                                referenceTarget.visible = true
								laser.visible = true
                                val referencePosition = cam.viewportToWorld(
                                    GLVector(
                                        gaze.normalizedPosition().x() * 2.0f - 1.0f,
                                        gaze.normalizedPosition().y() * 2.0f - 1.0f),
                                    offset = 0.5f) + cam.forward * 0.15f

								val headCenter = cam.viewportToWorld(GLVector(0.0f, 0.0f))

								referenceTarget.position = referencePosition.clone()

								val direction = (referencePosition - headCenter).normalize()

								laser.orientBetweenPoints(headCenter, referencePosition, rescale = false, reposition = true)
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
        hmd.addKeyBinding("start_calibration", "M")
    }

    @Test override fun main() {
        super.main()
    }
}
