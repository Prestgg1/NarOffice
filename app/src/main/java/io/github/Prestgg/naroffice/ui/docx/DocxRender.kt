// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.docx

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.docx.AtomKind
import io.github.Prestgg.naroffice.docx.DocAtom
import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.docx.RunFmt
import io.github.Prestgg.naroffice.docx.WPara
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.text.TextProps
import io.github.Prestgg.naroffice.ui.common.TextStyles
import io.github.Prestgg.naroffice.xml.XElement

/** First character of an editable paragraph field; deleting it means "join with previous". */
const val SENTINEL = '​'

class ImageRef(val relId: String, val widthPt: Float, val heightPt: Float)

class ViewText(
    val text: AnnotatedString,
    val images: Map<String, ImageRef>,
    /** For each character of [text], the offset in the paragraph's editable text. */
    val toRich: IntArray,
    val hasPageBreak: Boolean,
)

class DocxRenderer(val doc: DocxDocument, val scale: Float, val maxWidthPt: Float) {

    fun props(pStyleId: String?, s: Span<RunFmt>): TextProps {
        var p = doc.styles.resolveRun(pStyleId, s.fmt.rPr)
        @Suppress("UNCHECKED_CAST")
        (s.wrapper as List<XElement>?)?.firstOrNull { it.localName == "hyperlink" }?.let { h ->
            p = p.copy(link = doc.hyperlink(h) ?: "#")
        }
        return p
    }

    fun viewText(p: XElement, rp: WPara, query: String?, links: Boolean): ViewText {
        val pStyle = doc.styles.paraStyleId(p.child("w:pPr"))
        val images = HashMap<String, ImageRef>()
        val map = ArrayList<Int>()
        var pageBreak = false
        var richPos = 0
        val text = buildAnnotatedString {
            for (s in rp.spans) {
                val tp = props(pStyle, s)
                val style = TextStyles.span(tp, scale)
                val atom = s.atom as DocAtom?
                if (atom != null) {
                    when (atom.kind) {
                        AtomKind.IMAGE -> {
                            val rel = DocxPara.imageRelId(atom.el)
                            if (rel != null) {
                                var (w, h) = DocxPara.imageSizePt(atom.el) ?: (120f to 90f)
                                if (w > maxWidthPt) {
                                    h = h * maxWidthPt / w; w = maxWidthPt
                                }
                                val id = "img${images.size}"
                                images[id] = ImageRef(rel, w, h)
                                appendInlineContent(id, "￼")
                                map.add(richPos)
                            }
                        }
                        AtomKind.PAGE_BREAK -> pageBreak = true
                        AtomKind.TEXTUAL -> withStyle(style) {
                            val d = atom.display
                            append(d)
                            repeat(d.length) { map.add(richPos) }
                        }
                        AtomKind.HIDDEN -> {}
                    }
                    richPos += 1
                    continue
                }
                val shown = if (tp.caps) s.text.uppercase() else s.text
                val t = shown.replace(SOFT_BREAK, '\n')
                if (links && tp.link != null && !tp.link.startsWith("#")) {
                    withLink(LinkAnnotation.Url(tp.link)) { withStyle(style) { append(t) } }
                } else {
                    withStyle(style) { append(t) }
                }
                for (k in t.indices) map.add(richPos + k.coerceAtMost(s.text.length - 1))
                richPos += s.text.length
            }
            if (!query.isNullOrEmpty()) {
                val plain = toString()
                var i = plain.indexOf(query, ignoreCase = true)
                while (i >= 0) {
                    addStyle(SpanStyle(background = Color(0xFFFFE066)), i, i + query.length)
                    i = plain.indexOf(query, i + query.length, ignoreCase = true)
                }
            }
        }
        map.add(richPos)
        return ViewText(text, images, map.toIntArray(), pageBreak)
    }

    fun inlineContent(view: ViewText, content: @androidx.compose.runtime.Composable (ImageRef) -> Unit): Map<String, InlineTextContent> =
        view.images.mapValues { (_, ref) ->
            InlineTextContent(
                Placeholder((ref.widthPt * scale).sp, (ref.heightPt * scale).sp, PlaceholderVerticalAlign.TextBottom),
            ) { content(ref) }
        }

    /** Styled text for the edit field: [SENTINEL] + paragraph text, with atoms shown as symbols. */
    fun editVisual(p: XElement, rp: WPara): AnnotatedString {
        val pStyle = doc.styles.paraStyleId(p.child("w:pPr"))
        return buildAnnotatedString {
            append(SENTINEL)
            for (s in rp.spans) {
                val tp = props(pStyle, s)
                val style = TextStyles.span(tp, scale)
                val atom = s.atom as DocAtom?
                if (atom != null) {
                    val ch = when (atom.kind) {
                        AtomKind.IMAGE -> '▣'
                        AtomKind.PAGE_BREAK -> '↵'
                        AtomKind.TEXTUAL -> atom.display.firstOrNull() ?: '◊'
                        AtomKind.HIDDEN -> SENTINEL
                    }
                    withStyle(style.copy(color = if (atom.kind == AtomKind.TEXTUAL) style.color else Color(0xFF8E8E8E))) {
                        append(ch)
                    }
                } else {
                    withStyle(style) { append(s.text.replace(SOFT_BREAK, '\n')) }
                }
            }
        }
    }
}
