// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui

import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.OfficeDoc
import io.github.Prestgg.naroffice.docx.DocxDocument
import java.io.OutputStream

class WordDoc(val doc: DocxDocument) : OfficeDoc() {
    override val kind = DocKind.WORD
    override val isModified get() = doc.isModified
    override fun save(out: OutputStream) = doc.save(out)
    override fun markSaved() = doc.history.markSaved()
    override fun close() = doc.pkg.close()
}

object Openers {
    fun open(kind: DocKind, file: java.io.File): OfficeDoc {
        if (kind == DocKind.CSV) {
            return io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(
                io.github.Prestgg.naroffice.xlsx.XlsxDocument.fromCsv(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")), csv = true)
        }
        val pkg = io.github.Prestgg.naroffice.ooxml.OpcPackage.open(file)
        try {
            return when (kind) {
                DocKind.WORD -> WordDoc(DocxDocument(pkg))
                DocKind.SHEET -> io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(
                    io.github.Prestgg.naroffice.xlsx.XlsxDocument(pkg).also { it.recalcIfStale() }, csv = false)
                DocKind.SLIDES -> io.github.Prestgg.naroffice.ui.pptx.SlidesDoc(io.github.Prestgg.naroffice.pptx.PptxDocument(pkg))
                DocKind.CSV -> error("unreachable")
            }
        } catch (t: Throwable) {
            pkg.close()
            throw t
        }
    }

    fun blank(kind: DocKind): OfficeDoc = when (kind) {
        DocKind.WORD -> WordDoc(DocxDocument.blank())
        DocKind.SHEET, DocKind.CSV -> io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(io.github.Prestgg.naroffice.xlsx.XlsxDocument.blank(), csv = false)
        DocKind.SLIDES -> io.github.Prestgg.naroffice.ui.pptx.SlidesDoc(io.github.Prestgg.naroffice.pptx.PptxDocument.blank())
    }
}
