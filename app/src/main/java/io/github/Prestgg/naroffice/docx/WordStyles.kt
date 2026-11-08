// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.docx

import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.text.TextProps
import io.github.Prestgg.naroffice.xml.XElement

class ParaProps(
    val align: Align = Align.START,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    /** Line spacing multiplier (1.0 = single). */
    val lineSpacing: Float = 1f,
    val indentLeftPt: Float = 0f,
    val indentFirstPt: Float = 0f,
    val numId: String? = null,
    val numLevel: Int = 0,
    val shading: Int? = null,
    val headingLevel: Int = 0,
    val pageBreakBefore: Boolean = false,
    val borderBottom: Boolean = false,
)

private class Style(
    val id: String,
    val type: String,
    val name: String,
    val basedOn: String?,
    val pPr: XElement?,
    val rPr: XElement?,
)

/** Resolves paragraph and run formatting through styles.xml (docDefaults → styles → direct). */
class WordStyles(stylesRoot: XElement?, private val theme: Colors.Theme?) {

    private val styles = HashMap<String, Style>()
    private val defaultRPr: XElement?
    private val defaultPPr: XElement?
    var defaultParagraphStyle: String? = null
        private set

    init {
        defaultRPr = stylesRoot?.path("w:docDefaults", "w:rPrDefault", "w:rPr")
        defaultPPr = stylesRoot?.path("w:docDefaults", "w:pPrDefault", "w:pPr")
        stylesRoot?.children("w:style")?.forEach { s ->
            val id = s["w:styleId"] ?: return@forEach
            val st = Style(
                id,
                s["w:type"] ?: "paragraph",
                s.child("w:name")?.get("w:val") ?: id,
                s.child("w:basedOn")?.get("w:val"),
                s.child("w:pPr"),
                s.child("w:rPr"),
            )
            styles[id] = st
            if (s["w:default"] == "1" && st.type == "paragraph") defaultParagraphStyle = id
        }
    }

    fun styleName(id: String): String = styles[id]?.name ?: id

    fun hasStyle(id: String) = styles.containsKey(id)

    /** Paragraph style id for the given heading level (1–9), if the document defines it. */
    fun headingStyleId(level: Int): String? {
        styles.values.firstOrNull { it.type == "paragraph" && it.name.equals("heading $level", true) }
            ?.let { return it.id }
        return "Heading$level".takeIf { styles.containsKey(it) }
    }

    fun titleStyleId(): String? =
        styles.values.firstOrNull { it.type == "paragraph" && it.name.equals("title", true) }?.id

