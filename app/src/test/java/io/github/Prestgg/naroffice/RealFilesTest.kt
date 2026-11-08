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
}
