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

package com.maltaisn.swfconvert.core.font

import com.flagstone.transform.Movie
import com.flagstone.transform.font.DefineFont
import com.flagstone.transform.font.DefineFont2
import com.flagstone.transform.font.DefineFont3
import com.flagstone.transform.font.DefineFont4
import com.maltaisn.swfconvert.core.Configuration
import com.maltaisn.swfconvert.core.conversionError
import com.maltaisn.swfconvert.core.font.data.*
import com.maltaisn.swfconvert.core.old.font.GlyphPathParser
import com.maltaisn.swfconvert.core.validateFilename
import java.io.File
import java.text.NumberFormat
import java.util.*


class FontConverter(private val fontsDir: File,
                    private val config: Configuration) {

    private val glyphOcr = GlyphOcr(File(fontsDir, "ocr"))

    private val unknownCharsMap = mutableMapOf<GlyphData, Char>()
    private var nextUnknownCharCode = 0

    /**
     * Each SWF file has its own fonts, which are sometimes subsetted.
     * To reduce file size when converting multiple SWFs to a single file, the number of fonts
     * is reduced by merging them. Fonts with common glyph shapes are grouped as a single font.
     * This grouping is not perfect, but can sometimes reduce the number of fonts by 50x-100x.
     */
    fun createFontGroups(swfs: List<Movie>): List<FontGroup> {
        // Create font destination folder
        fontsDir.deleteRecursively()
        fontsDir.mkdirs()

        // Create fonts for each font tag in each file.
        val allFonts = createAllFonts(swfs)

        // Merge fonts with the same name if they are compatible.
        print("Creating fonts: merging fonts\r")
        val groups = mergeFonts(allFonts)
        if (config.groupFonts) {
            val ratio = (allFonts.size - groups.size) / allFonts.size.toFloat()
            println("Created ${groups.size} font groups from ${allFonts.size} " +
                    "fonts (-${PERCENT_FMT.format(ratio)})")
        }

        // Assign unique name to each group
        assignUniqueFontNames(groups)

        return groups
    }

    /**
     * Create the TTF font files for a list of font groups.
     */
    fun createFontFiles(groups: List<FontGroup>) {
        print("Creating fonts: building TTF fonts\r")
        val tempDir = File(fontsDir, "temp")
        tempDir.mkdirs()
        for (group in groups) {
            buildFontFile(group, tempDir)
        }
        tempDir.deleteRecursively()
        println()
    }

    /**
     * Take a list of font groups and ungroup them to the original fonts,
     * so they have the original glyph indices but they share the same TTF font.
     */
    fun ungroupFonts(groups: List<FontGroup>): Map<FontId, Font> {
        val fonts = mutableMapOf<FontId, Font>()
        for (group in groups) {
            for (font in group.fonts) {
                font.name = group.name
                font.fontFile = group.fontFile
                fonts[font.id] = font
            }
        }
        return fonts
    }

    private fun createAllFonts(swfs: List<Movie>): List<Font> {
        val fonts = mutableListOf<Font>()

        unknownCharsMap.clear()
        nextUnknownCharCode = FIRST_CODE_FOR_UNKNOWN

        for ((i, swf) in swfs.withIndex()) {
            fonts += createSwfFonts(swf, i)
            print("Creating fonts: parsing all fonts, file ${i + 1} / ${swfs.size}\r")
        }
        println()
        return fonts
    }

    private fun createSwfFonts(swf: Movie, fileIndex: Int): List<Font> {
        val fonts = mutableListOf<Font>()
        for (obj in swf.objects) {
            val wfont = when (obj) {
                is DefineFont -> conversionError("Unsupported define font tag")
                is DefineFont2 -> WDefineFont(obj)
                is DefineFont3 -> WDefineFont(obj)
                is DefineFont4 -> conversionError("Unsupported define font tag")
                else -> null
            }
            if (wfont != null) {
                conversionError(wfont.kernings.isEmpty()) { "Unsupported font kerning" }

                // Create glyphs
                val codes = mutableSetOf<Char>()
                val glyphs = mutableListOf<FontGlyph>()
                val glyphConverter = GlyphPathParser(wfont)
                for ((i, code) in wfont.codes.withIndex()) {
                    val data = glyphConverter.createGlyphData(i)
                    val glyph = createFontGlyph(data, code, codes)
                    glyphs += glyph
                    codes += glyph.char
                }
                glyphConverter.dispose()

                // Create font
                val scale = wfont.scale
                val fontId = FontId(fileIndex, wfont.identifier)
                val metrics = FontMetrics(wfont.ascent * scale.scaleX,
                        wfont.descent * scale.scaleX, scale)
                fonts += Font(fontId, wfont.name, metrics, glyphs)
            }
        }
        return fonts
    }

    private fun createFontGlyph(data: GlyphData, code: Int,
                                assignedCodes: Set<Char>): FontGlyph {
        var char = code.toChar()
        when {
            data.isWhitespace -> {
                // Most whitespaces are discarded when TTF is built, and the others are converted to
                // spaces by the text renderer, so use only spaces. Advance width used here is the
                // default used for a space, but that doesn't have much importance since the text
                // renderer will set it manually anyway.
                return FontGlyph(' ', GlyphData(GlyphData.WHITESPACE_ADVANCE_WIDTH, emptyList()))
            }
            char in assignedCodes ||
                    char.isWhitespace() ||
                    char in '\u0000'..'\u001F' ||
                    char in '\uFFF0'..'\uFFFF' -> {
                // This will happen if:
                // - Duplicate code in font, meaning code was already assigned.
                // - Glyph should be a whitespace but it isn't. Ligatures and other unicode characters
                //     are sometimes replaced by an extended ASCII equivalent, often a space.
                // - Control chars: they don't get added to TTF fonts by doubletype.
                // - Specials unicode block chars: they don't seem to work well with PDFBox.
                // So in all these cases, a new code is used.

                val assigned = unknownCharsMap[data]
                if (assigned != null) {
                    // Glyph data is already assigned to a code in other fonts, try it.
                    if (assigned !in assignedCodes) {
                        char = assigned
                    } else {
                        // The existingly used code for this data can be used for this font,
                        // because it's already assigned. Use a new code.
                        char = nextUnknownCharCode.toChar()
                        nextUnknownCharCode++
                    }

                } else {
                    val ocrChar = if (config.ocrDetectGlyphs) {
                        // Try to recognize char with OCR
                        glyphOcr.recognizeGlyphData(data)
                    } else {
                        null
                    }
                    if (ocrChar == null || ocrChar in assignedCodes) {
                        // Char couldn't be recognized or recognized char is already assigned.
                        // Use a new code.
                        char = nextUnknownCharCode.toChar()
                        nextUnknownCharCode++
                    } else {
                        char = ocrChar
                    }
                    // Remember the char assigned for this data so the work doesn't have to be
                    // done for another identical char. This also increases mergeability.
                    unknownCharsMap[data] = char
                }
            }
        }

        return FontGlyph(char, data)
    }


    private fun mergeFonts(allFonts: List<Font>): List<FontGroup> {
        val fontsByName = allFonts.groupBy { it.name }
        val allGroups = mutableListOf<FontGroup>()
        for (fonts in fontsByName.values) {
            // Create font groups
            val groups = fonts.map { font ->
                FontGroup(font.name, font.metrics, mutableListOf(font),
                        font.glyphs.associateByTo(mutableMapOf()) { it.char })
            }

            // Merge groups
            allGroups += mergeFontGroups(groups, true)
        }
        // Merge again in case two fonts with different names are the same.
        // Also since this is the last merge, merge even if fonts have no common chars.
        return mergeFontGroups(allGroups, false)
    }

    private fun mergeFontGroups(groups: List<FontGroup>,
                                requireCommon: Boolean): List<FontGroup> {
        if (!config.groupFonts) {
            return groups
        }

        val newGroups = mutableListOf<FontGroup>()
        for (group in groups) {
            var wasMerged = false
            for (newGroup in newGroups) {
                if (group.isCompatibleWith(newGroup, requireCommon)) {
                    // Both fonts are compatible, merge them.
                    newGroup.merge(group)
                    wasMerged = true
                    break
                }
            }
            if (!wasMerged) {
                // Both fonts aren't compatible, add new global font.
                newGroups += group
            }
        }
        return if (groups.size != newGroups.size) {
            // Number of fonts decreased, continue merging. This is necessary since two fonts may 
            // not be mergeable at first if they don't have common characters.
            mergeFontGroups(newGroups, requireCommon)
        } else {
            newGroups
        }
    }

    private fun assignUniqueFontNames(groups: List<FontGroup>) {
        val assignedNames = mutableSetOf<String>()
        for (group in groups) {
            var name = group.name.replace(' ', '-').toLowerCase()
            if (name.isEmpty()) {
                name = UUID.randomUUID().toString()
            }
            if (name in assignedNames) {
                var i = 2
                while ("$name-$i" in assignedNames) {
                    i++
                }
                name = "$name-$i"
            }
            group.name = name
            assignedNames += name
        }
    }

    private fun buildFontFile(group: FontGroup, tempDir: File) {
        val builder = group.builder()
        val ttfFile = builder.build(tempDir)
        val outFile = File(fontsDir, validateFilename("${group.name}.ttf"))
        ttfFile.renameTo(outFile)
        group.fontFile = outFile
    }

    /**
     * Represents a group of font objects merged into a single group,
     * that share the same info and the same glyphs.
     */
    data class FontGroup(var name: String,
                         val metrics: FontMetrics,
                         val fonts: MutableList<Font>,
                         val glyphs: MutableMap<Char, FontGlyph>) {

        lateinit var fontFile: File

        private val isAllWhitespaces: Boolean
            get() = glyphs.values.all { it.isWhitespace }

        fun isCompatibleWith(other: FontGroup, requireCommon: Boolean): Boolean {
            if (other.glyphs.size < glyphs.size) {
                // Use group with the least glyph as comparison base.
                return other.isCompatibleWith(this, requireCommon)
            }
            return when {
                isAllWhitespaces || other.isAllWhitespaces -> {
                    // One font or the other has only whitespace. Since all whitespace is converted
                    // to identical spaces, fonts are automatically compatible.
                    true
                }
                metrics == other.metrics -> {
                    // Both font have same metrics, check glyphs.
                    var hasCommonChar = false
                    for ((char, glyph) in glyphs) {
                        val otherGlyph = other.glyphs[char]
                        if (otherGlyph != null) {
                            if (glyph != otherGlyph) {
                                // Two glyphs with same character but different shape, so these two fonts are different.
                                return false
                            } else if (!glyph.isWhitespace) {
                                hasCommonChar = true
                            }
                        }
                    }
                    // If no character is common between the two fonts, we can't say if they
                    // are compatible, so it's better to assume they're not for the moment, to
                    // make further merging more efficient.
                    hasCommonChar || !requireCommon
                }
                else -> false
            }
        }

        fun merge(font: FontGroup) {
            glyphs += font.glyphs
            fonts += font.fonts
        }

        fun builder() = FontBuilder(validateFilename(name)).also {
            it.glyphs += glyphs
            it.ascent = metrics.ascent
            it.descent = metrics.descent
        }

        override fun toString() = "FontGroup{name=$name, metrics=$metrics, " +
                "${fonts.size} fonts, ${glyphs.size} glyphs}"

    }

    companion object {
        private const val FIRST_CODE_FOR_UNKNOWN = 0xE000

        private val PERCENT_FMT = NumberFormat.getPercentInstance().apply {
            maximumFractionDigits = 2
        }
    }

}