    private fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id?.let { styles[it] }
        var guard = 0
        while (cur != null && guard++ < 20) {
            out.add(0, cur)
            cur = cur.basedOn?.let { styles[it] }
        }
        return out
    }

    fun headingLevel(styleId: String?): Int {
        for (s in chain(styleId).asReversed()) {
            val n = s.name.lowercase()
            if (n == "title") return 1
            if (n.startsWith("heading ")) return n.removePrefix("heading ").toIntOrNull() ?: 0
            s.pPr?.child("w:outlineLvl")?.get("w:val")?.toIntOrNull()?.let { return it + 1 }
        }
        return 0
    }

    fun paraStyleId(pPr: XElement?): String? = pPr?.child("w:pStyle")?.get("w:val") ?: defaultParagraphStyle

    fun resolvePara(pPr: XElement?): ParaProps {
        val styleId = paraStyleId(pPr)
        val layers = ArrayList<XElement>()
        defaultPPr?.let { layers.add(it) }
        chain(styleId).forEach { s -> s.pPr?.let { layers.add(it) } }
        pPr?.let { layers.add(it) }

        var align = Align.START
        var before = 0f
        var after = 0f
        var line = 1f
        var left = 0f
        var first = 0f
        var numId: String? = null
        var ilvl = 0
        var shading: Int? = null
        var pageBreak = false
        var border = false
        for (l in layers) {
            l.child("w:jc")?.get("w:val")?.let {
                align = when (it) {
                    "center" -> Align.CENTER
                    "right", "end" -> Align.END
                    "both", "distribute" -> Align.JUSTIFY
                    else -> Align.START
                }
            }
            l.child("w:spacing")?.let { sp ->
                sp["w:before"]?.toFloatOrNull()?.let { before = it / 20f }
                sp["w:after"]?.toFloatOrNull()?.let { after = it / 20f }
                if (sp["w:beforeAutospacing"] == "1") before = 5f
                if (sp["w:afterAutospacing"] == "1") after = 5f
                sp["w:line"]?.toFloatOrNull()?.let { v ->
                    val rule = sp["w:lineRule"] ?: "auto"
                    line = if (rule == "auto") v / 240f else (v / 20f) / 13.8f
                }
            }
            l.child("w:ind")?.let { ind ->
                (ind["w:left"] ?: ind["w:start"])?.toFloatOrNull()?.let { left = it / 20f }
                ind["w:firstLine"]?.toFloatOrNull()?.let { first = it / 20f }
                ind["w:hanging"]?.toFloatOrNull()?.let { first = -it / 20f }
            }
            l.child("w:numPr")?.let { np ->
                np.child("w:numId")?.get("w:val")?.let { numId = it }
                np.child("w:ilvl")?.get("w:val")?.toIntOrNull()?.let { ilvl = it }
            }
            l.child("w:shd")?.get("w:fill")?.let { shading = Colors.hex(it) }
            l.child("w:pageBreakBefore")?.let { pageBreak = it["w:val"] != "0" && it["w:val"] != "false" }
            if (l.path("w:pBdr", "w:bottom") != null) border = true
        }
        if (numId == "0") numId = null
        return ParaProps(
            align, before, after, line.coerceIn(0.7f, 3f), left, first, numId, ilvl, shading,
            headingLevel(styleId), pageBreak, border,
        )
    }

    /** Run formatting: defaults → paragraph style → character style → direct rPr. */
    fun resolveRun(paraStyleId: String?, rPr: XElement?): TextProps {
        val layers = ArrayList<XElement>()
        defaultRPr?.let { layers.add(it) }
        chain(paraStyleId).forEach { s -> s.rPr?.let { layers.add(it) } }
        val rStyle = rPr?.child("w:rStyle")?.get("w:val")
        chain(rStyle).forEach { s -> s.rPr?.let { layers.add(it) } }
        rPr?.let { layers.add(it) }
        var p = TextProps()
        var sizeSet = false
        for (l in layers) {
            for (c in l.elements) {
                val v = c["w:val"]
                when (c.name) {
                    "w:b" -> p = p.copy(bold = onOff(v))
                    "w:i" -> p = p.copy(italic = onOff(v))
                    "w:strike", "w:dstrike" -> p = p.copy(strike = onOff(v))
                    "w:u" -> p = p.copy(underline = v != null && v != "none")
                    "w:caps", "w:smallCaps" -> p = p.copy(caps = onOff(v))
                    "w:sz" -> v?.toFloatOrNull()?.let { p = p.copy(sizePt = it / 2f); sizeSet = true }
                    "w:color" -> p = p.copy(
                        color = c["w:themeColor"]?.let { theme?.color(it) } ?: v?.let { Colors.hex(it) },
                    )
                    "w:highlight" -> p = p.copy(highlight = v?.let { Colors.highlight(it) })
                    "w:shd" -> c["w:fill"]?.let { f -> Colors.hex(f)?.let { p = p.copy(highlight = it) } }
                    "w:vertAlign" -> p = p.copy(
                        baseline = when (v) {
                            "superscript" -> Baseline.SUPER
                            "subscript" -> Baseline.SUB
                            else -> Baseline.NORMAL
                        },
                    )
                    "w:rFonts" -> {
                        val f = c["w:ascii"] ?: c["w:hAnsi"] ?: c["w:cs"]
                        ?: c["w:asciiTheme"]?.let { theme?.font(it) }
                        if (f != null) p = p.copy(font = f)
                    }
                }
            }
        }
        if (!sizeSet) p = p.copy(sizePt = 10f)
        return p
    }

    private fun onOff(v: String?) = v == null || (v != "0" && v != "false" && v != "off")
}
