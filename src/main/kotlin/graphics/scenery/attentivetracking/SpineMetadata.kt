package graphics.scenery.attentivetracking

import cleargl.GLVector
import com.jogamp.opengl.math.Quaternion

data class SpineMetadata(
        val timepoint: Int,
        val origin: GLVector,
        val direction: GLVector,
        val localEntry: GLVector,
        val localExit: GLVector,
        val headPosition: GLVector,
        val headOrientation: Quaternion,
        val position: GLVector,
        val confidence: Float,
        val samples: List<Float?>
)