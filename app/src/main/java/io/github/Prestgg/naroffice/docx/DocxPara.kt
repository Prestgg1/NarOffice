// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.docx

import io.github.Prestgg.naroffice.text.ATOM
import io.github.Prestgg.naroffice.text.Marker
import io.github.Prestgg.naroffice.text.RichPara
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XText

/** Run properties, compared by content so that runs with equal formatting merge. */
class RunFmt(val rPr: XElement?) {
    private val key: String by lazy { rPr?.toString() ?: "" }
    override fun equals(other: Any?) = other is RunFmt && other.key == key
    override fun hashCode() = key.hashCode()

    /** Returns a copy with [name] set (val = null removes it). */
    fun with(name: String, attrs: Map<String, String>?): RunFmt {
        val r = rPr?.deepCopy() ?: XElement("w:rPr")
        r.removeChild(name)
        if (attrs != null) {
            val e = XElement(name)
            attrs.forEach { (k, v) -> e[k] = v }
            insertOrdered(r, e)
        }
        return RunFmt(r.takeIf { it.children.isNotEmpty() })
    }

    fun has(name: String): XElement? = rPr?.child(name)

    companion object {
        /** Schema order of rPr children; Word is strict about it. */
        private val ORDER = listOf(
            "w:rStyle", "w:rFonts", "w:b", "w:bCs", "w:i", "w:iCs", "w:caps", "w:smallCaps", "w:strike",
            "w:dstrike", "w:outline", "w:shadow", "w:emboss", "w:imprint", "w:noProof", "w:snapToGrid",
            "w:vanish", "w:webHidden", "w:color", "w:spacing", "w:w", "w:kern", "w:position", "w:sz",
            "w:szCs", "w:highlight", "w:u", "w:effect", "w:bdr", "w:shd", "w:fitText", "w:vertAlign",
            "w:rtl", "w:cs", "w:em", "w:lang", "w:eastAsianLayout", "w:specVanish", "w:oMath",
        )

        fun insertOrdered(parent: XElement, e: XElement, order: List<String> = ORDER) {
            val rank = order.indexOf(e.name).let { if (it < 0) order.size else it }
            var idx = parent.children.size
            for ((i, c) in parent.children.withIndex()) {
                if (c is XElement) {
                    val r = order.indexOf(c.name).let { if (it < 0) order.size else it }
                    if (r > rank) {
                        idx = i; break
                    }
                }
            }
            parent.children.add(idx, e)
        }
    }
}

enum class AtomKind { IMAGE, HIDDEN, PAGE_BREAK, TEXTUAL }

/** A non-text piece of a paragraph, written back exactly as it was read. */
class DocAtom(val el: XElement, val inRun: Boolean, val kind: AtomKind) {
    /** Text shown for textual atoms (equations, content controls, symbols). */
    val display: String by lazy {
        when (el.localName) {
            "sym" -> el["w:char"]?.toIntOrNull(16)?.let { c ->
                val code = if (c in 0xF000..0xF0FF) c - 0xF000 else c
                code.toChar().toString()
            } ?: "?"
            "footnoteReference", "endnoteReference" -> "*"
            else -> el.textContent
        }
    }
}

typealias WPara = RichPara<RunFmt>

object DocxPara {

    private val MARKERS = setOf(
        "bookmarkStart", "bookmarkEnd", "commentRangeStart", "commentRangeEnd", "permStart", "permEnd",
        "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd", "del", "moveFrom",
        "customXmlInsRangeStart", "customXmlInsRangeEnd", "customXmlDelRangeStart", "customXmlDelRangeEnd",
    )
    private val WRAPPERS = setOf("hyperlink", "ins", "smartTag", "customXml", "fldSimple", "dir", "bdo", "moveTo")
    private val HIDDEN = setOf("fldChar", "instrText", "delInstrText", "commentReference", "annotationRef",
        "separator", "continuationSeparator", "footnoteRef", "endnoteRef", "lastRenderedPageBreak")

