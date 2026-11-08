// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.pptx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.Prestgg.naroffice.pptx.ARunFmt
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.pptx.TextPara
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.RichPara
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.text.TextProps
import io.github.Prestgg.naroffice.xml.XElement

/**
 * Editing state for the text of one slide shape. All paragraphs live in one text field,
 * separated by '\n'; edits are applied to a combined [RichPara] by minimal diff and split back
 * into paragraphs, each keeping the paragraph properties it had.
 */
class ShapeTextEdit(val doc: PptxDocument, val slide: Int, val shapeId: String) {

    private class Meta(val pPr: XElement?, val endRPr: XElement?)

    private var meta: List<Meta>
    var combined: RichPara<ARunFmt>
        private set
    var value: TextFieldValue
        private set
    var pending: ARunFmt? = null
        private set
    var dirty = false
        private set

    /** Bumped on every change; observe it to recompose. */
    var tick by mutableIntStateOf(0)
        private set

    private val propsCache = HashMap<Pair<Int, ARunFmt>, TextProps>()

    init {
        val paras = doc.editableText(slide, shapeId)
        meta = paras.map { Meta(it.pPr, it.endRPr) }
        combined = combine(paras)
        value = TextFieldValue(combined.text, TextRange(combined.length))
    }

    private fun combine(paras: List<TextPara>): RichPara<ARunFmt> {
        var r = paras.first().rich
        for (p in paras.drop(1)) {
            val sepFmt = r.spans.lastOrNull { it.atom == null }?.fmt ?: ARunFmt(null)
            r = r.append(RichPara(listOf(Span("\n", sepFmt)))).append(p.rich)
        }
        return r
    }

    private fun splitParts(r: RichPara<ARunFmt>): List<RichPara<ARunFmt>> {
        val parts = ArrayList<RichPara<ARunFmt>>()
        var rest = r
        while (true) {
            val i = rest.text.indexOf('\n')
            if (i < 0) break
            val (left, right) = rest.cut(i)
            parts.add(left)
            rest = right.replace(0, 1, "")
        }
        parts.add(rest)
        return parts
    }

    /** Current paragraphs, ready for [PptxDocument.setText]. */
    fun paragraphs(): List<TextPara> {
        val parts = splitParts(combined)
        return parts.mapIndexed { i, rp ->
            val m = meta.getOrElse(i) { meta.last() }
            TextPara(rp, m.pPr, m.endRPr)
        }
    }

    fun paraIndexAt(offset: Int): Int = combined.text.take(offset.coerceIn(0, combined.length)).count { it == '\n' }

    fun levelAt(paraIndex: Int): Int = meta.getOrNull(paraIndex)?.pPr?.get("lvl")?.toIntOrNull() ?: 0

    fun props(level: Int, fmt: ARunFmt): TextProps = propsCache.getOrPut(level to fmt) {
        runCatching { doc.resolveRun(slide, shapeId, level, fmt) }.getOrElse { TextProps(sizePt = 18f) }
    }

    fun onValueChange(v: TextFieldValue) {
        val old = value.text
        val new = v.text
        if (old == new) {
            if (v.selection != value.selection) pending = null
            value = v
            tick++
            return
        }
        var pre = 0
        val maxPre = minOf(old.length, new.length)
        while (pre < maxPre && old[pre] == new[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < new.length - pre && old[old.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        val delStart = pre
        val delEnd = old.length - suf
        val ins = new.substring(pre, new.length - suf)

        val p0 = old.take(delStart).count { it == '\n' }
        val d = old.substring(delStart, delEnd).count { it == '\n' }
        val n = ins.count { it == '\n' }
        val like = pending?.let { Span("", it) }
        var r = combined.replace(delStart, delEnd, ins, like = like)
        if (r.length != new.length) r = combined.replace(0, combined.length, new)
        combined = r

        val newCount = meta.size - d + n
        val newMeta = ArrayList<Meta>(newCount)
        for (j in 0 until newCount) {
            newMeta.add(
                when {
                    j <= p0 -> meta.getOrElse(j) { meta.last() }
                    j <= p0 + n -> meta.getOrElse(p0) { meta.last() }.let { Meta(it.pPr?.deepCopy(), it.endRPr) }
                    else -> meta.getOrElse(j - n + d) { meta.last() }
                },
            )
        }
        meta = newMeta
        if (ins.isEmpty()) pending = null
        value = v
        dirty = true
        tick++
    }

    private fun sel(): IntRange = value.selection.min..value.selection.max

    fun currentProps(): TextProps? {
        val s = sel()
        val level = levelAt(paraIndexAt(s.first))
        val spans = if (s.first == s.last) listOfNotNull(pending?.let { Span("", it) } ?: combined.fmtAt(s.first))
        else combined.spansIn(s.first, s.last).filter { it.text != "\n" }
        val props = spans.filter { it.atom == null }.map { props(level, it.fmt) }
        if (props.isEmpty()) return null
        return props.first().copy(
            bold = props.all { it.bold },
            italic = props.all { it.italic },
            underline = props.all { it.underline },
            strike = props.all { it.strike },
        )
    }

    fun format(change: (ARunFmt) -> ARunFmt) {
        val s = sel()
        if (s.first == s.last) {
            val base = pending ?: combined.fmtAt(s.first)?.fmt ?: ARunFmt(null)
            pending = change(base)
            if (combined.length == 0) {
                combined = RichPara(listOf(Span("", pending!!)), combined.markers)
                dirty = true
            }
        } else {
            combined = combined.format(s.first, s.last, change)
            dirty = true
        }
        tick++
    }

    fun currentAlign(): Align? {
        val i = paraIndexAt(sel().first)
        return meta.getOrNull(i)?.pPr?.get("algn")?.let(TextPara::parseAlign)
    }

    fun setAlign(a: Align) = editParas { it.withAlign(a) }

    fun changeLevel(delta: Int) = editParas { it.withLevel(it.level + delta) }

    private fun editParas(f: (TextPara) -> TextPara) {
        val s = sel()
        val first = paraIndexAt(s.first)
        val last = paraIndexAt(s.last)
        meta = meta.mapIndexed { i, m ->
            if (i in first..last) {
                val tp = f(TextPara(RichPara(listOf(Span("", ARunFmt(null)))), m.pPr, m.endRPr))
                Meta(tp.pPr, tp.endRPr)
            } else m
        }
        propsCache.clear()
        dirty = true
        tick++
    }

    /** Writes the text into the presentation (one undo step). */
    fun commit(): Boolean {
        if (!dirty) return false
        doc.setText(slide, shapeId, paragraphs())
        dirty = false
        return true
    }
}
