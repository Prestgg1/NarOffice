// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.ooxml.OpcPackage
import io.github.Prestgg.naroffice.xlsx.CellRange
import io.github.Prestgg.naroffice.xlsx.HAlign
import io.github.Prestgg.naroffice.xlsx.NumFmtPreset
import io.github.Prestgg.naroffice.xlsx.NumberFormat
import io.github.Prestgg.naroffice.xlsx.StyleChange
import io.github.Prestgg.naroffice.xlsx.XValue
import io.github.Prestgg.naroffice.xlsx.XlsxDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class XlsxTest {

    private fun saved(doc: XlsxDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    /** A workbook as Excel writes it: shared strings, styles, shared formula, merge, calcChain. */
    private fun excelLike(): XlsxDocument {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val pkg = OpcPackage.fromParts(
            linkedMapOf(
                "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/></Types>""",
                "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="$ns" xmlns:r="$rel"><bookViews><workbookView activeTab="1"/></bookViews><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="My Sheet" sheetId="2" r:id="rId2"/></sheets><definedNames><definedName name="Area">Data!${'$'}A${'$'}1:${'$'}B${'$'}5</definedName></definedNames><calcPr calcId="191029"/></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="$rel/sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId4" Type="$rel/styles" Target="styles.xml"/><Relationship Id="rId5" Type="$rel/calcChain" Target="calcChain.xml"/></Relationships>""",
                "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="$ns" count="3" uniqueCount="3"><si><t>Ad</t></si><si><t>Məbləğ</t></si><si><r><rPr><b/></rPr><t>Cəm</t></r><r><t>i</t></r></si></sst>""",
                "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="$ns"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00&quot; ₼&quot;"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><color rgb="FFFF0000"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" applyFont="1"><alignment horizontal="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/></cellXfs></styleSheet>""",
                "xl/calcChain.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><calcChain xmlns="$ns"><c r="B5" i="1"/></calcChain>""",
                "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="$ns" xmlns:r="$rel"><dimension ref="A1:C5"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="B3" sqref="B3"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="1" width="20.7109375" customWidth="1"/></cols><sheetData><row r="1" spans="1:3"><c r="A1" t="s" s="1"><v>0</v></c><c r="B1" t="s" s="1"><v>1</v></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>Alma</t></is></c><c r="B2" s="2"><v>10</v></c><c r="C2"><f t="shared" ref="C2:C4" si="0">B2*2</f><v>20</v></c></row><row r="3"><c r="A3" t="inlineStr"><is><t>Armud</t></is></c><c r="B3" s="2"><v>20.5</v></c><c r="C3"><f t="shared" si="0"/><v>41</v></c></row><row r="4" ht="30" customHeight="1"><c r="A4" t="inlineStr"><is><t>Nar</t></is></c><c r="B4" s="2"><v>30</v></c><c r="C4"><f t="shared" si="0"/><v>60</v></c></row><row r="5"><c r="A5" t="s"><v>2</v></c><c r="B5" s="2"><f>SUM(B2:B4)</f><v>60.5</v></c><c r="C5"><f>CUBEVALUE("x")</f><v>42</v></c><c r="D5" s="3"><v>45658</v></c></row></sheetData><mergeCells count="1"><mergeCell ref="A6:B6"/></mergeCells><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>""",
                "xl/worksheets/sheet2.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1"><f>Data!B5*2</f><v>121</v></c><c r="B1"><f>SUMIF(Data!A2:A4,"A*",Data!B2:B4)</f><v>30.5</v></c><c r="C1"><f>VLOOKUP("Nar",Data!A2:B4,2,FALSE)</f><v>30</v></c></row></sheetData></worksheet>""",
            ),
        )
        return XlsxDocument(pkg)
    }

    @Test
    fun readsWorkbook() {
        val doc = excelLike()
        assertEquals(listOf("Data", "My Sheet"), doc.sheets.map { it.name })
        assertEquals(1, doc.activeSheet)
        val s = doc.sheet(0)
        assertEquals(4, s.maxRow)
        assertEquals(3, s.maxCol)
        assertEquals("Ad", s.cell(0, 0)!!.display)
        assertEquals("Cəmi", s.cell(4, 0)!!.display)
        val h = s.cell(0, 1)!!.style
        assertTrue(h.bold)
        assertEquals(14f, h.fontSizePt)
        assertEquals(0xFFFF0000.toInt(), h.fontColor)
        assertEquals(0xFFFFFF00.toInt(), h.fill)
        assertEquals(HAlign.CENTER, h.hAlign)
        assertEquals("10.00 ₼", s.cell(1, 1)!!.display)
        assertEquals(HAlign.RIGHT, s.cell(1, 1)!!.effectiveAlign)
        assertEquals("B3*2", s.cell(2, 2)!!.formula)
        assertEquals("41", s.cell(2, 2)!!.display)
        assertEquals("01.01.2025", s.cell(4, 3)!!.display)
        assertEquals(1, s.frozenRows)
        assertEquals(0, s.frozenCols)
        assertEquals(30f, s.rowHeightPt(3))
        assertEquals(15f, s.rowHeightPt(1))
        assertTrue(s.colWidthPt(0) > 100f)
        assertEquals(listOf(CellRange(5, 0, 5, 1)), s.merges)
        assertEquals("=SUM(B2:B4)", s.inputText(4, 1))
        assertEquals("10", s.inputText(1, 1))
        assertEquals("01.01.2025", s.inputText(4, 3))
    }

    @Test
    fun editRecalculatesAcrossSheets() {
        val doc = excelLike()
        doc.setCellInput(0, 1, 1, "100")
        val s = doc.sheet(0)
        assertEquals(XValue.Num(150.5), s.value(4, 1))
        assertEquals(XValue.Num(200.0), s.value(1, 2)) // shared formula C2 = B2*2
        assertEquals(XValue.Num(41.0), s.value(2, 2))
        // Unsupported function keeps its cached value.
        assertEquals(XValue.Num(42.0), s.value(4, 2))
        val s2 = doc.sheet(1)
        assertEquals(XValue.Num(301.0), s2.value(0, 0))
        assertEquals(XValue.Num(120.5), s2.value(0, 1)) // Alma + Armud
        assertEquals(XValue.Num(30.0), s2.value(0, 2))
        assertTrue(doc.isModified)
        doc.undo()
        assertEquals(XValue.Num(60.5), s.value(4, 1))
        assertEquals(XValue.Num(121.0), s2.value(0, 0))
        doc.redo()
        assertEquals(XValue.Num(150.5), s.value(4, 1))
    }

    @Test
    fun saveAndReopen() {
        val doc = excelLike()
        doc.setCellInput(0, 9, 4, "Salam")
        doc.setCellInput(0, 9, 5, "1,5")
        doc.setCellInput(0, 9, 6, "=F10*2")
        doc.setCellInput(0, 9, 7, "15%")
        doc.setCellInput(0, 9, 8, "31.12.2024")
        doc.setCellInput(0, 9, 9, "true")
        doc.setCellInput(0, 9, 10, "=1/0")
        val bytes = saved(doc)
        assertFalse(doc.isModified)
        val pkg = OpcPackage.read(bytes)
        assertFalse(pkg.exists("xl/calcChain.xml"))
        assertTrue(pkg.rels("xl/workbook.xml").none { it.type.endsWith("/calcChain") })
        val wbXml = String(pkg.bytes("xl/workbook.xml")!!)
        assertTrue(wbXml, wbXml.contains("fullCalcOnLoad=\"1\""))
        val sheetXml = String(pkg.bytes("xl/worksheets/sheet1.xml")!!)
        assertTrue(sheetXml.contains("<dimension ref=\"A1:K10\"/>"))
        val again = XlsxDocument.open(bytes)
        val s = again.sheet(0)
        assertEquals("Salam", s.cell(9, 4)!!.display)
        assertEquals(XValue.Num(1.5), s.value(9, 5))
        assertEquals(XValue.Num(3.0), s.value(9, 6))
        assertEquals("15%", s.cell(9, 7)!!.display)
        assertEquals("31.12.2024", s.cell(9, 8)!!.display)
        assertEquals(XValue.Bool(true), s.value(9, 9))
        assertEquals("#DIV/0!", s.cell(9, 10)!!.display)
        // Untouched content survives.
        assertEquals("Cəmi", s.cell(4, 0)!!.display)
        assertEquals("10.00 ₼", s.cell(1, 1)!!.display)
        assertEquals(1, s.frozenRows)
    }

    @Test
    fun clearCellKeepsStyle() {
        val doc = excelLike()
        doc.setCellInput(0, 1, 1, "")
        val c = doc.sheet(0).cell(1, 1)!!
        assertEquals(XValue.Empty, c.value)
        assertEquals(2, c.styleIndex)
        doc.setCellInput(0, 20, 20, "x")
        doc.setCellInput(0, 20, 20, "")
        assertNull(doc.sheet(0).cellElement(20, 20))
    }

    @Test
    fun styleEdits() {
        val doc = excelLike()
        val r = CellRange(1, 0, 2, 0)
        doc.applyStyle(0, r, StyleChange.Bold(true))
        val s = doc.sheet(0)
        assertTrue(s.cell(1, 0)!!.style.bold)
        assertTrue(s.cell(2, 0)!!.style.bold)
        val idx = s.cell(1, 0)!!.styleIndex
        doc.applyStyle(0, CellRange(3, 0, 3, 0), StyleChange.Bold(true))
        assertEquals(idx, s.cell(3, 0)!!.styleIndex) // deduplicated
        doc.applyStyle(0, r, StyleChange.Fill(0xFF00FF00.toInt()))
        assertEquals(0xFF00FF00.toInt(), s.cell(1, 0)!!.style.fill)
        doc.applyStyle(0, r, StyleChange.Align(HAlign.RIGHT))
        assertEquals(HAlign.RIGHT, s.cell(1, 0)!!.style.hAlign)
        doc.applyStyle(0, CellRange(1, 1, 1, 1), StyleChange.NumberFormat(NumFmtPreset.PERCENT.code))
        assertEquals("1000%", s.cell(1, 1)!!.display)
        doc.applyStyle(0, CellRange(1, 1, 1, 1), StyleChange.NumberFormat(NumFmtPreset.DATE.code))
        assertEquals("10.01.1900", s.cell(1, 1)!!.display)
        doc.applyStyle(0, CellRange(8, 8, 8, 8), StyleChange.Italic(true)) // empty cell gets a style
        assertTrue(s.cell(8, 8)!!.style.italic)
        repeat(7) { doc.undo() }
        assertFalse(s.cell(1, 0)!!.style.bold)
        assertNull(s.cellElement(8, 8))
        repeat(3) { doc.redo() }
        val again = XlsxDocument.open(saved(doc))
        assertTrue(again.sheet(0).cell(1, 0)!!.style.bold)
        assertEquals(0xFF00FF00.toInt(), again.sheet(0).cell(1, 0)!!.style.fill)
    }

    @Test
    fun insertAndDeleteRows() {
        val doc = excelLike()
        doc.insertRows(0, 2, 2) // before row 3
        val s = doc.sheet(0)
        assertEquals("Armud", s.cell(4, 0)!!.display)
        assertEquals("SUM(B2:B6)", s.cell(6, 1)!!.formula)
        assertEquals(XValue.Num(60.5), s.value(6, 1))
        assertEquals("B5*2", s.formulaText(4, 2))
        assertEquals(listOf(CellRange(7, 0, 7, 1)), s.merges)
        assertEquals(30f, s.rowHeightPt(5))
        assertEquals("Data!B7*2", doc.sheet(1).formulaText(0, 0))
        doc.undo()
        assertEquals("Armud", s.cell(2, 0)!!.display)
        assertEquals("SUM(B2:B4)", s.cell(4, 1)!!.formula)
        assertEquals("Data!B5*2", doc.sheet(1).formulaText(0, 0))

        doc.deleteRows(0, 1, 1) // delete row 2 (Alma)
        assertEquals("Armud", s.cell(1, 0)!!.display)
        assertEquals("SUM(B2:B3)", s.cell(3, 1)!!.formula)
        assertEquals(XValue.Num(50.5), s.value(3, 1))
        assertEquals("B2*2", s.formulaText(1, 2))
        val again = XlsxDocument.open(saved(doc))
        assertEquals("SUM(B2:B3)", again.sheet(0).formulaText(3, 1))
        assertEquals("Data!B4*2", again.sheet(1).formulaText(0, 0))
    }

    @Test
    fun insertAndDeleteColumns() {
        val doc = excelLike()
        doc.insertColumns(0, 1)
        val s = doc.sheet(0)
        assertEquals("Ad", s.cell(0, 0)!!.display)
        assertEquals("Məbləğ", s.cell(0, 2)!!.display)
        assertEquals("SUM(C2:C4)", s.formulaText(4, 2))
        assertEquals(listOf(CellRange(5, 0, 5, 2)), s.merges)
        assertTrue(s.colWidthPt(0) > 100f)
        doc.deleteColumns(0, 1)
        assertEquals("SUM(B2:B4)", s.formulaText(4, 1))
        doc.deleteColumns(0, 1) // delete column B: the SUM loses its range
        assertEquals("CUBEVALUE(\"x\")", s.formulaText(4, 1))
        assertEquals("#REF!*2", doc.sheet(1).formulaText(0, 0))
        assertEquals(listOf(CellRange(5, 0, 5, 0)), s.merges)
    }

    @Test
    fun sheetManagement() {
        val doc = excelLike()
        assertTrue(doc.renameSheet(0, "Məlumat"))
        assertEquals("Məlumat!B5*2", doc.sheet(1).formulaText(0, 0))
        assertFalse(doc.renameSheet(1, "məlumat"))
        assertFalse(doc.renameSheet(1, "a/b"))
        val i = doc.addSheet()
        assertEquals(2, i)
        assertEquals("Sheet3", doc.sheets[2].name)
        doc.setCellInput(2, 0, 0, "=Məlumat!B2+1")
        assertEquals(XValue.Num(11.0), doc.sheet(2).value(0, 0))
        val bytes = saved(doc)
        val again = XlsxDocument.open(bytes)
        assertEquals(listOf("Məlumat", "My Sheet", "Sheet3"), again.sheets.map { it.name })
        assertEquals(XValue.Num(11.0), again.sheet(2).value(0, 0))

        assertTrue(doc.deleteSheet(1))
        assertEquals(listOf("Məlumat", "Sheet3"), doc.sheets.map { it.name })
        assertEquals(0, doc.activeSheet)
        val afterDelete = OpcPackage.read(saved(doc))
        assertFalse(afterDelete.exists("xl/worksheets/sheet2.xml"))
        doc.undo()
        assertEquals(listOf("Məlumat", "My Sheet", "Sheet3"), doc.sheets.map { it.name })
        assertEquals(XValue.Num(30.0), doc.sheet(1).value(0, 2))
        doc.undo(); doc.undo(); doc.undo()
        assertEquals(listOf("Data", "My Sheet"), doc.sheets.map { it.name })
        assertEquals("Data!B5*2", doc.sheet(1).formulaText(0, 0))
        // The last visible sheet cannot be deleted.
        val one = XlsxDocument.blank()
        assertFalse(one.deleteSheet(0))
    }

    @Test
    fun blankWorkbook() {
        val doc = XlsxDocument.blank()
        doc.setCellInput(0, 0, 0, "2")
        doc.setCellInput(0, 1, 0, "3")
        doc.setCellInput(0, 2, 0, "=sum(a1:a2;10)")
        assertEquals("=SUM(A1:A2,10)", doc.sheet(0).inputText(2, 0))
        assertEquals(XValue.Num(15.0), doc.sheet(0).value(2, 0))
        val again = XlsxDocument.open(saved(doc))
        assertEquals("15", again.sheet(0).cell(2, 0)!!.display)
    }

    @Test
    fun csv() {
        val doc = XlsxDocument.fromCsv("ad;qiymət;qeyd\r\nalma;1,5;\"bir; iki\"\nnar;2;\"x \"\"y\"\"\"\n007;;\n")
        val s = doc.sheet(0)
        assertEquals("qiymət", s.cell(0, 1)!!.display)
        assertEquals(XValue.Num(1.5), s.value(1, 1))
        assertEquals("bir; iki", s.cell(1, 2)!!.display)
        assertEquals("x \"y\"", s.cell(2, 2)!!.display)
        assertEquals(XValue.Str("007"), s.value(3, 0))
        assertEquals("ad,qiymət,qeyd\r\nalma,1.5,bir; iki\r\nnar,2,\"x \"\"y\"\"\"\r\n007,,\r\n", doc.sheetToCsv(0))
        val again = XlsxDocument.open(saved(doc))
        assertEquals("alma", again.sheet(0).cell(1, 0)!!.display)
    }

    @Test
    fun numberFormats() {
        assertEquals("1,234.57", NumberFormat.format(1234.567, "#,##0.00"))
        assertEquals("-1,235", NumberFormat.format(-1234.5, "#,##0"))
        assertEquals("(1,235)", NumberFormat.format(-1234.5, "#,##0 ;(#,##0)"))
        assertEquals("12.5%", NumberFormat.format(0.125, "0.0%"))
        assertEquals("0.50", NumberFormat.format(0.5, "0.00"))
        assertEquals(".5", NumberFormat.format(0.5, "#.#"))
        assertEquals("1.23E+05", NumberFormat.format(123456.0, "0.00E+00"))
        assertEquals("$5.00", NumberFormat.format(5.0, "\"$\"0.00"))
        assertEquals("5 ₼", NumberFormat.format(5.0, "0 [\$₼-42C]"))
        assertEquals("0.1", NumberFormat.format(0.1, "General"))
        assertEquals("12345678901", NumberFormat.format(12345678901.0, "General"))
        assertEquals("1.23457E+11", NumberFormat.format(123456789012.0, "General"))
        assertEquals("0.333333333", NumberFormat.format(1.0 / 3, "General"))
        assertEquals("2.68", NumberFormat.format(2.675, "0.00"))
        // Dates and times (45658 = 2025-01-01).
        assertEquals("01.01.2025", NumberFormat.format(45658.0, "dd.mm.yyyy"))
        assertEquals("1-Jan-25", NumberFormat.format(45658.0, "d-mmm-yy"))
        assertEquals("January 2025", NumberFormat.format(45658.0, "mmmm yyyy"))
        assertEquals("Wednesday", NumberFormat.format(45658.0, "dddd"))
        assertEquals("18:30", NumberFormat.format(45658.0 + 18.5 / 24, "hh:mm"))
        assertEquals("6:30 PM", NumberFormat.format(45658.0 + 18.5 / 24, "h:mm AM/PM"))
        assertEquals("30:00:00", NumberFormat.format(1.25, "[h]:mm:ss"))
        assertEquals("05:07", NumberFormat.format((5 * 60 + 7) / 86400.0, "mm:ss"))
        assertEquals("1/2/1900", NumberFormat.format(2.0, "m/d/yyyy"))
        assertEquals("2 1/2", NumberFormat.format(2.5, "# ?/?"))
    }
}