    /** Reads a w:p into an editable paragraph. */
    fun read(p: XElement): WPara {
        val spans = ArrayList<Span<RunFmt>>()
        val markers = ArrayList<Marker>()
        var len = 0
        fun addText(t: String, fmt: RunFmt, wrapper: List<XElement>?) {
            if (t.isEmpty()) return
            spans.add(Span(t, fmt, null, wrapper)); len += t.length
        }

        fun addAtom(atom: DocAtom, fmt: RunFmt, wrapper: List<XElement>?) {
            spans.add(Span(ATOM.toString(), fmt, atom, wrapper)); len += 1
        }

        fun walk(container: XElement, wrapper: List<XElement>?) {
            for (c in container.children) {
                if (c !is XElement) continue
                val ln = c.localName
                when {
                    c.name == "w:pPr" -> {}
                    c.name == "w:r" -> readRun(c, wrapper, ::addText, ::addAtom)
                    ln == "proofErr" -> {}
                    ln in MARKERS -> markers.add(Marker(len, c))
                    ln in WRAPPERS -> {
                        val shell = XElement(c.name, LinkedHashMap(c.attrs))
                        // fldSimple keeps its non-run children (w:fldData) in the shell.
                        c.children.filter { it is XElement && it.localName == "fldData" }.forEach { shell.children.add(it) }
                        walk(c, (wrapper ?: emptyList()) + shell)
                    }
                    ln == "oMath" || ln == "oMathPara" || ln == "sdt" ->
                        addAtom(DocAtom(c, false, AtomKind.TEXTUAL), RunFmt(null), wrapper)
                    else -> addAtom(DocAtom(c, false, AtomKind.HIDDEN), RunFmt(null), wrapper)
                }
            }
        }
        walk(p, null)
        if (spans.isEmpty()) {
            spans.add(Span("", RunFmt(p.path("w:pPr", "w:rPr")?.deepCopy()?.also { stripParaMarkProps(it) })))
        }
        return RichPara(spans, markers)
    }

    private fun stripParaMarkProps(rPr: XElement) {
        rPr.children.removeAll { it is XElement && it.localName in setOf("ins", "del", "moveFrom", "moveTo") }
    }

