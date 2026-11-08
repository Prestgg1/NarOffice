// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.ooxml.OpcPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class FilePackageTest {
    @Test
    fun fileBackedPackageStreamsUnchangedParts() {
        // A document with a large "video" part that must survive untouched.
        val blank = DocxDocument.blank()
        val media = Random(1).nextBytes(3_000_000)
        blank.pkg.putBytes("word/media/clip.mp4", media, "video/mp4")
        val src = File.createTempFile("nar", ".docx").apply { writeBytes(blank.pkg.toBytes()) }
        val doc = DocxDocument(OpcPackage.open(src))
        val p = doc.blocks().first().el
        doc.commitParagraphs(doc.body, p, listOf(DocxPara.read(p).replace(0, 0, "Salam") to null))
        val out = File.createTempFile("nar-out", ".docx")
        out.outputStream().use { doc.save(it) }
        doc.pkg.close()
        val again = DocxDocument.open(out.readBytes())
        assertEquals("Salam\n", again.plainText())
        assertArrayEquals(media, again.pkg.bytes("word/media/clip.mp4"))
        src.delete(); out.delete()
    }
}
