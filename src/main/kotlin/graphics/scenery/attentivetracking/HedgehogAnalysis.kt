package graphics.scenery.attentivetracking

import cleargl.GLVector
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.utils.LazyLogger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class HedgehogAnalysis(val csv: File) {
    val logger by LazyLogger()

    val timepoints = LinkedHashMap<Int, ArrayList<SpineMetadata>>()
    val points = ArrayList<GLVector>()

    var avgConfidence = 0.0f
        private set
    var totalSampleCount = 0
        private set

    data class Track(
            val points: ArrayList<GLVector>,
            val confidence: Float
    )

    init {
        logger.info("Starting analysis with results from ${csv.absolutePath}")

        csv.readLines().drop(1).forEach { line ->
            val tokens = line.split(",")
            val timepoint = tokens[0].toInt()
            val confidence = tokens[1].toFloat()
            val samples = tokens.subList(2, tokens.size - 1).map { it.toFloat() }

            val current = timepoints[timepoint]
            val currentSpine = SpineMetadata(
                    timepoint,
                    GLVector.getNullVector(3),
                    GLVector.getNullVector(3),
                    GLVector.getNullVector(3),
                    GLVector.getNullVector(3),
                    GLVector.getNullVector(3),
                    Quaternion(),
                    GLVector.getNullVector(3),
                    confidence,
                    samples)

            if(current == null) {
                timepoints[timepoint] = arrayListOf(currentSpine)
            } else {
                current.add(currentSpine)
            }

            avgConfidence += confidence
            totalSampleCount++
        }

        avgConfidence /= totalSampleCount
    }

    private fun localMaxima(list: List<Float>): List<Pair<Int, Float>> =
        list.windowed(3, 1).mapIndexed { index, l ->
            val left = l[0]
            val center = l[1]
            val right = l[2]

            // we have a match at center
            if(left - center < 0 && center - right > 0) {
                index + 1 to center
            } else {
                null
            }
        }.filterNotNull()

    fun run(): Track {
        val startingThreshold = 0.125f
        val startingPoint = timepoints.entries.first { entry ->
            entry.value.any { metadata -> metadata.samples.filterNotNull().any { it > startingThreshold } }
        }

        logger.info("Starting point is ${startingPoint.key}/${timepoints.size} (threshold=$startingThreshold)")

        val residual = timepoints.entries.drop(timepoints.entries.indexOf(startingPoint))
        logger.info("${residual.size} timepoints left")

        residual.forEach {
            it.value.forEachIndexed { i, spine ->
                val max = localMaxima(spine.samples.filterNotNull())
                logger.info("Local maxima at ${it.key}/$i are: ${max.joinToString(",")}")
            }
        }

        return Track(points, avgConfidence)
    }
}

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("HedgehogAnalysisMain")
    if(args.isEmpty()) {
        logger.error("Sorry, but a file name is needed.")
        return
    }

    val file = File(args[0])
    val analysis = HedgehogAnalysis(file)

    val results = analysis.run()
    logger.info("Results: \n$results")
}