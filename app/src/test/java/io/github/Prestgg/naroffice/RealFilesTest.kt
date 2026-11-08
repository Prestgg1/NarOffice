// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.docx.DocBlock
import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opens real files from $NAROFFICE_SAMPLES (if set), rewrites every paragraph through the
 * editor model, and writes the result to $NAROFFICE_SAMPLES/out for checking in other apps.
 */
class RealFilesTest {
    @Test
    fun rewriteAllParagraphs() {
        val dir = System.getenv("NAROFFICE_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(dir, "out").apply { mkdirs() }
        for (f in dir!!.listFiles()!!.filter { it.name.endsWith(".docx") }) {
            val t0 = System.nanoTime()
            val doc = DocxDocument.open(f.readBytes())
            var n = 0
            fun visit(container: io.github.Prestgg.naroffice.xml.XElement) {
                for (b in doc.blocksOf(container)) when (b) {
                    is DocBlock.Para -> {
                        val rp = DocxPara.read(b.el)
                        // Append a marker to every non-empty paragraph to exercise the writer.
                        val edited = if (rp.length > 0) rp.replace(rp.length, rp.length, " ✓") else rp
                        doc.commitParagraphs(b.container, b.el, listOf(edited to b.el.child("w:pPr")))
                        n++
                    }
                    is DocBlock.Table -> b.rows.forEach { r -> doc.cells(r).forEach { visit(it) } }
                }
            }
            visit(doc.body)
            File(out, f.name).outputStream().use { doc.save(it) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("${f.name}: $n paragraphs, ${ms}ms, labels=${doc.listLabels().size}")
        }
    }

    @Test
    fun spreadsheetsAndSlides() {
        val dir = System.getenv("NAROFFICE_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(dir, "out").apply { mkdirs() }
        for (f in dir!!.listFiles()!!.filter { it.name.endsWith(".xlsx") }) {
            val t0 = System.nanoTime()
            val doc = io.github.Prestgg.naroffice.xlsx.XlsxDocument.open(f.readBytes())
            var cells = 0
            for (i in 0 until doc.sheetCount) {
                val sh = doc.sheet(i)
                for (r in 0..sh.maxRow) for (c in 0..sh.maxCol) if (sh.cell(r, c) != null) cells++
            }
            val sh = doc.sheet(0)
            val before = (0..12).map { r -> (0..5).joinToString("|") { c -> sh.cell(r, c)?.display ?: "" } }
            doc.setCellInput(0, 2, 1, "100")
            val t1 = (System.nanoTime() - t0) / 1_000_000
            File(out, f.name).outputStream().use { doc.save(it) }
            println("${f.name}: ${doc.sheetCount} sheets, $cells cells, ${t1}ms")
            before.forEach { println("   $it") }
            println("   after B3=100: D3=${sh.cell(2, 3)?.display} D9=${sh.cell(8, 3)?.display} B13=${sh.cell(12, 1)?.display}")
        }
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".pptx") }) {
            val doc = io.github.Prestgg.naroffice.pptx.PptxDocument.open(f.readBytes())
            for (i in 0 until doc.slideCount) {
                val s = doc.slide(i)
                println("${f.name} slide ${i + 1}: " + s.shapes.joinToString { sh ->
                    "${sh.kind}${if (sh.editable) "*" else ""}:" + (sh.text?.paras?.joinToString("/") { p -> p.runs.joinToString("") { it.text } } ?: "")
                })
            }
            val s0 = doc.slide(0)
            val title = s0.shapes.first { it.editable && it.text != null }
            val paras = doc.editableText(0, title.id)
            doc.setText(0, title.id, listOf(paras.first().withRich(paras.first().rich.replace(0, 0, "✓ "))) + paras.drop(1))
            File(out, f.name).outputStream().use { doc.save(it) }
        }
    }
}
