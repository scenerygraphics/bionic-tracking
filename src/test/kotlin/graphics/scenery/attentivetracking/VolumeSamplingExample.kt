package graphics.scenery.tests.examples.advanced

import cleargl.GLTypeEnum
import cleargl.GLVector
import coremem.enums.NativeTypeEnum
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.RingBuffer
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import org.junit.Test
import org.lwjgl.system.MemoryUtil.memAlloc
import org.scijava.ui.behaviour.ClickBehaviour
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.PI

/**
 * Example that renders procedurally generated volumes and samples from it.
 * [bitsPerVoxel] can be set to 8 or 16, to generate Byte or UnsignedShort volumes.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class VolumeSamplingExample: SceneryBase("Volume Sampling example", 1280, 720) {
    val bitsPerVoxel = 8
    lateinit var volume: Volume

    override fun init() {
        renderer = Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight)
        hub.add(SceneryElement.Renderer, renderer!!)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = GLVector(0.0f, 0.5f, 5.0f)
            perspectiveCamera(50.0f, 1.0f*windowWidth, 1.0f*windowHeight)
            active = true

            scene.addChild(this)
        }

        val shell = Box(GLVector(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material.cullingMode = Material.CullingMode.None
        shell.material.diffuse = GLVector(0.2f, 0.2f, 0.2f)
        shell.material.specular = GLVector.getNullVector(3)
        shell.material.ambient = GLVector.getNullVector(3)
        shell.position = GLVector(0.0f, 4.0f, 0.0f)
        scene.addChild(shell)

        volume = Volume()
        volume.name = "volume"
        volume.position = GLVector(0.0f, 0.0f, 0.0f)
        volume.colormap = "plasma"
        volume.voxelSizeX = 10.0f
        volume.voxelSizeY = 10.0f
        volume.voxelSizeZ = 10.0f
        with(volume.transferFunction) {
            addControlPoint(0.0f, 0.0f)
            addControlPoint(0.2f, 0.0f)
            addControlPoint(0.4f, 1.0f)
            addControlPoint(0.8f, 1.0f)
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
            light.intensity = 50.0f
            scene.addChild(light)
        }

        thread {
            while(!scene.initialized) { Thread.sleep(200) }

            val volumeSize = 128L
            val volumeBuffer = RingBuffer<ByteBuffer>(2) { memAlloc((volumeSize*volumeSize*volumeSize*bitsPerVoxel/8).toInt()) }

            val seed = Random.randomFromRange(0.0f, 133333337.0f).toLong()
            var shift = GLVector.getNullVector(3)
            val shiftDelta = Random.randomVectorFromRange(3, -1.5f, 1.5f)

            val dataType = if(bitsPerVoxel == 8) {
                NativeTypeEnum.UnsignedByte
            } else {
                NativeTypeEnum.UnsignedShort
            }

            while(running) {
                if(volume.metadata["animating"] == true) {
                    val currentBuffer = volumeBuffer.get()

                    Volume.generateProceduralVolume(volumeSize, 0.05f, seed = seed,
                        intoBuffer = currentBuffer, shift = shift, use16bit = bitsPerVoxel > 8)

                    volume.readFromBuffer(
                        "procedural-cloud-${shift.hashCode()}", currentBuffer,
                        volumeSize, volumeSize, volumeSize, 1.0f, 1.0f, 1.0f,
                        dataType = dataType, bytesPerVoxel = bitsPerVoxel / 8)

                    shift = shift + shiftDelta
                }

                Thread.sleep(200)
            }
        }
    }

    fun addRay(p1: GLVector, p2: GLVector) {
        val s1 = Icosphere(0.02f, 2)
        s1.position = p1
        s1.material.diffuse = GLVector(0.3f, 0.8f, 0.3f)
        scene.addChild(s1)

        val s2 = Icosphere(0.02f, 2)
        s2.position = p2
        s2.material.diffuse = GLVector(0.3f, 0.3f, 0.8f)
        scene.addChild(s2)

        val connector = Cylinder.betweenPoints(s1.position, s2.position)
        connector.material.diffuse = GLVector(1.0f, 1.0f, 1.0f)
        scene.addChild(connector)

        s1.update.add {
            connector.orientBetweenPoints(s1.position, s2.position, true, true)
        }

        s2.update.add {
            connector.orientBetweenPoints(s1.position, s2.position, true, true)
        }

        val intersection = volume.intersectAABB(s1.position, (s2.position - s1.position).normalize())
        if(intersection is MaybeIntersects.Intersection) {
            val scale = volume.localScale()
            val localEntry = (intersection.relativeEntry + GLVector.getOneVector(3)) * (1.0f / 2.0f)
            val localExit = (intersection.relativeExit + GLVector.getOneVector(3)) * (1.0f / 2.0f)
            logger.info("Ray intersects volume at ${intersection.entry}/${intersection.exit} rel=${localEntry}/${localExit} localScale=$scale")

            val samples = volume.sampleRay(localEntry, localExit)
            logger.info("Samples: ${samples?.joinToString(",") ?: "(no samples returned)"}")

            if (samples == null) {
                return
            }

            val diagram = Line(capacity = samples.size)
            diagram.name = "diagram"
            diagram.edgeWidth = 0.005f
            diagram.material.diffuse = GLVector(0.05f, 0.05f, 0.05f)
            diagram.position = GLVector(0.0f, 0.0f, -0.5f)
            diagram.addPoint(GLVector(0.0f, 0.0f, 0.0f))
            var point = GLVector.getNullVector(3)
            samples.filterNotNull().forEachIndexed { i, sample ->
                point = GLVector(0.0f, i.toFloat() / samples.size, -sample / 255.0f * 0.2f)
                diagram.addPoint(point)
            }
            diagram.addPoint(point)
            connector.addChild(diagram)
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        val toggleRenderingMode = object : ClickBehaviour {
            var modes = hashMapOf(0 to "Local MIP", 1 to "MIP", 2 to "Alpha Compositing")
            var currentMode = (scene.find("volume") as? Volume)?.renderingMethod ?: 0

            override fun click(x: Int, y: Int) {
                currentMode = (currentMode + 1) % modes.size

                (scene.find("volume") as? Volume)?.renderingMethod = currentMode
                logger.info("Switched volume rendering mode to ${modes[currentMode]} (${(scene.find("volume") as? Volume)?.renderingMethod})")
            }
        }

        inputHandler?.addBehaviour("toggle_rendering_mode", toggleRenderingMode)
        inputHandler?.addKeyBinding("toggle_rendering_mode", "M")

        val shootRay = object: ClickBehaviour {
            override fun click(x: Int, y: Int) {
                val cam = scene.findObserver() ?: return
                addRay(cam.position+cam.forward * 1.0f, cam.position + cam.forward * 3.0f)
            }
        }

        inputHandler?.addBehaviour("add_ray", shootRay)
        inputHandler?.addKeyBinding("add_ray", "R")
    }

    @Test override fun main() {
        super.main()
    }
}
