// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.docx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.Prestgg.naroffice.docx.DocBlock
import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.docx.RunFmt
import io.github.Prestgg.naroffice.docx.WPara
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.text.TextProps
import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.xml.XElement
import java.util.IdentityHashMap

/** The paragraph currently being edited. */
class ActiveEdit(
    val key: Long,
    var container: XElement,
    var p: XElement,
    var rich: WPara,
    var value: TextFieldValue,
    /** Format for the next typed text when the selection is collapsed. */
    var pending: RunFmt? = null,
    var dirty: Boolean = false,
)

class DocxController(val doc: DocxDocument) {

    /** Incremented whenever the rendered document must refresh. */
    var tick by mutableIntStateOf(0)
        private set

    var active by mutableStateOf<ActiveEdit?>(null)
        private set

    /** Bumped when the active edit's text or selection changes. */
    var activeTick by mutableIntStateOf(0)
        private set

    private val keys = IdentityHashMap<XElement, Long>()
    private var nextKey = 1L

    fun keyOf(el: XElement): Long = keys.getOrPut(el) { nextKey++ }

    private fun refresh() {
        tick++
    }

    // ------------------------------------------------------------ activation

    fun activate(container: XElement, p: XElement, offset: Int) {
        if (active?.p === p) {
            setSelection(offset, offset); return
        }
        commit()
        val rich = DocxPara.read(p)
        val pos = offset.coerceIn(0, rich.length) + 1
        active = ActiveEdit(keyOf(p), container, p, rich, TextFieldValue(SENTINEL + rich.text, TextRange(pos)))
        activeTick++
    }

    fun deactivate() {
        commit()
        active = null
        activeTick++
    }

    private fun setSelection(start: Int, end: Int) {
        val a = active ?: return
        a.value = a.value.copy(selection = TextRange(start + 1, end + 1))
        activeTick++
    }

    /** Writes the active paragraph into the document if it changed. */
    fun commit() {
        val a = active ?: return
        if (!a.dirty) return
        val idx = a.container.children.indexOfFirst { it === a.p }
        if (idx < 0) {
            a.dirty = false; return
        }
        val np = DocxPara.write(a.rich, a.p, a.p.child("w:pPr"))
        doc.replace(a.container, listOf(a.p), listOf(np))
        keys[np] = a.key
        a.p = np
        a.dirty = false
        refresh()
    }

    /** Selection in paragraph coordinates (without the sentinel). */
    fun selection(): IntRange? {
        val a = active ?: return null
        val s = (a.value.selection.min - 1).coerceAtLeast(0)
        val e = (a.value.selection.max - 1).coerceAtLeast(0)
        return s..e
    }

    // ------------------------------------------------------------ typing

    fun onValueChange(from: ActiveEdit, v: TextFieldValue) {
        val a = active ?: return
        // A field that is no longer the active one may still report its old text.
        if (a !== from) return
        val old = a.value.text
        val new = v.text
        if (!new.startsWith(SENTINEL)) {
            if (new == old.drop(1)) {
                // Backspace at the very start: join with the previous paragraph.
                if (mergeWithPrevious()) return
            }
            // Put the sentinel back.
            val fixed = SENTINEL + new.removePrefix(SENTINEL.toString())
            applyText(a, old, fixed, v.copy(text = fixed, selection = shift(v.selection, 1)))
            return
        }
        if (old == new) {
            val sel = if (v.selection.min < 1) TextRange(maxOf(1, v.selection.start), maxOf(1, v.selection.end)) else v.selection
            if (sel != a.value.selection) a.pending = null
            a.value = v.copy(selection = sel)
            activeTick++
            return
        }
        applyText(a, old, new, v)
    }

    private fun shift(r: TextRange, d: Int) = TextRange(r.start + d, r.end + d)

