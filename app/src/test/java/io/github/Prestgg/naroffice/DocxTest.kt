// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.docx.RunFmt
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.xml.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxTest {

    private val p = """<w:p w14:paraId="1"><w:pPr><w:jc w:val="center"/></w:pPr><w:bookmarkStart w:id="0" w:name="a"/><w:r><w:rPr><w:b/></w:rPr><w:t>Hello </w:t></w:r><w:proofErr w:type="spellStart"/><w:r><w:t>wörld</w:t></w:r><w:bookmarkEnd w:id="0"/><w:hyperlink r:id="rId5"><w:r><w:rPr><w:rStyle w:val="Hyperlink"/></w:rPr><w:t>link</w:t></w:r></w:hyperlink><w:r><w:drawing><wp:inline/></w:drawing></w:r></w:p>"""

    @Test
    fun xmlRoundTrip() {
        val s = """<a:b x="1 &amp; 2" y='q"'><c>t &lt; u</c><!-- c --><d/><![CDATA[<raw>]]></a:b>"""
        val e = Xml.parseElement(s)
        assertEquals("""<a:b x="1 &amp; 2" y="q&quot;"><c>t &lt; u</c><!-- c --><d/><![CDATA[<raw>]]></a:b>""", e.toString())
    }

    @Test
    fun paragraphReadWrite() {
        val el = Xml.parseElement(p)
        val rp = DocxPara.read(el)
        assertEquals("Hello wörldlink￼", rp.text)
        val out = DocxPara.write(rp, el, el.child("w:pPr"))
        // Structure survives (proofErr is dropped on purpose).
        val expected = p.replace("""<w:proofErr w:type="spellStart"/>""", "")
            .replace("<w:t>Hello </w:t>", """<w:t xml:space="preserve">Hello </w:t>""")
        assertEquals(expected, out.toString())
    }

    @Test
    fun editKeepsFormatting() {
        val el = Xml.parseElement(p)
        var rp = DocxPara.read(el)
        rp = rp.replace(6, 11, "dünya") // replace "wörld"
        assertEquals("Hello dünyalink￼", rp.text)
        rp = rp.replace(0, 0, ">> ")
        val out = DocxPara.write(rp, el, el.child("w:pPr")).toString()
        assertTrue(out, out.contains("""<w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">&gt;&gt; Hello </w:t></w:r>"""))
        assertTrue(out.contains("<w:t>dünya</w:t>"))
        assertTrue(out.contains("""<w:hyperlink r:id="rId5">"""))
        assertTrue(out.contains("<w:bookmarkEnd"))
    }

    @Test
    fun formatSelection() {
        val el = Xml.parseElement("<w:p><w:r><w:t>abcdef</w:t></w:r></w:p>")
        val rp = DocxPara.read(el).format(2, 4) { it.with("w:i", emptyMap()) }
        val out = DocxPara.write(rp, el, null).toString()
        assertEquals("<w:p><w:r><w:t>ab</w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>cd</w:t></w:r><w:r><w:t>ef</w:t></w:r></w:p>", out)
    }

    @Test
    fun splitAndMerge() {
        val el = Xml.parseElement("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>abcdef</w:t></w:r></w:p>")
        val (a, b) = DocxPara.read(el).cut(3)
        assertEquals("abc", a.text)
        assertEquals("def", b.text)
        assertEquals("abcdef", a.append(b).text)
        val (c, d) = DocxPara.read(el).cut(6)
        assertEquals("", d.text)
        // The empty half keeps its formatting via the paragraph mark.
        val out = DocxPara.write(d, el, null).toString()
        assertTrue(out, out.contains("<w:b/>"))
        assertEquals("abcdef", c.text)
    }

    @Test
    fun blankDocumentEditSave() {
        val doc = DocxDocument.blank()
        val p0 = doc.blocks().first().el
        val rp = DocxPara.read(p0).replace(0, 0, "Salam dünya")
        doc.commitParagraphs(doc.body, p0, listOf(rp to p0.child("w:pPr")))
        val p1 = doc.blocks().first().el
        doc.setHeading(doc.body, p1, 1)
        doc.setAlign(doc.body, doc.blocks().first().el, Align.CENTER)
        doc.toggleList(doc.body, doc.blocks().first().el, bullet = true)
        doc.insertTable(doc.body, doc.blocks().first().el, 2, 3)
        val bytes = java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        val again = DocxDocument.open(bytes)
        assertEquals("Salam dünya\n\n\n\n\n\n\n\n", again.plainText())
        assertTrue(again.listLabels().values.first() == "•")
        assertEquals(1, again.headingOf(again.blocks().first().el))
        assertTrue(doc.undo())
        assertTrue(doc.undo())
        assertTrue(doc.undo())
        assertTrue(doc.undo())
        assertTrue(doc.undo())
        assertEquals("\n", doc.plainText())
    }

    @Test
    fun replaceAll() {
        val doc = DocxDocument.blank()
        val p0 = doc.blocks().first().el
        doc.commitParagraphs(doc.body, p0, listOf(DocxPara.read(p0).replace(0, 0, "a-b-a") to null))
        assertEquals(2, doc.replaceAll("a", "xy"))
        assertEquals("xy-b-xy\n", doc.plainText())
        doc.undo()
        assertEquals("a-b-a\n", doc.plainText())
    }

    @Test
    fun runFmtOrder() {
        val f = RunFmt(Xml.parseElement("<w:rPr><w:sz w:val=\"20\"/></w:rPr>")).with("w:b", emptyMap())
        assertEquals("<w:rPr><w:b/><w:sz w:val=\"20\"/></w:rPr>", f.rPr.toString())
    }
}
