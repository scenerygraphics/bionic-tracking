/*-
 * #%L
 * Scenery-backed 3D visualization package for ImageJ.
 * %%
 * Copyright (C) 2016 - 2018 SciView developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package graphics.scenery.bionictracking

import ij.IJ
import net.imglib2.RandomAccess
import net.imglib2.img.Img
import net.imglib2.img.array.ArrayImgs
import net.imglib2.img.display.imagej.ImageJFunctions
import net.imglib2.type.numeric.integer.UnsignedShortType
import java.io.*
import java.lang.Integer.max
import java.lang.Integer.min
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.collections.HashSet

/**
 * Convert a mastodon track file into a directory of tiffs for comparison with Vlado's measure
 *
 * @author Kyle Harrington
 */
class CreateCellTrackingChallengeTiff {
    fun run(filename: Path) {
        val outDirectory = filename.resolveSibling("track_tiff_dir")
        val outputDims = longArrayOf(700, 660, 113)
        val numTimesteps = 600

        // End hard coded


        // ArrayImg<UnsignedShortType, ShortArray> output = ArrayImgs.unsignedShorts(outputDims[0], outputDims[1], outputDims[2], numTimesteps);// Make a single output image
        var outputRA: RandomAccess<UnsignedShortType> // = output.randomAccess();
        val reader: BufferedReader
        try {
            reader = filename.toFile().bufferedReader()
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return
        }
        val trackNodes = ArrayList<Array<Any>>()
        val pos = LongArray(4)

        val maxTimePerTrack = HashMap<Int, Int>()
        val minTimePerTrack = HashMap<Int, Int>()
        val parentPerTrack = HashMap<Int, Int>()

        reader.lines().forEach { line ->
            // Skip comments
            if (line!!.startsWith("#")) {
                return@forEach
            }

            if (line.length > 1) {
                val parts = line.split("\t".toRegex()).toTypedArray()
                //System.out.println(Arrays.toString(parts));
                val time = parts[0].toInt()
                val x = parts[1].toDouble()
                val y = parts[2].toDouble()
                val z = parts[3].toDouble()
                val trackId = parts[4].toInt()
                val parentTrackId = parts[5].toInt()
                val spotLabel = parts[6]
                val r = arrayOf<Any>(time, x, y, z, trackId, parentTrackId, spotLabel)
                trackNodes.add(r)

                maxTimePerTrack[trackId] = if( maxTimePerTrack.contains(trackId) )
                    max(maxTimePerTrack[trackId]!!, time )
                else
                    time

                minTimePerTrack[trackId] = if( minTimePerTrack.contains(trackId) )
                    min(minTimePerTrack[trackId]!!, time )
                else
                    time

                parentPerTrack[trackId] = parentTrackId

//                pos[0] = Math.round(x);
//                pos[1] = Math.round(y);
//                pos[2] = Math.round(z);
//                pos[3] = time;
//
//                outputRA.setPosition(pos);
//                outputRA.get().set(trackId);
            }
        }
        try {
            reader.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Now save tiffs
        if (!Files.exists(outDirectory)) {
            Files.createDirectory(outDirectory)
        }
        for (t in 0 until numTimesteps) {
            val outFrame: Img<UnsignedShortType> = ArrayImgs.unsignedShorts(outputDims[0], outputDims[1], outputDims[2])
            outputRA = outFrame.randomAccess()
            for (trackNode in trackNodes) {
                val time = trackNode[0] as Int
                val x = trackNode[1] as Double
                val y = trackNode[2] as Double
                val z = trackNode[3] as Double
                val trackId = trackNode[4] as Int
                val parentTrackId = trackNode[5] as Int
                val spotLabel = trackNode[6] as String
                if (time == t) {
                    pos[0] = Math.round(x)
                    pos[1] = Math.round(y)
                    pos[2] = Math.round(z)
                    pos[3] = time.toLong()
                    outputRA.setPosition(pos)
                    outputRA.get().set(trackId)
                }
            }
            val imp = ImageJFunctions.wrap(outFrame, "timestep_$t")
            //ImagePlus imp = ImageJFunctions.wrap(Views.hyperSlice(output, 3, t), "timestep_" + t);
            IJ.saveAsTiff(imp, outDirectory.fileName.toString() + "/output_${String.format("%05d", t)}.tif")
        }

        val writer = BufferedWriter(FileWriter(outDirectory.fileName.toString() + "/res_track.txt"))

        for( trackId in maxTimePerTrack.keys ){
            val TPfrom = minTimePerTrack[trackId]
            val TPtill = maxTimePerTrack[trackId]
            val parentTrackID = parentPerTrack[trackId]

            val s = "$trackId $TPfrom $TPtill $parentTrackID\n"

            writer.write(s)
        }

        writer.close()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            //val inputFile = args.getOrNull(1) ?: throw IllegalStateException("Need to give input file as argument!")
            val inputFile = "/home/kharrington/Data/CellTrackingChallenge/VladoUlrikBT/with_reorganized_tree.txtExportedTracks.txt"
            val converter = CreateCellTrackingChallengeTiff()
            converter.run(Paths.get(inputFile))
        }
    }
}