// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.text

/** Character formatting shown on screen, independent of the file format. */
data class TextProps(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = 11f,
    /** ARGB, or null for the theme's text colour. */
    val color: Int? = null,
    val highlight: Int? = null,
    val font: String? = null,
    val baseline: Baseline = Baseline.NORMAL,
    val link: String? = null,
    val caps: Boolean = false,
)

enum class Baseline { NORMAL, SUPER, SUB }

enum class Align { START, CENTER, END, JUSTIFY }

/** The object replacement character marks an atomic, non-text piece (image, field, break). */
const val ATOM = '￼'

/** A soft line break inside a paragraph. */
const val SOFT_BREAK = ' '

/**
 * One run of text with a format-specific payload.
 *
 * [fmt] is opaque to the editor (an XML run-properties element for DOCX/PPTX); [atom] is set
 * for runs that stand for a single non-text object and then [text] is exactly [ATOM].
 */
class Span<F>(
    val text: String,
    val fmt: F,
    val atom: Any? = null,
    /** Optional grouping wrapper (e.g. a hyperlink element) shared by consecutive spans. */
    val wrapper: Any? = null,
) {
    fun withText(t: String) = Span(t, fmt, null, wrapper)
    fun withFmt(f: F) = Span(text, f, atom, wrapper)
}

/** A zero-width marker (bookmark, comment range…) anchored at a character offset. */
class Marker(val offset: Int, val node: Any)

/**
 * Paragraph text being edited: a list of spans plus anchored markers. All operations return a
 * new value; nothing is mutated, so values double as undo snapshots.
 */
class RichPara<F>(val spans: List<Span<F>>, val markers: List<Marker> = emptyList()) {

    val text: String by lazy { spans.joinToString("") { it.text } }
    val length: Int get() = text.length

    /** Span index and offset inside it for a character position (bias to the left span). */
    private fun locate(pos: Int): Pair<Int, Int> {
        var acc = 0
        for ((i, s) in spans.withIndex()) {
            if (pos <= acc + s.text.length) return i to (pos - acc)
            acc += s.text.length
        }
        return spans.size to 0
    }

    /** Splits spans so that [pos] falls on a span boundary. */
    fun splitAt(pos: Int): RichPara<F> {
        if (pos <= 0 || pos >= length) return this
        val (i, off) = locate(pos)
        if (i >= spans.size) return this
        val s = spans[i]
        if (off == 0 || off == s.text.length || s.atom != null) return this
        val out = ArrayList<Span<F>>(spans.size + 1)
        out.addAll(spans.subList(0, i))
        out.add(s.withText(s.text.substring(0, off)))
        out.add(s.withText(s.text.substring(off)))
        out.addAll(spans.subList(i + 1, spans.size))
        return RichPara(out, markers)
    }

    /** Format to use for text typed at [pos]: the span before it, or after it at the start. */
    fun fmtAt(pos: Int): Span<F>? {
        if (spans.isEmpty()) return null
        var acc = 0
        var before: Span<F>? = null
        for (s in spans) {
            val sStart = acc
            acc += s.text.length
            if (s.atom != null) continue
            if (sStart < pos) before = s else return before ?: s
        }
        return before ?: spans.firstOrNull { it.atom == null } ?: spans.first()
    }