    private fun applyText(a: ActiveEdit, old: String, new: String, v: TextFieldValue) {
        // Minimal diff between old and new field text.
        var pre = 0
        val maxPre = minOf(old.length, new.length)
        while (pre < maxPre && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < new.length - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        val delStart = pre - 1
        val delEnd = old.length - suf - 1
        val ins = new.substring(pre, new.length - suf)
        val like = a.pending?.let { Span("", it) }
        var rich = a.rich.replace(delStart.coerceAtLeast(0), delEnd.coerceAtLeast(0), ins, like = like)
        if (rich.length + 1 != new.length) {
            // Should not happen; fall back to rebuilding the text in one span.
            rich = a.rich.replace(0, a.rich.length, new.drop(1))
        }
        a.rich = rich
        a.dirty = true
        if (ins.isNotEmpty() && a.pending != null && '\n' !in ins) {
            // Keep the pending format for the rest of this typing burst only.
        } else if (ins.isEmpty()) {
            a.pending = null
        }
        a.value = v
        activeTick++
        if ('\n' in ins) splitAtNewlines()
    }

    private fun splitAtNewlines() {
        val a = active ?: return
        val text = a.rich.text
        if ('\n' !in text) return
        val pPr = a.p.child("w:pPr")
        // Enter on an empty list item ends the list instead of adding an item.
        if (text == "\n" && doc.listKind(a.p) != null) {
            a.rich = a.rich.replace(0, 1, "")
            a.value = TextFieldValue(SENTINEL.toString(), TextRange(1))
            a.dirty = true
            commit()
            doc.listKind(a.p)?.let { toggleList(it) }
            return
        }
        val parts = ArrayList<WPara>()
        var rest = a.rich
        while (true) {
            val i = rest.text.indexOf('\n')
            if (i < 0) break
            val (left, right) = rest.cut(i)
            parts.add(left)
            rest = right.replace(0, 1, "")
        }
        parts.add(rest)
        val firstPPr = pPr?.deepCopy()?.also { it.removeChild("w:sectPr") }
        val nextPPr = pPr?.deepCopy()?.also { np ->
            np.removeChild("w:pageBreakBefore")
            val sid = np.child("w:pStyle")?.get("w:val")
            if (sid != null && doc.styles.headingLevel(sid) > 0) np.removeChild("w:pStyle")
        }
        val lastPPr = nextPPr?.deepCopy()?.also { lp ->
            if (pPr.child("w:sectPr") == null) lp.removeChild("w:sectPr")
        }
        val withPPr = parts.mapIndexed { i, rp ->
            rp to when (i) {
                0 -> firstPPr
                parts.size - 1 -> lastPPr
                else -> nextPPr?.deepCopy()?.also { it.removeChild("w:sectPr") }
            }
        }
        val idx = a.container.children.indexOfFirst { it === a.p }
        if (idx < 0) return
        doc.commitParagraphs(a.container, a.p, withPPr)
        val newEls = a.container.children.subList(idx, idx + parts.size).map { it as XElement }
        keys[newEls.first()] = a.key
        val last = newEls.last()
        val cursor = 0
        a.dirty = false
        active = null
        refresh()
        activate(a.container, last, cursor)
    }

    private fun mergeWithPrevious(): Boolean {
        val a = active ?: return false
        commit()
        val idx = a.container.children.indexOfFirst { it === a.p }
        var prevIdx = idx - 1
        while (prevIdx >= 0) {
            val c = a.container.children[prevIdx]
            if (c is XElement && c.localName in setOf("p", "tbl", "sdt")) break
            prevIdx--
        }
        if (prevIdx < 0) return false
        val prev = a.container.children[prevIdx] as XElement
        if (prev.localName != "p") return false
        val prevRich = DocxPara.read(prev)
        val merged = if (prevRich.length == 0) a.rich else prevRich.append(a.rich)
        val pPr = if (prevRich.length == 0) a.p.child("w:pPr") else prev.child("w:pPr")
        val np = DocxPara.write(merged, prev, pPr)
        val between = a.container.children.subList(prevIdx + 1, idx).toList()
        doc.splice(a.container, prevIdx, idx - prevIdx + 1, listOf(np) + between)
        keys[np] = keyOf(prev)
        active = null
        refresh()
        activate(a.container, np, prevRich.length)
        return true
    }

    // ------------------------------------------------------------ formatting

    fun currentProps(): TextProps? {
        val a = active ?: return null
        val sel = selection() ?: return null
        val pStyle = doc.styles.paraStyleId(a.p.child("w:pPr"))
        val spans = if (sel.first == sel.last) listOfNotNull(a.pending?.let { Span("", it) } ?: a.rich.fmtAt(sel.first))
        else a.rich.spansIn(sel.first, sel.last)
        val props = spans.filter { it.atom == null }.map { doc.styles.resolveRun(pStyle, it.fmt.rPr) }
        if (props.isEmpty()) return null
        return TextProps(
            bold = props.all { it.bold },
            italic = props.all { it.italic },
            underline = props.all { it.underline },
            strike = props.all { it.strike },
            sizePt = props.first().sizePt,
            color = props.first().color,
            highlight = props.first().highlight,
            baseline = props.first().baseline,
        )
    }

    /** Applies a run-format change to the selection, or to the next typed text. */
    fun format(change: (RunFmt) -> RunFmt) {
        val a = active ?: return
        val sel = selection() ?: return
        if (sel.first == sel.last) {
            val base = a.pending ?: a.rich.fmtAt(sel.first)?.fmt ?: RunFmt(null)
            a.pending = change(base)
            if (a.rich.length == 0) {
                // An empty paragraph: format its only (empty) span directly.
                a.rich = io.github.Prestgg.naroffice.text.RichPara(listOf(Span("", a.pending!!)), a.rich.markers)
                a.dirty = true
            }
        } else {
            a.rich = a.rich.format(sel.first, sel.last, change)
            a.dirty = true
            commit()
        }
        activeTick++
    }

    private fun pStyle(): String? = active?.let { doc.styles.paraStyleId(it.p.child("w:pPr")) }

    /** Sets or clears a toggle property, overriding the style when needed. */
    fun toggle(name: String, on: Boolean, read: (TextProps) -> Boolean) = format { f ->
        var r = f.with(name, null)
        if (read(doc.styles.resolveRun(pStyle(), r.rPr)) != on) r = f.with(name, if (on) emptyMap() else mapOf("w:val" to "0"))
        r
    }

    fun setBold(on: Boolean) = toggle("w:b", on) { it.bold }.also { format { f -> f.with("w:bCs", null) } }
    fun setItalic(on: Boolean) = toggle("w:i", on) { it.italic }
    fun setStrike(on: Boolean) = toggle("w:strike", on) { it.strike }
    fun setUnderline(on: Boolean) = format { f -> f.with("w:u", if (on) mapOf("w:val" to "single") else null) }

    fun setBaseline(b: Baseline) = format { f ->
        f.with("w:vertAlign", when (b) {
            Baseline.SUPER -> mapOf("w:val" to "superscript")
            Baseline.SUB -> mapOf("w:val" to "subscript")
            Baseline.NORMAL -> null
        })
    }

    fun setSize(pt: Float) = format { f ->
        val v = (pt * 2).toInt().toString()
        f.with("w:sz", mapOf("w:val" to v)).with("w:szCs", mapOf("w:val" to v))
    }

    fun setColor(argb: Int?) = format { f -> f.with("w:color", argb?.let { mapOf("w:val" to Colors.toHex(it)) }) }

    fun setHighlight(name: String?) = format { f -> f.with("w:highlight", name?.let { mapOf("w:val" to it) }) }

    // ------------------------------------------------------------ paragraph operations

    /** Runs a paragraph-level document change and keeps editing the new paragraph. */
    private fun paragraphOp(op: (XElement, XElement) -> Unit) {
        val a = active ?: return
        commit()
        val sel = selection() ?: 0..0
        val idx = a.container.children.indexOfFirst { it === a.p }
        if (idx < 0) return
        op(a.container, a.p)
        val np = a.container.children.getOrNull(idx) as? XElement ?: return
        keys[np] = a.key
        a.p = np
        a.rich = DocxPara.read(np)
        a.value = a.value.copy(selection = TextRange(sel.first + 1, sel.last + 1))
        refresh()
        activeTick++
    }

    fun setAlign(align: Align) = paragraphOp { c, p -> doc.setAlign(c, p, align) }
    fun setHeading(level: Int?) = paragraphOp { c, p -> doc.setHeading(c, p, level) }
    fun toggleList(bullet: Boolean) = paragraphOp { c, p -> doc.toggleList(c, p, bullet) }
    fun indent(delta: Int) = paragraphOp { c, p ->
        if (doc.listKind(p) != null) doc.changeListLevel(c, p, delta)
        else {
            val pp = doc.styles.resolvePara(p.child("w:pPr"))
            val left = ((pp.indentLeftPt + delta * 36f).coerceAtLeast(0f) * 20).toInt()
            val np = doc.withPPr(p) { pPr ->
                val ind = pPr.child("w:ind")?.deepCopy() ?: XElement("w:ind")
                ind.attrs.remove("w:start")
                ind["w:left"] = left.toString()
                DocxPara.insertPPr(pPr, ind)
            }
            doc.replace(c, listOf(p), listOf(np))
        }
    }

    fun currentAlign(): Align = active?.let { doc.styles.resolvePara(it.p.child("w:pPr")).align } ?: Align.START
    fun currentHeading(): Int? = active?.let { doc.headingOf(it.p) }
    fun currentList(): Boolean? = active?.let { doc.listKind(it.p) }

    fun insertPageBreak() {
        val a = active ?: return
        val sel = selection() ?: return
        commit()
        val br = io.github.Prestgg.naroffice.xml.Xml.parseElement("""<w:br w:type="page"/>""")
        val atom = io.github.Prestgg.naroffice.docx.DocAtom(br, true, io.github.Prestgg.naroffice.docx.AtomKind.PAGE_BREAK)
        val fmt = a.rich.fmtAt(sel.first)?.fmt ?: RunFmt(null)
        val (l, r) = a.rich.splitAt(sel.first).cut(sel.first)
        a.rich = l.append(io.github.Prestgg.naroffice.text.RichPara(listOf(Span(io.github.Prestgg.naroffice.text.ATOM.toString(), fmt, atom)))).append(r)
        a.value = TextFieldValue(SENTINEL + a.rich.text, TextRange(sel.first + 2))
        a.dirty = true
        commit()
        activeTick++
    }

    // ------------------------------------------------------------ blocks

    fun insertTable(rows: Int, cols: Int) {
        val a = active
        commit()
        doc.insertTable(a?.container ?: doc.body, a?.p ?: lastBodyBlock(), rows, cols)
        refresh()
    }

    fun insertImage(bytes: ByteArray, ext: String, w: Int, h: Int) {
        val a = active
        commit()
        doc.insertImage(a?.container ?: doc.body, a?.p ?: lastBodyBlock(), bytes, ext, w, h)
        refresh()
    }

    private fun lastBodyBlock(): XElement? = doc.blocksOf(doc.body).lastOrNull { it.container === doc.body }?.el

    fun deleteBlock(container: XElement, el: XElement) {
        if (active?.p === el) {
            active = null; activeTick++
        } else commit()
        doc.delete(container, el)
        refresh()
    }

    fun tableOp(op: () -> Unit) {
        commit()
        active = null
        activeTick++
        op()
        refresh()
    }

    // ------------------------------------------------------------ history

    fun undo() {
        commit()
        active = null
        activeTick++
        doc.undo()
        refresh()
    }

    fun redo() {
        commit()
        active = null
        activeTick++
        doc.redo()
        refresh()
    }

    fun replaceAll(q: String, r: String): Int {
        deactivate()
        val n = doc.replaceAll(q, r)
        refresh()
        return n
    }

    /** Index of the top-level block containing paragraph [p], for scrolling to search results. */
    fun blockIndexOf(blocks: List<DocBlock>, p: XElement): Int {
        for ((i, b) in blocks.withIndex()) {
            if (b.el === p) return i
            if (b is DocBlock.Table && b.el.descendants().any { it === p }) return i
        }
        return -1
    }
}
