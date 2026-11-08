// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.ooxml.OpcPackage
import io.github.Prestgg.naroffice.pptx.Fill
import io.github.Prestgg.naroffice.pptx.Frame
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.pptx.PptxTemplate
import io.github.Prestgg.naroffice.pptx.PathCmd
import io.github.Prestgg.naroffice.pptx.ShapeKind
import io.github.Prestgg.naroffice.pptx.TextPara
import io.github.Prestgg.naroffice.text.Align
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PptxTest {

    private fun PptxDocument.bytes() = ByteArrayOutputStream().also { save(it) }.toByteArray()

    private fun write(doc: PptxDocument, slide: Int, shapeId: String, vararg lines: String) {
        val paras = doc.editableText(slide, shapeId)
        val first = paras.first()
        doc.setText(slide, shapeId, lines.map { first.withRich(first.rich.replace(0, first.rich.length, it)) })
    }

    @Test
    fun blankRoundTrip() {
        val doc = PptxDocument.open(PptxDocument.blank().bytes())
        assertEquals(1, doc.slideCount)
        assertEquals(12192000L to 6858000L, doc.slideSize)
        val s = doc.slide(0)
        assertEquals(Fill.Solid(0xFFFFFFFF.toInt()), s.background)
        assertEquals("Title Slide", s.layoutName)
        val (title, sub) = s.shapes
        // Frames come from the layout, as the slide placeholders have no xfrm.
        assertEquals(Frame(1524000, 1122363, 9144000, 2387600), title.frame)
        assertTrue(title.isEmptyPlaceholder)
        assertEquals("ctrTitle", title.placeholder?.type)
        assertEquals(ShapeKind.TEXT, title.kind)
        // Layout lstStyle (60 pt, centred) overrides the master title style.
        val tp = title.text!!.paras.first()
        assertEquals(60f, tp.endSizePt)
        assertEquals(Align.CENTER, tp.align)
        assertEquals(24f, sub.text!!.paras.first().endSizePt)
        assertEquals(3, doc.layouts().size)
        assertEquals(listOf("Title Slide", "Title and Content", "Blank"), doc.layouts().map { it.name })
    }

    @Test
    fun textEditSaveReopenIsLossless() {
        val doc = PptxDocument.blank()
        val before = OpcPackage.read(doc.bytes())
        val title = doc.slide(0).shapes.first()
        write(doc, 0, title.id, "Salam dünya", "ikinci")
        assertTrue(doc.isModified)
        val again = PptxDocument.open(doc.bytes())
        val t = again.slide(0).shapes.first()
        assertFalse(t.isEmptyPlaceholder)
        assertEquals(listOf("Salam dünya", "ikinci"), t.text!!.paras.map { it.text })
        assertEquals(60f, t.text!!.paras.first().runs.first().props.sizePt)
        assertEquals("Calibri Light", t.text!!.paras.first().runs.first().props.font)
        // Untouched parts are byte-identical.
        for (p in listOf("ppt/slideLayouts/slideLayout1.xml", "ppt/slideMasters/slideMaster1.xml", "ppt/theme/theme1.xml")) {
            assertArrayEquals(p, before.bytes(p), again.pkg.bytes(p))
        }
        assertTrue(String(again.pkg.bytes("ppt/slides/slide1.xml")!!).contains("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"))
        doc.undo()
        assertTrue(doc.slide(0).shapes.first().isEmptyPlaceholder)
        doc.redo()
        assertEquals("ikinci", doc.slide(0).shapes.first().text!!.paras[1].text)
    }

    @Test
    fun formattingAndAlign() {
        val doc = PptxDocument.blank()
        val id = doc.slide(0).shapes[1].id
        val p = doc.editableText(0, id).first()
        val rich = p.rich.replace(0, 0, "abcdef").format(0, 3) { it.bold(true).color(0xFFFF0000.toInt()).size(40f) }
        doc.setText(0, id, listOf(p.withRich(rich).withAlign(Align.END)))
        val para = doc.slide(0).shapes[1].text!!.paras.first()
        assertEquals(Align.END, para.align)
        val (r1, r2) = para.runs
        assertTrue(r1.props.bold)
        assertEquals(0xFFFF0000.toInt(), r1.props.color)
        assertEquals(40f, r1.props.sizePt)
        assertFalse(r2.props.bold)
        assertEquals(24f, r2.props.sizePt)
        val fmt = doc.editableText(0, id).first().rich.spans.first().fmt
        assertTrue(doc.resolveRun(0, id, 0, fmt).bold)
    }

    @Test
    fun masterInheritanceAndBullets() {
        val doc = PptxDocument.blank()
        val content = doc.layouts().first { it.name == "Title and Content" }
        val i = doc.addSlide(content.part)
        assertEquals(1, i)
        val s = doc.slide(i)
        val title = s.shapes.first { it.placeholder?.type == "title" }
        // Neither slide nor layout give a frame: it comes from the master.
        assertEquals(Frame(838200, 365125, 10515600, 1325563), title.frame)
        assertEquals(44f, title.text!!.paras.first().endSizePt)
        val body = s.shapes.first { it.placeholder?.idx == "1" }
        write(doc, i, body.id, "bir")
        val bp = doc.slide(i).shapes.first { it.placeholder?.idx == "1" }.text!!.paras.first()
        assertEquals(28f, bp.runs.first().props.sizePt)
        assertEquals("•", bp.bullet?.text)
        assertEquals(228600L, bp.marLEmu)
        assertEquals(Frame(838200, 1825625, 10515600, 4351338), doc.slide(i).shapes.first { it.placeholder?.idx == "1" }.frame)
        val lvl = doc.editableText(i, body.id).first().withLevel(1)
        doc.setText(i, body.id, listOf(lvl))
        assertEquals(24f, doc.slide(i).shapes.first { it.placeholder?.idx == "1" }.text!!.paras.first().runs.first().props.sizePt)
    }

    @Test
    fun handBuiltShapes() {
        val parts = PptxTemplate.parts().toMutableMap()
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        parts["ppt/slides/slide1.xml"] = """<?xml version="1.0" encoding="UTF-8"?><p:sld $ns><p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val="accent1"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="10" name="G"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="1000" y="2000"/><a:ext cx="200" cy="200"/><a:chOff x="0" y="0"/><a:chExt cx="100" cy="100"/></a:xfrm></p:grpSpPr>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="11" name="E"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="10" y="20"/><a:ext cx="50" cy="40"/></a:xfrm><a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill><a:ln w="25400"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:ln></p:spPr></p:sp></p:grpSp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="12" name="C"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm rot="5400000"><a:off x="0" y="0"/><a:ext cx="100" cy="100"/></a:xfrm><a:custGeom><a:pathLst><a:path w="10" h="10"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="10" y="5"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom></p:spPr><p:style><a:lnRef idx="2"><a:schemeClr val="accent2"/></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent3"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style><p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/><a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:rPr lang="az"/><a:t>x</a:t></a:r></a:p><a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:rPr lang="az"/><a:t>y</a:t></a:r><a:fld id="{1}" type="slidenum"><a:t>‹#›</a:t></a:fld></a:p></p:txBody></p:sp>""" +
            """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="13" name="T"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="0" y="0"/><a:ext cx="200" cy="100"/></p:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid><a:gridCol w="100"/><a:gridCol w="100"/></a:tblGrid><a:tr h="50"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="az"/><a:t>H</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:lstStyle/><a:p/></a:txBody><a:tcPr/></a:tc></a:tr><a:tr h="50"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="az"/><a:t>a</a:t></a:r></a:p></a:txBody><a:tcPr><a:solidFill><a:srgbClr val="123456"/></a:solidFill></a:tcPr></a:tc><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p/></a:txBody><a:tcPr/></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>""" +
            """</p:spTree></p:cSld></p:sld>"""
        val doc = PptxDocument(OpcPackage.fromParts(parts))
        val s = doc.slide(0)
        assertEquals(Fill.Solid(0xFFB5202E.toInt()), s.background)
        val e = s.shapes.first { it.name == "E" }
        assertEquals(Frame(1020, 2040, 100, 80), e.frame)
        assertEquals("ellipse", e.geometry)
        assertEquals(Fill.Solid(0xFF00FF00.toInt()), e.fill)
        assertEquals(25400L, e.line?.widthEmu)
        assertEquals(0xFF000000.toInt(), e.line?.argb)
        val c = s.shapes.first { it.name == "C" }
        assertEquals(90f, c.rotationDeg)
        assertNull(c.geometry)
        assertEquals(PathCmd.LineTo(1f, 0.5f), c.paths!!.first().cmds[1])
        assertEquals(Fill.Solid(0xFF4F8A3C.toInt()), c.fill)
        assertEquals(12700L, c.line?.widthEmu)
        assertEquals(ShapeKind.SHAPE, c.kind)
        val paras = c.text!!.paras
        assertEquals("1.", paras[0].bullet?.text)
        assertEquals("2.", paras[1].bullet?.text)
        assertEquals("y1", paras[1].text)
        assertEquals(0xFFFFFFFF.toInt(), paras[0].runs[0].props.color)
        val t = s.shapes.first { it.name == "T" }
        assertEquals(ShapeKind.TABLE, t.kind)
        val tbl = t.table!!
        assertEquals(listOf(100L, 100L), tbl.colWidths)
        assertEquals(2, tbl.rows[0].cells[0].gridSpan)
        assertTrue(tbl.rows[0].cells[1].hMerge)
        assertTrue(tbl.rows[0].cells[0].text.paras[0].runs[0].props.bold)
        assertEquals(Fill.Solid(0xFFB5202E.toInt()), tbl.rows[0].cells[0].fill)
        assertEquals(Fill.Solid(0xFF123456.toInt()), tbl.rows[1].cells[0].fill)

        // Moving a shape inside a group converts back to group coordinates.
        doc.setFrame(0, e.id, Frame(1000, 2000, 200, 200))
        assertEquals(Frame(1000, 2000, 200, 200), doc.slide(0).shapes.first { it.name == "E" }.frame)
        assertTrue(String(doc.pkg.bytes("ppt/slides/slide1.xml")!!).contains("""<a:off x="0" y="0"/><a:ext cx="100" cy="100"/>"""))
        doc.deleteShape(0, t.id)
        assertNull(doc.slide(0).shapes.firstOrNull { it.name == "T" })
        doc.undo()
        assertNotNull(doc.slide(0).shapes.firstOrNull { it.name == "T" })
    }

    private fun consistent(doc: PptxDocument) {
        val pkg = OpcPackage.read(doc.bytes())
        val again = PptxDocument(pkg)
        assertEquals(doc.slideCount, again.slideCount)
        val parts = again.slideParts()
        assertEquals(parts.size, parts.toSet().size)
        for (p in parts) {
            assertTrue(p, pkg.exists(p))
            assertEquals(PptxDocument.CT_SLIDE, pkg.contentType(p))
            assertNotNull(pkg.targetsOfType(p, "/slideLayout").firstOrNull())
        }
        // Every slide relationship of the presentation is listed, and vice versa.
        val relSlides = pkg.targetsOfType(again.presPart, "/slide").sorted()
        assertEquals(parts.sorted(), relSlides)
        val slideFiles = pkg.partNames.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }.sorted()
        assertEquals(parts.sorted(), slideFiles)
    }

    @Test
    fun slideOperations() {
        val doc = PptxDocument.blank()
        write(doc, 0, doc.slide(0).shapes.first().id, "Bir")
        val blank = doc.layouts().first { it.name == "Blank" }
        doc.addSlide(blank.part)
        assertEquals(2, doc.slideCount)
        assertTrue(doc.slide(1).shapes.isEmpty())
        val d = doc.duplicateSlide(0)
        assertEquals(1, d)
        assertEquals(3, doc.slideCount)
        assertEquals("Bir", doc.slide(1).shapes.first().text!!.paras.first().text)
        consistent(doc)
        doc.moveSlide(0, 2)
        // [copy, blank, original]
        assertEquals(listOf(false, true, false), (0..2).map { doc.slide(it).shapes.isEmpty() })
        doc.deleteSlide(0)
        assertEquals(2, doc.slideCount)
        consistent(doc)
        doc.undo() // delete
        assertEquals(3, doc.slideCount)
        consistent(doc)
        doc.undo() // move
        doc.undo() // duplicate
        assertEquals(2, doc.slideCount)
        consistent(doc)
        doc.redo()
        assertEquals(3, doc.slideCount)
        consistent(doc)
        doc.setHidden(2, true)
        assertTrue(doc.slide(2).hidden)
        assertEquals("Bir\n\nBir\n\n", doc.plainText())
    }

    @Test
    fun addPictureAndTextBox() {
        val doc = PptxDocument.blank()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val id = doc.addPicture(0, png, "png", 400, 200)
        val tb = doc.addTextBox(0, Frame(0, 0, 3000000, 500000), "Mətn")
        val again = PptxDocument.open(doc.bytes())
        val pic = again.slide(0).shapes.first { it.id == id }
        assertEquals(ShapeKind.PICTURE, pic.kind)
        assertEquals("ppt/media/image1.png", pic.imagePart)
        assertArrayEquals(png, again.partBytes(pic.imagePart!!))
        assertEquals(2.0, pic.frame.w.toDouble() / pic.frame.h, 0.01)
        assertEquals("image/png", again.pkg.contentType("ppt/media/image1.png"))
        val box = again.slide(0).shapes.first { it.id == tb }
        assertEquals(ShapeKind.TEXT, box.kind)
        assertEquals("Mətn", box.text!!.paras.first().text)
        assertEquals(18f, box.text!!.paras.first().runs.first().props.sizePt)
        // Split a paragraph through the editing API.
        val (a, b) = again.editableText(0, tb).first().split(2)
        again.setText(0, tb, listOf(a, b, TextPara.empty()))
        assertEquals(listOf("Mə", "tn", ""), again.slide(0).shapes.first { it.id == tb }.text!!.paras.map { it.text })
    }
}