    /** Replaces [start, end) with [insert], styled like [like] (or the text before it). */
    fun replace(start: Int, end: Int, insert: String, like: Span<F>? = null, fallback: F? = null): RichPara<F> {
        val st = start.coerceIn(0, length)
        val en = end.coerceIn(st, length)
        // Replacing text keeps the format of the first replaced character.
        val ref = like ?: if (en > st) fmtAt(st + 1) else fmtAt(st)
        val p = splitAt(st).splitAt(en)
        val out = ArrayList<Span<F>>()
        var acc = 0
        var inserted = insert.isEmpty()
        for (s in p.spans) {
            val sStart = acc
            val sEnd = acc + s.text.length
            acc = sEnd
            if (!inserted && sStart >= st) {
                out.add(newSpan(insert, ref, fallback))
                inserted = true
            }
            if (sStart >= st && sEnd <= en && s.text.isNotEmpty()) continue // deleted
            if (s.text.isEmpty() && sStart in st until en) continue
            out.add(s)
        }
        if (!inserted) out.add(newSpan(insert, ref, fallback))
        val delta = insert.length - (en - st)
        val newMarkers = markers.map {
            when {
                it.offset <= st -> it
                it.offset >= en -> Marker(it.offset + delta, it.node)
                else -> Marker(st, it.node)
            }
        }
        return RichPara(merge(out), newMarkers)
    }

    private fun newSpan(text: String, like: Span<F>?, fallback: F?): Span<F> {
        val f = like?.fmt ?: fallback ?: spans.firstOrNull()?.fmt
        ?: error("No format available for new text")
        return Span(text, f, null, like?.wrapper)
    }

    /** Applies [transform] to the format of every span intersecting [start, end). */
    fun format(start: Int, end: Int, transform: (F) -> F): RichPara<F> {
        if (start >= end) return this
        val p = splitAt(start).splitAt(end)
        var acc = 0
        val out = p.spans.map { s ->
            val sStart = acc
            acc += s.text.length
            if (sStart >= start && acc <= end && s.text.isNotEmpty()) s.withFmt(transform(s.fmt)) else s
        }
        return RichPara(merge(out), markers)
    }

    /** Splits into two paragraphs at [pos]. */
    fun cut(pos: Int): Pair<RichPara<F>, RichPara<F>> {
        val p = splitAt(pos)
        val left = ArrayList<Span<F>>()
        val right = ArrayList<Span<F>>()
        var acc = 0
        for (s in p.spans) {
            if (acc + s.text.length <= pos && !(s.text.isEmpty() && acc == pos && pos == 0)) left.add(s) else right.add(s)
            acc += s.text.length
        }
        // Each half keeps a format to type with, even when it is empty.
        if (right.isEmpty()) (left.lastOrNull { it.atom == null } ?: left.lastOrNull())?.let { right.add(Span("", it.fmt, null, null)) }
        if (left.isEmpty()) (right.firstOrNull { it.atom == null } ?: right.firstOrNull())?.let { left.add(Span("", it.fmt, null, null)) }
        val lm = markers.filter { it.offset <= pos }
        val rm = markers.filter { it.offset > pos }.map { Marker(it.offset - pos, it.node) }
        return RichPara(left, lm) to RichPara(right, rm)
    }

    fun append(other: RichPara<F>): RichPara<F> {
        val off = length
        return RichPara(
            merge(spans + other.spans),
            markers + other.markers.map { Marker(it.offset + off, it.node) },
        )
    }

    /** Spans whose text intersects [start, end), or the span at [start] if empty. */
    fun spansIn(start: Int, end: Int): List<Span<F>> {
        if (start >= end) return listOfNotNull(fmtAt(start))
        val out = ArrayList<Span<F>>()
        var acc = 0
        for (s in spans) {
            val sStart = acc
            acc += s.text.length
            if (acc > start && sStart < end && s.text.isNotEmpty()) out.add(s)
        }
        return out
    }

    private fun merge(list: List<Span<F>>): List<Span<F>> {
        val out = ArrayList<Span<F>>(list.size)
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && last.atom == null && s.atom == null && last.wrapper === s.wrapper &&
                last.fmt == s.fmt
            ) {
                out[out.size - 1] = Span(last.text + s.text, last.fmt, null, last.wrapper)
            } else if (s.text.isNotEmpty() || s.atom != null) {
                out.add(s)
            } else if (list.size == 1) {
                out.add(s) // keep the single empty span so the paragraph remembers its format
            }
        }
        if (out.isEmpty() && list.isNotEmpty()) out.add(list.first().withText(""))
        return out
    }
}