    private fun readRun(
        r: XElement,
        wrapper: List<XElement>?,
        addText: (String, RunFmt, List<XElement>?) -> Unit,
        addAtom: (DocAtom, RunFmt, List<XElement>?) -> Unit,
    ) {
        val fmt = RunFmt(r.child("w:rPr"))
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                addText(sb.toString(), fmt, wrapper); sb.setLength(0)
            }
        }
        for (c in r.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "rPr" -> {}
                "t" -> sb.append(c.textContent)
                "tab", "ptab" -> sb.append('\t')
                "br" -> if (c["w:type"] == "page" || c["w:type"] == "column") {
                    flush(); addAtom(DocAtom(c, true, AtomKind.PAGE_BREAK), fmt, wrapper)
                } else sb.append(SOFT_BREAK)
                "cr" -> sb.append(SOFT_BREAK)
                "noBreakHyphen" -> sb.append('‑')
                "softHyphen" -> sb.append('­')
                "lastRenderedPageBreak" -> {}
                "drawing", "pict", "object" -> {
                    flush()
                    val kind = if (imageRelId(c) != null) AtomKind.IMAGE else AtomKind.HIDDEN
                    addAtom(DocAtom(c, true, kind), fmt, wrapper)
                }
                "AlternateContent" -> {
                    flush()
                    val kind = if (imageRelId(c) != null) AtomKind.IMAGE else AtomKind.HIDDEN
                    addAtom(DocAtom(c, true, kind), fmt, wrapper)
                }
                "sym", "footnoteReference", "endnoteReference" -> {
                    flush(); addAtom(DocAtom(c, true, AtomKind.TEXTUAL), fmt, wrapper)
                }
                else -> {
                    flush()
                    addAtom(DocAtom(c, true, if (c.localName in HIDDEN) AtomKind.HIDDEN else AtomKind.HIDDEN), fmt, wrapper)
                }
            }
        }
        flush()
    }

    /** Relationship id of the first picture inside a drawing/pict element. */
    fun imageRelId(e: XElement): String? {
        for (d in e.descendants()) {
            if (d.localName == "blip") d.attrLocal("embed")?.let { return it }
            if (d.localName == "imagedata") (d["r:id"] ?: d.attrLocal("id"))?.let { return it }
        }
        return null
    }

    /** Displayed size of an image atom in points, if known. */
    fun imageSizePt(e: XElement): Pair<Float, Float>? {
        for (d in e.descendants()) {
            if (d.localName == "extent") {
                val cx = d["cx"]?.toLongOrNull() ?: continue
                val cy = d["cy"]?.toLongOrNull() ?: continue
                return cx / 12700f to cy / 12700f
            }
            if (d.localName == "shape" && d["style"] != null) {
                val st = d["style"]!!.split(';').associate {
                    it.substringBefore(':').trim() to it.substringAfter(':', "").trim()
                }
                val w = st["width"]?.let(::cssPt)
                val h = st["height"]?.let(::cssPt)
                if (w != null && h != null) return w to h
            }
        }
        return null
    }

    private fun cssPt(v: String): Float? {
        val num = v.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
        return when {
            v.endsWith("pt") -> num
            v.endsWith("in") -> num * 72
            v.endsWith("cm") -> num * 28.35f
            v.endsWith("mm") -> num * 2.835f
            v.endsWith("px") -> num * 0.75f
            else -> num
        }
    }

    /** Builds a new w:p from [para], keeping the attributes of [original] and the given pPr. */
    fun write(para: WPara, original: XElement, pPr: XElement?): XElement {
        val p = XElement(original.name, LinkedHashMap(original.attrs))
        if (pPr != null && pPr.children.isNotEmpty()) p.children.add(pPr.deepCopy())

        // Emit spans grouped by wrapper chain, interleaving markers at their offsets.
        val markers = para.markers.sortedBy { it.offset }
        var mi = 0
        var pos = 0
        // Stack of currently open wrapper elements (output) and their source shells.
        val openOut = ArrayList<XElement>()
        val openSrc = ArrayList<XElement>()
        fun target(): XElement = openOut.lastOrNull() ?: p

        fun emitMarkersUpTo(o: Int) {
            while (mi < markers.size && markers[mi].offset <= o) {
                target().children.add(markers[mi].node as XElement)
                mi++
            }
        }

        fun syncWrappers(chain: List<XElement>?) {
            val want = chain ?: emptyList()
            var common = 0
            while (common < openSrc.size && common < want.size && openSrc[common] === want[common]) common++
            while (openSrc.size > common) {
                openSrc.removeAt(openSrc.size - 1); openOut.removeAt(openOut.size - 1)
            }
            for (k in common until want.size) {
                val src = want[k]
                val out = src.deepCopy()
                target().children.add(out)
                openSrc.add(src); openOut.add(out)
            }
        }

        for (s in para.spans) {
            if (s.text.isEmpty() && s.atom == null) continue
            emitMarkersUpTo(pos)
            @Suppress("UNCHECKED_CAST")
            syncWrappers(s.wrapper as List<XElement>?)
            val atom = s.atom as DocAtom?
            if (atom != null) {
                if (atom.inRun) {
                    val r = XElement("w:r")
                    s.fmt.rPr?.let { r.children.add(it.deepCopy()) }
                    r.children.add(atom.el)
                    target().children.add(r)
                } else {
                    target().children.add(atom.el)
                }
            } else {
                target().children.add(textRun(s.text, s.fmt))
            }
            pos += s.text.length
        }
        syncWrappers(null)
        emitMarkersUpTo(Int.MAX_VALUE)

        // An empty paragraph remembers its formatting in the paragraph mark.
        if (para.length == 0) {
            val f = para.spans.firstOrNull()?.fmt?.rPr
            if (f != null && f.children.isNotEmpty()) {
                val ppr = p.child("w:pPr") ?: XElement("w:pPr").also { p.children.add(0, it) }
                ppr.removeChild("w:rPr")
                val mark = f.deepCopy()
                mark.name = "w:rPr"
                insertPPr(ppr, mark)
            }
        }
        return p
    }

    private fun textRun(text: String, fmt: RunFmt): XElement {
        val r = XElement("w:r")
        fmt.rPr?.let { r.children.add(it.deepCopy()) }
        val sb = StringBuilder()
        fun flush() {
            if (sb.isEmpty()) return
            val t = XElement("w:t")
            val s = sb.toString()
            if (s.first().isWhitespace() || s.last().isWhitespace()) t["xml:space"] = "preserve"
            t.children.add(XText(s))
            r.children.add(t)
            sb.setLength(0)
        }
        for (ch in text) {
            when (ch) {
                '\t' -> { flush(); r.children.add(XElement("w:tab")) }
                SOFT_BREAK, '\n' -> { flush(); r.children.add(XElement("w:br")) }
                '‑' -> { flush(); r.children.add(XElement("w:noBreakHyphen")) }
                '­' -> { flush(); r.children.add(XElement("w:softHyphen")) }
                else -> sb.append(ch)
            }
        }
        flush()
        return r
    }

    private val PPR_ORDER = listOf(
        "w:pStyle", "w:keepNext", "w:keepLines", "w:pageBreakBefore", "w:framePr", "w:widowControl",
        "w:numPr", "w:suppressLineNumbers", "w:pBdr", "w:shd", "w:tabs", "w:suppressAutoHyphens", "w:kinsoku",
        "w:wordWrap", "w:overflowPunct", "w:topLinePunct", "w:autoSpaceDE", "w:autoSpaceDN", "w:bidi",
        "w:adjustRightInd", "w:snapToGrid", "w:spacing", "w:ind", "w:contextualSpacing", "w:mirrorIndents",
        "w:suppressOverlap", "w:jc", "w:textDirection", "w:textAlignment", "w:textboxTightWrap",
        "w:outlineLvl", "w:divId", "w:cnfStyle", "w:rPr", "w:sectPr", "w:pPrChange",
    )

    fun insertPPr(pPr: XElement, e: XElement) {
        pPr.removeChild(e.name)
        RunFmt.insertOrdered(pPr, e, PPR_ORDER)
    }
}
