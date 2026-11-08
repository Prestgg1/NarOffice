// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.export

import android.app.Activity
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.Prestgg.naroffice.docx.AtomKind
import io.github.Prestgg.naroffice.docx.DocAtom
import io.github.Prestgg.naroffice.docx.DocBlock
import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.ui.common.Images
import io.github.Prestgg.naroffice.ui.pptx.drawSlide
import io.github.Prestgg.naroffice.ui.pptx.imageKey
import io.github.Prestgg.naroffice.ui.pptx.imageParts
import io.github.Prestgg.naroffice.ui.pptx.layoutSlide
import io.github.Prestgg.naroffice.xlsx.CellRange
import io.github.Prestgg.naroffice.xlsx.HAlign
import io.github.Prestgg.naroffice.xlsx.VAlign
import io.github.Prestgg.naroffice.xlsx.XlsxDocument
import io.github.Prestgg.naroffice.xml.XElement
import java.io.FileOutputStream
import java.io.OutputStream

/** Printing and PDF export (through the system print dialog, which offers "Save as PDF"). */
object Export {

    // Keeps the WebView alive until printing has started.
    private var printingView: WebView? = null

    fun printHtml(activity: Activity, html: String, jobName: String) {
        val wv = WebView(activity)
        printingView = wv
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
                printingView = null
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** Prints a ready-made PDF produced by [write]. */
    fun printPdf(activity: Activity, jobName: String, pages: Int, write: (OutputStream) -> Unit) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled(); return
                }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder("$jobName.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(pages).build(),
                    true,
                )
            }

            override fun onWrite(
                pageRanges: Array<out PageRange>?, destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?, callback: WriteResultCallback,
            ) {
                runCatching {
                    FileOutputStream(destination.fileDescriptor).use { write(it) }
                }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                    .onFailure { callback.onWriteFailed(it.message) }
            }
        }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).build())
    }

    // ------------------------------------------------------------------ slides

    fun slidesToPdf(ctx: Context, doc: PptxDocument, out: OutputStream) {
        val (cx, cy) = doc.slideSize
        val w = (cx / 12700f)
        val h = (cy / 12700f)
        // Render at 2× so that text measurement has enough precision, then scale the canvas down.
        val k = 2f
        val measurer = TextMeasurer(createFontFamilyResolver(ctx), Density(1f), LayoutDirection.Ltr)
        val pdf = PdfDocument()
        try {
            var pageNo = 1
            for (i in 0 until doc.slideCount) {
                val view = doc.slide(i)
                if (view.hidden) continue
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), pageNo++).create())
                val size = Size(w * k, h * k)
                val layout = layoutSlide(view, doc.slideSize, size, measurer, 1f, 1f, false, "", "", emptyMap())
                val images = imageParts(view).mapNotNull { part ->
                    val key = imageKey(doc, part)
                    Images.decode(key, doc.partBytes(part))?.let { part to it }
                }.toMap()
                val canvas = page.canvas
                canvas.scale(1f / k, 1f / k)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), size) {
                    drawSlide(view, layout, images)
                }
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    // ------------------------------------------------------------------ documents

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;")
            '\t' -> append("&emsp;")
            SOFT_BREAK, '\n' -> append("<br>")
            else -> append(c)
        }
    }

    private fun css(argb: Int) = "#" + Colors.toHex(argb)

    fun docxToHtml(doc: DocxDocument): String {
        val labels = doc.listLabels()
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>")
        sb.append("body{font-family:sans-serif;color:#1b1b1b;margin:0}p{margin:0;white-space:pre-wrap}")
        sb.append("table{border-collapse:collapse;width:100%;margin:4pt 0}td{vertical-align:top;padding:2pt 4pt}")
        sb.append(".pb{page-break-after:always}@page{margin:2cm}")
        sb.append("</style></head><body>")
        fun para(p: XElement) {
            val pPr = p.child("w:pPr")
            val pp = doc.styles.resolvePara(pPr)
            val pStyle = doc.styles.paraStyleId(pPr)
            val style = StringBuilder()
            style.append("text-align:").append(
                when (pp.align) { Align.CENTER -> "center"; Align.END -> "right"; Align.JUSTIFY -> "justify"; else -> "left" },
            ).append(';')
            style.append("margin-top:${pp.spaceBeforePt}pt;margin-bottom:${pp.spaceAfterPt}pt;")
            style.append("line-height:${1.15f * pp.lineSpacing};")
            if (pp.indentLeftPt > 0) style.append("margin-left:${pp.indentLeftPt}pt;")
            if (pp.indentFirstPt != 0f && labels[p] == null) style.append("text-indent:${pp.indentFirstPt}pt;")
            pp.shading?.let { style.append("background:${css(it)};") }
            if (pp.borderBottom) style.append("border-bottom:1px solid #555;")
            if (pp.pageBreakBefore) style.append("page-break-before:always;")
            sb.append("<p style=\"").append(style).append("\">")
            labels[p]?.let { sb.append(esc(it)).append("&ensp;") }
            val rich = DocxPara.read(p)
            var pageBreak = false
            if (rich.length == 0) sb.append("&nbsp;")
            for (s in rich.spans) {
                val atom = s.atom as DocAtom?
                if (atom != null) {
                    when (atom.kind) {
                        AtomKind.IMAGE -> {
                            val rel = DocxPara.imageRelId(atom.el)
                            val bytes = rel?.let { doc.imageBytes(it) }
                            if (bytes != null) {
                                val (w, h) = DocxPara.imageSizePt(atom.el) ?: (200f to 150f)
                                val mime = if (bytes.size > 3 && bytes[0] == 0xFF.toByte()) "image/jpeg" else "image/png"
                                sb.append("<img style=\"width:${w}pt;height:${h}pt;max-width:100%\" src=\"data:$mime;base64,")
                                    .append(Base64.encodeToString(bytes, Base64.NO_WRAP)).append("\">")
                            }
                        }
                        AtomKind.PAGE_BREAK -> pageBreak = true
                        AtomKind.TEXTUAL -> sb.append(esc(atom.display))
                        AtomKind.HIDDEN -> {}
                    }
                    continue
                }
                val t = doc.styles.resolveRun(pStyle, s.fmt.rPr)
                val st = StringBuilder("font-size:${t.sizePt}pt;")
                if (t.bold) st.append("font-weight:bold;")
                if (t.italic) st.append("font-style:italic;")
                val deco = listOfNotNull(if (t.underline) "underline" else null, if (t.strike) "line-through" else null)
                if (deco.isNotEmpty()) st.append("text-decoration:${deco.joinToString(" ")};")
                t.color?.let { st.append("color:${css(it)};") }
                t.highlight?.let { st.append("background:${css(it)};") }
                t.font?.let { st.append("font-family:'${it.replace("'", "")}',sans-serif;") }
                if (t.baseline == Baseline.SUPER) st.append("vertical-align:super;font-size:${t.sizePt * 0.65f}pt;")
                if (t.baseline == Baseline.SUB) st.append("vertical-align:sub;font-size:${t.sizePt * 0.65f}pt;")
                sb.append("<span style=\"").append(st).append("\">")
                    .append(esc(if (t.caps) s.text.uppercase() else s.text)).append("</span>")
            }
            sb.append("</p>")
            if (pageBreak) sb.append("<div class=\"pb\"></div>")
        }
        fun blocks(container: XElement) {
            for (b in doc.blocksOf(container)) when (b) {
                is DocBlock.Para -> para(b.el)
                is DocBlock.Table -> {
                    sb.append("<table>")
                    for (row in b.rows) {
                        sb.append("<tr>")
                        for (cell in doc.cells(row)) {
                            val tcPr = cell.child("w:tcPr")
                            val vMerge = tcPr?.child("w:vMerge")
                            if (vMerge != null && vMerge["w:val"] != "restart") continue
                            val span = tcPr?.child("w:gridSpan")?.get("w:val")?.toIntOrNull() ?: 1
                            val fill = tcPr?.child("w:shd")?.get("w:fill")?.let { Colors.hex(it) }
                            sb.append("<td colspan=\"$span\" style=\"border:0.5pt solid #777;")
                            fill?.let { sb.append("background:${css(it)};") }
                            sb.append("\">")
                            blocks(cell)
                            sb.append("</td>")
                        }
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                }
            }
        }
        blocks(doc.body)
        sb.append("</body></html>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ spreadsheets

    fun xlsxToHtml(doc: XlsxDocument): String {
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>")
        sb.append("body{font-family:sans-serif;margin:0}table{border-collapse:collapse;table-layout:fixed}")
        sb.append("td{padding:1pt 3pt;overflow:hidden;white-space:nowrap;border:0.5pt solid #ddd;font-size:10pt}")
        sb.append("h3{font-size:11pt;margin:0 0 4pt}.pb{page-break-after:always}@page{margin:1.5cm}")
        sb.append("</style></head><body>")
        for (si in 0 until doc.sheetCount) {
            if (doc.sheets[si].hidden) continue
            val sh = doc.sheet(si)
            if (sh.maxRow < 0) continue
            if (si > 0) sb.append("<div class=\"pb\"></div>")
            sb.append("<h3>").append(esc(doc.sheets[si].name)).append("</h3><table>")
            for (c in 0..sh.maxCol) {
                val w = if (sh.isColHidden(c)) 0f else sh.colWidthPt(c)
                sb.append("<col style=\"width:${w}pt\">")
            }
            val covered = HashSet<Long>()
            for (m in sh.merges) for (r in m.r1..m.r2) for (c in m.c1..m.c2) {
                if (r != m.r1 || c != m.c1) covered.add(r.toLong() shl 20 or c.toLong())
            }
            for (r in 0..sh.maxRow) {
                if (sh.isRowHidden(r)) continue
                sb.append("<tr style=\"height:${sh.rowHeightPt(r)}pt\">")
                for (c in 0..sh.maxCol) {
                    if ((r.toLong() shl 20 or c.toLong()) in covered) continue
                    val m: CellRange? = sh.mergeAt(r, c)
                    val cell = sh.cell(r, c)
                    sb.append("<td")
                    if (m != null) sb.append(" colspan=\"${m.cols}\" rowspan=\"${m.rows}\"")
                    if (cell != null) {
                        val st = cell.style
                        sb.append(" style=\"")
                        sb.append("font-size:${st.fontSizePt * 0.9f}pt;")
                        if (st.bold) sb.append("font-weight:bold;")
                        if (st.italic) sb.append("font-style:italic;")
                        if (st.underline) sb.append("text-decoration:underline;")
                        st.fontColor?.let { sb.append("color:${css(it)};") }
                        st.fill?.let { sb.append("background:${css(it)};") }
                        sb.append("text-align:").append(
                            when (cell.effectiveAlign) { HAlign.CENTER -> "center"; HAlign.RIGHT -> "right"; else -> "left" },
                        ).append(';')
                        sb.append("vertical-align:").append(
                            when (st.vAlign) { VAlign.TOP -> "top"; VAlign.CENTER -> "middle"; else -> "bottom" },
                        ).append(';')
                        if (st.wrap) sb.append("white-space:normal;")
                        fun b(side: String, bd: io.github.Prestgg.naroffice.xlsx.Border) {
                            if (bd.present) sb.append("border-$side:${if (bd.thick) "1.5pt" else "0.5pt"} solid ${bd.color?.let { css(it) } ?: "#000"};")
                        }
                        b("left", st.left); b("right", st.right); b("top", st.top); b("bottom", st.bottom)
                        sb.append("\">").append(esc(cell.display))
                    } else {
                        sb.append(">")
                    }
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }
}
