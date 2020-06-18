/*
 * Copyright 2020 Nicolas Maltais
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.maltaisn.swfconvert.render.svg

import com.maltaisn.swfconvert.core.*
import com.maltaisn.swfconvert.render.core.FramesRenderer
import org.apache.logging.log4j.kotlin.logger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider


/**
 * Convert all frames from the intermediate representation to SVG.
 */
class SvgFramesRenderer @Inject internal constructor(
        private val config: SvgConfiguration,
        private val progressCb: ProgressCallback,
        private val svgFrameRendererProvider: Provider<SvgFrameRenderer>
) : FramesRenderer {

    private val logger = logger()

    override suspend fun renderFrames(frameGroups: List<FrameGroup>) {
        // Move images and fonts from temp dir to output dir
        val outputDir = config.output.first().parentFile
        val outputImagesDir = File(outputDir, "images")
        val outputFontsDir = File(outputDir, "fonts")
        progressCb.showStep("Copying images and fonts to output", false) {

            val tempImagesDir = File(config.tempDir, "images")
            tempImagesDir.copyRecursively(outputImagesDir, true)

            val tempFontsDir = File(config.tempDir, "fonts")
            tempFontsDir.copyRecursively(outputFontsDir, true)
        }

        // Write frames
        var frames = frameGroups.withIndex().associate { (k, v) -> k to v }
        save@ while (true) {
            frames = renderFrames(frames, outputImagesDir, outputFontsDir)

            if (frames.isNotEmpty()) {
                // Some files couldn't be saved. Ask to retry.
                print("Could not save ${frames.size} files. Retry (Y/N)? ")
                retry@ while (true) {
                    when (readLine()?.toLowerCase()) {
                        "y" -> continue@save
                        "n" -> return
                        else -> continue@retry
                    }
                }
            } else {
                return
            }
        }
    }

    /**
     * Render [frameGroups], a map of frame by file index.
     * Returns a similar map for frames that couldn't be saved.
     */
    private suspend fun renderFrames(frameGroups: Map<Int, FrameGroup>,
                                     imagesDir: File, fontsDir: File): Map<Int, FrameGroup> {
        return progressCb.showStep("Writing SVG frames", true) {
            progressCb.showProgress(frameGroups.size) {
                val failed = ConcurrentHashMap<Int, FrameGroup>()

                frameGroups.entries.mapInParallel(config.parallelFrameRendering) { (i, frameGroup) ->
                    val renderer = svgFrameRendererProvider.get()

                    try {
                        renderer.renderFrame(i, frameGroup, imagesDir, fontsDir)
                    } catch (e: IOException) {
                        logger.warn { "Failed to save file ${config.output[i]}" }
                        failed[i] = frameGroup
                    }

                    progressCb.incrementProgress()
                }

                failed
            }
        }
    }

}
