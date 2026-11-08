// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import io.github.Prestgg.naroffice.xlsx.EvalContext
import io.github.Prestgg.naroffice.xlsx.Formula
import io.github.Prestgg.naroffice.xlsx.XValue
import io.github.Prestgg.naroffice.xlsx.XlsxDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTest {

    /** Two sheets: "S1" with A1..A5 = 1..5, B1..B5 = a,b,c,d,e; "Other Sheet" with A1 = 100. */
    private val ctx = object : EvalContext {
        override fun sheetIndex(name: String?) = when {
            name == null -> 0
            name.equals("S1", true) -> 0
            name.equals("Other Sheet", true) -> 1
            else -> -1
        }

        override fun value(sheet: Int, row: Int, col: Int): XValue = when {
            sheet == 1 -> if (row == 0 && col == 0) XValue.Num(100.0) else XValue.Empty
            col == 0 && row < 5 -> XValue.Num(row + 1.0)
            col == 1 && row < 5 -> XValue.Str(('a' + row).toString())
            col == 2 && row == 0 -> XValue.Str("12")
            col == 2 && row == 1 -> XValue.Bool(true)
            else -> XValue.Empty
        }

        override fun extent(sheet: Int) = 4 to 2
        override val date1904 = false
    }

    private fun ev(f: String) = Formula.evaluate(f, 0, ctx)
    private fun num(f: String) = (ev(f) as? XValue.Num)?.v ?: error("$f → ${ev(f)}")

    @Test
    fun precedence() {
        assertEquals(7.0, num("1+2*3"), 0.0)
        assertEquals(9.0, num("(1+2)*3"), 0.0)
        assertEquals(4.0, num("-2^2"), 0.0) // unary minus binds tighter, as in Excel
        assertEquals(64.0, num("2^3^2"), 0.0) // left-associative
        assertEquals(0.5, num("50%"), 0.0)
        assertEquals(XValue.Str("37"), ev("3&4+3"))
        assertEquals(XValue.Bool(true), ev("1+1=2"))
        assertEquals(XValue.Bool(true), ev("\"abc\"=\"ABC\""))
        assertEquals(XValue.Bool(true), ev("\"a\">5")) // text > numbers
        assertEquals(XValue.DIV0, ev("1/0"))
        assertEquals(XValue.VALUE, ev("\"x\"+1"))
        assertEquals(3.0, num("\"1\"+2"), 0.0)
        assertEquals(XValue.Str("say \"hi\""), ev("\"say \"\"hi\"\"\""))
    }

    @Test
    fun references() {
        assertEquals(15.0, num("SUM(A1:A5)"), 0.0)
        assertEquals(15.0, num("SUM(A:A)"), 0.0)
        assertEquals(15.0, num("SUM(\$A\$1:A5)"), 0.0)
        assertEquals(101.0, num("'Other Sheet'!A1+A1"), 0.0)
        assertEquals(6.0, num("S1!A1+S1!A5"), 0.0)
        assertEquals(XValue.REF, ev("Missing!A1"))
        assertEquals(0.0, num("D9"), 0.0) // empty cell → 0
        assertEquals(XValue.VALUE, ev("A1:A2+1"))
        assertEquals(XValue.NAME, ev("foo+1"))
    }

    @Test
    fun functions() {
        assertEquals(3.0, num("AVERAGE(A1:A5)"), 0.0)
        assertEquals(1.0, num("MIN(A1:A5)"), 0.0)
        assertEquals(5.0, num("MAX(A1:A5,2)"), 0.0)
        assertEquals(5.0, num("COUNT(A1:C5)"), 0.0)
        assertEquals(12.0, num("COUNTA(A1:C5)"), 0.0)
        assertEquals(3.0, num("COUNTBLANK(C1:C5)"), 0.0)
        assertEquals(120.0, num("PRODUCT(A1:A5)"), 0.0)
        assertEquals(XValue.Str("yes"), ev("IF(A2>1,\"yes\",\"no\")"))
        assertEquals(XValue.Bool(false), ev("IF(A1>1,\"yes\")"))
        assertEquals(XValue.Str("err"), ev("IFERROR(1/0,\"err\")"))
        assertEquals(XValue.Bool(true), ev("AND(A1=1,OR(FALSE,A2=2))"))
        assertEquals(XValue.Bool(false), ev("NOT(TRUE)"))
        assertEquals(2.68, num("ROUND(2.675,2)"), 1e-12)
        assertEquals(-3.0, num("ROUND(-2.5,0)"), 0.0)
        assertEquals(1230.0, num("ROUND(1234.5,-1)"), 0.0)
        assertEquals(3.2, num("ROUNDUP(3.11,1)"), 1e-12)
        assertEquals(3.1, num("ROUNDDOWN(3.19,1)"), 1e-12)
        assertEquals(-4.0, num("INT(-3.5)"), 0.0)
        assertEquals(2.0, num("MOD(-1,3)"), 0.0)
        assertEquals(3.0, num("SQRT(9)"), 0.0)
        assertEquals(8.0, num("POWER(2,3)"), 0.0)
        assertEquals(5.0, num("ABS(-5)"), 0.0)
        assertEquals(XValue.Str("Hel"), ev("LEFT(\"Hello\",3)"))
        assertEquals(XValue.Str("lo"), ev("RIGHT(\"Hello\",2)"))
        assertEquals(XValue.Str("ell"), ev("MID(\"Hello\",2,3)"))
        assertEquals(5.0, num("LEN(\"Hello\")"), 0.0)
        assertEquals(XValue.Str("ABC"), ev("UPPER(\"abc\")"))
        assertEquals(XValue.Str("a b"), ev("TRIM(\"  a   b \")"))
        assertEquals(XValue.Str("a-1"), ev("CONCATENATE(B1,\"-\",A1)"))
        assertEquals(XValue.Str("abcde"), ev("_xlfn.CONCAT(B1:B5)"))
        assertEquals(XValue.Str("1,234.50"), ev("TEXT(1234.5,\"#,##0.00\")"))
        assertEquals(XValue.Str("01.01.2025"), ev("TEXT(DATE(2025,1,1),\"dd.mm.yyyy\")"))
        assertEquals(12.0, num("VALUE(C1)"), 0.0)
        assertEquals(45658.0, num("DATE(2025,1,1)"), 0.0)
        assertEquals(45689.0, num("DATE(2024,14,1)"), 0.0) // month overflow → Feb 2025
        assertEquals(2025.0, num("YEAR(45658)"), 0.0)
        assertEquals(1.0, num("MONTH(45658)"), 0.0)
        assertEquals(1.0, num("DAY(45658)"), 0.0)
        assertEquals(Math.PI, num("PI()"), 0.0)
        val r = num("RAND()")
        assertTrue(r >= 0 && r < 1)
        assertTrue(num("TODAY()") > 45000)
    }

    @Test
    fun conditionalAndLookup() {
        assertEquals(12.0, num("SUMIF(A1:A5,\">2\")"), 0.0)
        assertEquals(12.0, num("SUMIF(B1:B5,\"<>c\",A1:A5)"), 0.0)
        assertEquals(2.0, num("COUNTIF(A1:A5,\"<=2\")"), 0.0)
        assertEquals(1.0, num("COUNTIF(B1:B5,\"c\")"), 0.0)
        assertEquals(5.0, num("COUNTIF(B1:B5,\"?\")"), 0.0)
        assertEquals(4.5, num("AVERAGEIF(A1:A5,\">3\")"), 0.0)
        assertEquals(XValue.Str("c"), ev("VLOOKUP(3,A1:B5,2,FALSE)"))
        assertEquals(XValue.Str("c"), ev("VLOOKUP(3.7,A1:B5,2)")) // approximate match
        assertEquals(XValue.NA, ev("VLOOKUP(9,A1:B5,2,FALSE)"))
        assertEquals(XValue.REF, ev("VLOOKUP(1,A1:B5,3,FALSE)"))
        assertEquals(4.0, num("MATCH(\"d\",B1:B5,0)"), 0.0)
        assertEquals(3.0, num("MATCH(3.5,A1:A5)"), 0.0)
        assertEquals(XValue.Str("e"), ev("INDEX(B1:B5,5)"))
        assertEquals(XValue.Str("b"), ev("INDEX(A1:B5,2,2)"))
        assertEquals(4.0, num("INDEX(A1:A5,MATCH(\"d\",B1:B5,0))"), 0.0)
        assertEquals(XValue.Num(2.0), ev("HLOOKUP(1,A1:B2,2,FALSE)"))
    }

    @Test
    fun rewriting() {
        assertEquals("B3*2", Formula.translate("B2*2", 1, 0))
        assertEquals("\$B\$2+C3", Formula.translate("\$B\$2+B2", 1, 1))
        assertEquals("SUM(A1:A7)+A9", Formula.shift("SUM(A1:A5)+A7", true, 2, 2) { it == null })
        assertEquals("SUM(A1:A3)+#REF!", Formula.shift("SUM(A1:A5)+A4", true, 3, -2) { it == null })
        assertEquals("SUM(#REF!)", Formula.shift("SUM(A4:A5)", true, 3, -2) { it == null })
        assertEquals("SUM(B1:B5)", Formula.shift("SUM(A1:A5)", false, 0, 1) { it == null })
        assertEquals("'S 2'!A1+A1", Formula.shift("'S 2'!A1+A1", true, 0, 1) { it != null && it == "S 1" })
        assertEquals("'New Name'!A1+\"Old!A1\"", Formula.renameSheet("Old!A1+\"Old!A1\"", "Old", "New Name"))
        assertEquals("SUM(A1:B2,3)", Formula.normalizeInput("sum(a1:b2;3)"))
        // Text that is not a reference stays as written.
        assertEquals("LOG10(A1)  +  \"A1\"", Formula.shift("LOG10(A1)  +  \"A1\"", true, 5, 1) { it == null })
    }

    @Test
    fun unsupportedFormulasAreDetected() {
        assertTrue(Formula.parse("SUM(A1:A3)*2").supported)
        assertFalse(Formula.parse("CUBEVALUE(\"x\")").supported)
        assertFalse(Formula.parse("Table1[Col]").supported)
        assertFalse(Formula.parse("[1]Sheet1!A1").supported)
        assertFalse(Formula.parse("SUM(").supported)
        assertFalse(Formula.parse("MyName*2").supported)
    }

    @Test
    fun circularReferenceDoesNotHang() {
        val doc = XlsxDocument.blank()
        doc.setCellInput(0, 0, 0, "=B1+1")
        doc.setCellInput(0, 0, 1, "=A1+1")
        val a = doc.sheet(0).value(0, 0)
        assertTrue(a.toString(), a is XValue.Err || a is XValue.Num)
    }

    @Test
    fun largeSheetPerformance() {
        val doc = XlsxDocument.blank()
        val sh = doc.sheet(0)
        // Build 50k cells directly, then 3000 dependent formulas.
        val sb = StringBuilder()
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        for (r in 0 until 5000) {
            sb.append("<row r=\"${r + 1}\">")
            for (c in 0 until 10) sb.append("<c r=\"${io.github.Prestgg.naroffice.xlsx.CellRef.name(r, c)}\"><v>${r + c}</v></c>")
            if (r < 3000) sb.append("<c r=\"K${r + 1}\"><f>${if (r == 0) "A1" else "K$r+A${r + 1}"}</f><v>0</v></c>")
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        val t0 = System.nanoTime()
        val big = io.github.Prestgg.naroffice.xml.Xml.parse(sb.toString())
        sh.root.children.clear()
        sh.root.children.addAll(big.root.children)
        sh.reindex()
        val tParse = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        doc.setCellInput(0, 0, 0, "1")
        val tEdit = (System.nanoTime() - t1) / 1e6
        assertEquals(XValue.Num(1.0 + (1 until 3000).sum()), sh.value(2999, 10))
        assertTrue("parse took $tParse ms", tParse < 3000)
        assertTrue("edit+recalc took $tEdit ms", tEdit < 3000)
    }
}
