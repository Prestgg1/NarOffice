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
}

object Openers {
    fun open(kind: DocKind, bytes: ByteArray): OfficeDoc = when (kind) {
        DocKind.WORD -> WordDoc(DocxDocument.open(bytes))
        DocKind.SHEET -> io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(io.github.Prestgg.naroffice.xlsx.XlsxDocument.open(bytes), csv = false)
        DocKind.CSV -> io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(
            io.github.Prestgg.naroffice.xlsx.XlsxDocument.fromCsv(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")), csv = true)
        else -> throw io.github.Prestgg.naroffice.core.UnsupportedFormatException(false)
    }

    fun blank(kind: DocKind): OfficeDoc = when (kind) {
        DocKind.WORD -> WordDoc(DocxDocument.blank())
        DocKind.SHEET, DocKind.CSV -> io.github.Prestgg.naroffice.ui.xlsx.SheetDoc(io.github.Prestgg.naroffice.xlsx.XlsxDocument.blank(), csv = false)
        else -> throw io.github.Prestgg.naroffice.core.UnsupportedFormatException(false)
    }
}
