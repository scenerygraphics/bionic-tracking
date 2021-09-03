package graphics.scenery.attentivetracking

import graphics.scenery.bionictracking.HedgehogAnalysis
import graphics.scenery.bionictracking.SpineMetadata
import graphics.scenery.utils.extensions.minus
import org.joml.Vector3f
import org.jruby.RubyProcess
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.sqrt

class test {

    data class tt(val value: Int,
                                var previous: tt? = null,
                                var next: tt? = null) {

        fun distance(): Float {
            val n = next
            return if(n != null) {
                val t = n.value - this.value
                t.toFloat()
            } else {
                0.0f
            }

        }

    }



}

fun main(args: Array<String>) {
    var v0 = test.tt(0)
    var v1 = test.tt(1)
    var v2 = test.tt(2)
    var v3 = test.tt(3)
    var v4 = test.tt(10)
    var v5 = test.tt(5)
    var v6 = test.tt(6)
    var v7 = test.tt(7)
    var v8 = test.tt(8)
    var v9 = test.tt(9)


    var sourceList = mutableListOf(v0, v1, v2, v3, v4, v5 ,v6, v7, v8, v9)
    sourceList.windowed(3, 1, partialWindows = true).forEach {
        it.getOrNull(0)?.next = it.getOrNull(1)
        it.getOrNull(1)?.previous = it.getOrNull(0)
        it.getOrNull(1)?.next = it.getOrNull(2)
        it.getOrNull(2)?.previous = it.getOrNull(1)
    }


    val zscoreThreshold = 2.0f
    fun Iterable<Float>.stddev() = sqrt((this.map { (it - this.average()) * (it - this.average()) }.sum() / this.count()))
    var avgPathLength = sourceList.map { it.distance() }.average().toFloat()
    var stdDevPathLength = sourceList.map { it.distance() }.stddev().toFloat()
    System.out.println("avgPath: "+ avgPathLength)

    fun zScore(value: Float, m: Float, sd: Float) = ((value - m)/sd)

    var remaining = sourceList.count { zScore(it.distance(), avgPathLength, stdDevPathLength) > zscoreThreshold }
    while (remaining >0) {
        System.out.println("I am called")
        val outliers = sourceList
                .filter {zScore(it.distance(), avgPathLength, stdDevPathLength) > zscoreThreshold  }
                .map {
                    val idx = sourceList.indexOf(it.next)
                    listOf(idx)
                }.flatten()

        sourceList = sourceList.filterIndexed { index, _ -> index !in outliers }.toMutableList()

        sourceList.windowed(3, 1, partialWindows = true).forEach {
            it.getOrNull(0)?.next = it.getOrNull(1)
            it.getOrNull(1)?.previous = it.getOrNull(0)
            it.getOrNull(1)?.next = it.getOrNull(2)
            it.getOrNull(2)?.previous = it.getOrNull(1)
        }


        avgPathLength = sourceList.map { it.distance() }.average().toFloat()
        stdDevPathLength = sourceList.map { it.distance() }.stddev().toFloat()
        System.out.println("avgPath: "+ avgPathLength)
        System.out.println("stdPath: "+ stdDevPathLength)

        for(item in sourceList)
        {
            System.out.println(item.value.toString() + " "+ item.distance() + " " +  zScore(item.distance(), avgPathLength, stdDevPathLength))
        }
        remaining = sourceList.count { zScore(it.distance(), avgPathLength, stdDevPathLength) > zscoreThreshold }
        System.out.println("reamining : " + remaining)
    }


//    while (sourceList.any { it.distance() > 2.0f * avgPathLength }) {
//        System.out.println("I am called")
//        val outliers = sourceList
//                .filter { it.distance() > 2.0f * avgPathLength }
//                .map {
//                    val idx = sourceList.indexOf(it.next)
//                    listOf(idx)
//                }.flatten()
//
//        sourceList = sourceList.filterIndexed { index, _ -> index !in outliers }.toMutableList()
//
//        sourceList.windowed(3, 1, partialWindows = true).forEach {
//            it.getOrNull(0)?.next = it.getOrNull(1)
//            it.getOrNull(1)?.previous = it.getOrNull(0)
//            it.getOrNull(1)?.next = it.getOrNull(2)
//            it.getOrNull(2)?.previous = it.getOrNull(1)
//        }
//
//        for(item in sourceList)
//        {
//            System.out.println(item.value.toString() + " "+ item.distance())
//        }
//        avgPathLength = sourceList.map { it.distance() }.average().toFloat()
//        System.out.println("avgPath: "+ avgPathLength)
//
//    }
}