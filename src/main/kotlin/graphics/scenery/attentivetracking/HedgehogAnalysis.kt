package graphics.scenery.attentivetracking

import cleargl.GLVector
import com.jogamp.opengl.math.Quaternion
import graphics.scenery.utils.LazyLogger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.sqrt

/**
 * <Description>
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
class HedgehogAnalysis(val spines: List<SpineMetadata>) {
    val logger by LazyLogger()

    val timepoints = LinkedHashMap<Int, ArrayList<SpineMetadata>>()

    var avgConfidence = 0.0f
        private set
    var totalSampleCount = 0
        private set

    data class Track(
            val points: List<Pair<GLVector, SpineGraphVertex>>,
            val confidence: Float
    )

    init {
		logger.info("Starting analysis with ${spines.size} spines")

		spines.forEach { spine ->
			val timepoint = spine.timepoint
			val current = timepoints[timepoint]

            if(current == null) {
                timepoints[timepoint] = arrayListOf(spine)
            } else {
                current.add(spine)
            }

            avgConfidence += spine.confidence
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

	data class SpineGraphVertex(val timepoint: Int,
								val position: GLVector,
								val worldPosition: GLVector,
								val value: Float,
								val metadata : SpineMetadata,
								var previous: SpineGraphVertex? = null,
								var next: SpineGraphVertex? = null) {

		fun distance(): Float {
			val prev = previous
			return if(prev != null) {
				(prev.worldPosition - this.worldPosition).magnitude()
			} else {
				0.0f
			}
		}

		fun drop() {
			previous?.next = next
			next?.previous = previous
		}

		override fun toString() : String {
			return "SpineGraphVertex for t=$timepoint, pos=$position, worldPos=$worldPosition, value=$value ($metadata)"
		}
	}

	fun GLVector.toQuaternion(forward: GLVector = GLVector(0.0f, 0.0f, -1.0f)): Quaternion {
		val cross = forward.cross(this)
		val q = Quaternion(cross.x(), cross.y(), cross.z(), this.times(forward))

		val x = sqrt((q.w + sqrt(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w)) / 2.0f)

		return Quaternion(q.x/(2.0f * x), q.y/(2.0f * x), q.z/(2.0f * x), x)
	}

    fun run(): Track? {
        val startingThreshold = 0.02f
		val localMaxThreshold = 0.01f

		if(timepoints.isEmpty()) {
			return null
		}

        val startingPoint = timepoints.entries.firstOrNull { entry ->
			entry.value.any { metadata -> metadata.samples.filterNotNull().any { it > startingThreshold } }
		} ?: return null

		logger.info("Starting point is ${startingPoint.key}/${timepoints.size} (threshold=$startingThreshold)")

//        val remainingTimepoints = timepoints.entries.drop(timepoints.entries.indexOf(startingPoint))

		timepoints.filter { it.key < startingPoint.key }
				.forEach { timepoints.remove(it.key) }

		logger.info("${timepoints.size} timepoints left")

		val candidates = timepoints.map { tp ->
            val vs = tp.value.mapIndexedNotNull { i, spine ->
                val maxIndices = localMaxima(spine.samples.filterNotNull())
                logger.info("Local maxima at ${tp.key}/$i are: ${maxIndices.joinToString(",")}")

				// compare gaze and head orientation

//				if(spine.headOrientation.dot(spine.direction.toQuaternion()) > 0.0f) {
//					return@mapIndexedNotNull null
//				}

				if(maxIndices.isNotEmpty()) {
					maxIndices.map { index ->
						val worldPosition = spine.position + spine.direction * spine.distance
						SpineGraphVertex(tp.key,
								spine.localEntry + spine.localDirection * index.first.toFloat(),
								worldPosition,
								index.second,
								spine)
					}
				} else {
					null
				}
            }
			vs
        }.flatten()


		// get the initial vertex, this one is assumed to always be in front, and have a local max
		val initial = candidates.first().first()
		var current = initial

		var shortestPath = candidates.drop(1).mapIndexedNotNull { time, vs ->
			val distances = vs
					.filter { it.value > localMaxThreshold }
					.map { vertex ->
						val distance = (current.worldPosition - vertex.worldPosition).magnitude()
						vertex to distance
					}
					.sortedBy { it.second }

			logger.info("Minimum distance for t=$time d=${distances.firstOrNull()?.second}")

			// select closest vertex
			val closest = distances.firstOrNull()?.first
			if(closest != null) {
				current.next = closest
				closest.previous = current
				current = closest
				current
			} else {
				null
			}
		}

		shortestPath.windowed(3, 1).forEach {
			it[1].previous = it[0]
			it[1].next = it[2]
		}

		val avgPathLength = shortestPath.map { it.distance() }.average().toFloat()
		logger.info("Average path length=$avgPathLength")

		val beforeCount = shortestPath.size
		while(shortestPath.any { it.distance() >= 2.0f * avgPathLength }) {
			shortestPath.filter { it.distance() >= 2.0f * avgPathLength }.forEach { it.drop() }
			shortestPath = shortestPath.filter { it.distance() < 2.0f * avgPathLength }
		}
		val afterCount = shortestPath.size
		logger.info("Pruned ${beforeCount - afterCount} vertices due to path length")
		logger.info("Final distances: ${shortestPath.joinToString { "d = ${it.distance()}" }}")

		val singlePoints = shortestPath
				.groupBy { it.timepoint }
				.mapNotNull { vs -> vs.value.maxBy { it.metadata.confidence } }
				.filter {
					it.metadata.direction.times(it.previous!!.metadata.direction) > 0.85f
				}


		logger.info("Returning ${singlePoints.size} points")

        return Track(singlePoints.map { it.position * 2.0f - GLVector.getOneVector(3) to it }, avgConfidence)
    }

	companion object {
		private val logger by LazyLogger()

		fun fromIncompleteCSV(csv: File, separator: String = ","): HedgehogAnalysis {
			logger.info("Loading spines from incomplete CSV at ${csv.absolutePath}")

			val lines = csv.readLines()
			val spines = ArrayList<SpineMetadata>(lines.size)

			lines.drop(1).forEach { line ->
				val tokens = line.split(separator)
				val timepoint = tokens[0].toInt()
				val confidence = tokens[1].toFloat()
				val samples = tokens.subList(2, tokens.size - 1).map { it.toFloat() }

				val currentSpine = SpineMetadata(
						timepoint,
						GLVector.getNullVector(3),
						GLVector.getNullVector(3),
						0.0f,
						GLVector.getNullVector(3),
						GLVector.getNullVector(3),
						GLVector.getNullVector(3),
						GLVector.getNullVector(3),
						Quaternion(),
						GLVector.getNullVector(3),
						confidence,
						samples)

				spines.add(currentSpine)
			}

			return HedgehogAnalysis(spines)
		}

		private fun String.toGLVector(): GLVector {
			val array = this
					.trim()
					.trimEnd()
					.replace("[[", "").replace("]]", "")
					.split(",")
					.map { it.trim().trimEnd().toFloat() }.toFloatArray()

			return GLVector(*array)
		}

		private fun String.toQuaternion(): Quaternion {
			val array = this
					.trim()
					.trimEnd()
					.replace("Quaternion[", "").replace("]", "")
					.split(",")
					.map { it.trim().trimEnd().replace("x ", "").replace("y ", "").replace("z ","").replace("w ", "").toFloat() }

			return Quaternion(array[0], array[1], array[2], array[3])
		}

		fun fromCSV(csv: File, separator: String = ";"): HedgehogAnalysis {
			logger.info("Loading spines from complete CSV at ${csv.absolutePath}")

			val lines = csv.readLines()
			val spines = ArrayList<SpineMetadata>(lines.size)

			lines.drop(1).forEach { line ->
				val tokens = line.split(separator)
				val timepoint = tokens[0].toInt()
				val origin = tokens[1].toGLVector()
				val direction = tokens[2].toGLVector()
				val localEntry = tokens[3].toGLVector()
				val localExit = tokens[4].toGLVector()
				val localDirection = tokens[5].toGLVector()
				val headPosition = tokens[6].toGLVector()
				val headOrientation = tokens[7].toQuaternion()
				val position = tokens[8].toGLVector()
				val confidence = tokens[9].toFloat()
				val samples = tokens.subList(10, tokens.size - 1).map { it.toFloat() }

				val currentSpine = SpineMetadata(
						timepoint,
						origin,
						direction,
						0.0f,
						localEntry,
						localExit,
						localDirection,
						headPosition,
						headOrientation,
						position,
						confidence,
						samples)

				spines.add(currentSpine)
			}

			return HedgehogAnalysis(spines)
		}
	}
}

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("HedgehogAnalysisMain")
    if(args.isEmpty()) {
        logger.error("Sorry, but a file name is needed.")
        return
    }

    val file = File(args[0])
    val analysis = HedgehogAnalysis.fromIncompleteCSV(file)

    val results = analysis.run()
    logger.info("Results: \n$results")
}