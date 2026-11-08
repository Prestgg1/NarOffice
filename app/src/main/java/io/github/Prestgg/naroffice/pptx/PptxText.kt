// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.pptx

import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.text.ATOM
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.RichPara
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XText

/** DrawingML run properties (a:rPr), compared by content so equal runs merge. */
class ARunFmt(val rPr: XElement?) {
    private val key: String by lazy { rPr?.toString() ?: "" }
    override fun equals(other: Any?) = other is ARunFmt && other.key == key
    override fun hashCode() = key.hashCode()

    private fun copy(): XElement = rPr?.deepCopy()?.also { it.name = "a:rPr" } ?: XElement("a:rPr")

    /** Sets or removes ([value] = null) an attribute. */
    fun attr(name: String, value: String?): ARunFmt {
        val r = copy()
        r[name] = value
        return ARunFmt(r)
    }

    fun bold(on: Boolean) = attr("b", if (on) "1" else "0")
    fun italic(on: Boolean) = attr("i", if (on) "1" else "0")
    fun underline(on: Boolean) = attr("u", if (on) "sng" else "none")
    fun strike(on: Boolean) = attr("strike", if (on) "sngStrike" else "noStrike")

    /** Font size in points, or null to inherit. */
    fun size(pt: Float?) = attr("sz", pt?.let { (it * 100).toInt().coerceIn(100, 400000).toString() })

    /** Text colour (ARGB), or null to inherit. */
    fun color(argb: Int?): ARunFmt {
        val r = copy()
        r.children.removeAll { it is XElement && it.localName in FILLS }
        if (argb != null) {
            val fill = XElement("a:solidFill")
            fill.children.add(XElement("a:srgbClr").also { it["val"] = Colors.toHex(argb) })
            val lnIdx = r.children.indexOfFirst { it is XElement && it.localName == "ln" }
            r.children.add(lnIdx + 1, fill)
        }
        return ARunFmt(r)
    }

    /** Latin font face, or null to inherit. */
    fun font(face: String?): ARunFmt {
        val r = copy()
        r.removeChild("a:latin")
        if (face != null) {
            val e = XElement("a:latin").also { it["typeface"] = face }
            // latin follows ln, fills, effects, highlight and underline styles.
            val before = setOf("ea", "cs", "sym", "hlinkClick", "hlinkMouseOver", "rtl", "extLst")
            val idx = r.children.indexOfFirst { it is XElement && it.localName in before }
            if (idx < 0) r.children.add(e) else r.children.add(idx, e)
        }
        return ARunFmt(r)
    }

    companion object {
        val FILLS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")
    }
}

/** A text field (slide number, date) kept as-is. */
class PAtom(val el: XElement) {
    val type: String? get() = el["type"]
    val display: String get() = el.child("a:t")?.textContent ?: ""
}

/** One editable paragraph of a text body: rich text plus its paragraph properties. */
class TextPara(
    val rich: RichPara<ARunFmt>,
    val pPr: XElement?,
    val endRPr: XElement?,
) {
    val text: String get() = rich.text
    val level: Int get() = pPr?.get("lvl")?.toIntOrNull() ?: 0

    /** Alignment set directly on this paragraph, if any. */
    val align: Align?
        get() = pPr?.get("algn")?.let(::parseAlign)

    fun withRich(r: RichPara<ARunFmt>) = TextPara(r, pPr, endRPr)

    private fun withPPrAttr(name: String, value: String?): TextPara {
        val p = pPr?.deepCopy() ?: XElement("a:pPr")
        p[name] = value
        return TextPara(rich, p.takeIf { it.attrs.isNotEmpty() || it.children.isNotEmpty() }, endRPr)
    }

    fun withAlign(a: Align?) = withPPrAttr("algn", a?.let {
        when (it) {
            Align.START -> "l"
            Align.CENTER -> "ctr"
            Align.END -> "r"
            Align.JUSTIFY -> "just"
        }
    })

    fun withLevel(l: Int) = withPPrAttr("lvl", l.coerceIn(0, 8).takeIf { it > 0 }?.toString())

    /** Splits at [pos]; both halves keep the paragraph properties. */
    fun split(pos: Int): Pair<TextPara, TextPara> {
        val (a, b) = rich.cut(pos)
        return TextPara(a, pPr?.deepCopy(), endRPr) to TextPara(b, pPr?.deepCopy(), endRPr)
    }

    fun merge(next: TextPara) = TextPara(rich.append(next.rich), pPr, next.endRPr ?: endRPr)

    companion object {
        fun parseAlign(v: String): Align = when (v) {
            "ctr" -> Align.CENTER
            "r" -> Align.END
            "just", "dist", "justLow", "thaiDist" -> Align.JUSTIFY
            else -> Align.START
        }

        /** A new empty paragraph, formatted like [fmt]. */
        fun empty(fmt: ARunFmt = ARunFmt(null)) = TextPara(RichPara(listOf(Span("", fmt))), null, null)
    }
}

object PptxText {

    fun read(p: XElement): TextPara {
        val spans = ArrayList<Span<ARunFmt>>()
        val endRPr = p.child("a:endParaRPr")
        for (c in p.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "r" -> {
                    val t = c.child("a:t")?.textContent ?: ""
                    if (t.isNotEmpty()) spans.add(Span(t, ARunFmt(c.child("a:rPr"))))
                }
                "br" -> spans.add(Span(SOFT_BREAK.toString(), ARunFmt(c.child("a:rPr"))))
                "fld" -> spans.add(Span(ATOM.toString(), ARunFmt(c.child("a:rPr")), PAtom(c)))
                else -> {}
            }
        }
        if (spans.isEmpty()) {
            val f = endRPr?.deepCopy()?.also { it.name = "a:rPr" }
            spans.add(Span("", ARunFmt(f)))
        }
        // Anything we do not model (math, extLst) is kept on the paragraph properties side:
        // a:p only allows pPr, r, br, fld and endParaRPr, plus rare extensions we drop.
        return TextPara(RichPara(spans), p.child("a:pPr"), endRPr)
    }

    fun write(tp: TextPara): XElement {
        val p = XElement("a:p")
        tp.pPr?.let { p.children.add(it.deepCopy().also { e -> e.name = "a:pPr" }) }
        for (s in tp.rich.spans) {
            val atom = s.atom as PAtom?
            if (atom != null) {
                p.children.add(atom.el)
                continue
            }
            if (s.text.isEmpty()) continue
            val sb = StringBuilder()
            fun flush() {
                if (sb.isEmpty()) return
                val r = XElement("a:r")
                r.children.add(rPrFor(s.fmt))
                r.children.add(XElement("a:t").also { it.children.add(XText(sb.toString())) })
                p.children.add(r)
                sb.setLength(0)
            }
            for (ch in s.text) {
                if (ch == SOFT_BREAK || ch == '\n') {
                    flush()
                    p.children.add(XElement("a:br").also { it.children.add(rPrFor(s.fmt)) })
                } else sb.append(ch)
            }
            flush()
        }
        val end = when {
            tp.rich.length == 0 -> tp.rich.spans.firstOrNull()?.fmt?.rPr?.deepCopy()
                ?.also { it.name = "a:endParaRPr" } ?: tp.endRPr?.deepCopy()
            else -> tp.endRPr?.deepCopy()
        }
        if (end != null) p.children.add(end)
        return p
    }

    private fun rPrFor(f: ARunFmt): XElement =
        f.rPr?.deepCopy()?.also { it.name = "a:rPr" } ?: XElement("a:rPr").also { it["lang"] = "az-Latn-AZ" }
}
