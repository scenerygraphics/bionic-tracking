package graphics.scenery.attentivetracking

import cleargl.GLVector
import com.jogamp.opengl.math.Quaternion

/**
 * Data class to store metadata for spines of the hedgehog.
 */
data class SpineMetadata(
        val timepoint: Int,
        val origin: GLVector,
        val direction: GLVector,
		val distance: Float,
        val localEntry: GLVector,
        val localExit: GLVector,
		val localDirection: GLVector,
        val headPosition: GLVector,
        val headOrientation: Quaternion,
        val position: GLVector,
        val confidence: Float,
        val samples: List<Float?>
)