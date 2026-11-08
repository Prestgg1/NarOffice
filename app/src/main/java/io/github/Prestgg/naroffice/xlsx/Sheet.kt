// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

import io.github.Prestgg.naroffice.xml.XDocument
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XText
import java.util.TreeMap

class CellView(
    val row: Int,
    val col: Int,
    val value: XValue,
    val display: String,
    val formula: String?,
    val style: CellStyle,
    val styleIndex: Int,
) {
    /** Alignment to use on screen: General aligns numbers right and booleans/errors centre. */
    val effectiveAlign: HAlign
        get() = if (style.hAlign != HAlign.GENERAL) style.hAlign else when (value) {
            is XValue.Num -> HAlign.RIGHT
            is XValue.Bool, is XValue.Err -> HAlign.CENTER
            else -> HAlign.LEFT
        }
}

/** One worksheet. Cells are indexed sparsely and keep their XML elements for round-trips. */
class Sheet internal constructor(val doc: XlsxDocument, val part: String, internal val xdoc: XDocument) {

    internal val root: XElement get() = xdoc.root
    internal val px: String = root.name.let { if (it.contains(':')) it.substringBefore(':') + ":" else "" }

    internal val rows = TreeMap<Int, XElement>()
    internal val cells = HashMap<Long, XElement>()
    internal var dirty = false

    var maxRow = -1
        private set
    var maxCol = -1
        private set

    /** Bumped when the cell index is rebuilt; lets the UI drop caches. */
    var generation = 0
        private set

    init {
        reindex()
    }

    internal val sheetData: XElement
        get() = root.childLocal("sheetData") ?: XElement(px + "sheetData").also { sd ->
            // sheetData follows sheetFormatPr/cols and precedes everything else.
            val before = setOf("sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols")
            var idx = 0
            for ((i, c) in root.children.withIndex()) if (c is XElement && c.localName in before) idx = i + 1
            root.children.add(idx, sd)
        }

    /** Rebuilds the row and cell index from the XML, normalizing implicit r attributes. */
    internal fun reindex() {
        rows.clear()
        cells.clear()
        var mr = -1
        var mc = -1
        var nextRow = 0
        val sd = root.childLocal("sheetData")
        if (sd != null) for (rowEl in sd.children) {
            if (rowEl !is XElement || rowEl.localName != "row") continue
            val r = rowEl["r"]?.toIntOrNull()?.minus(1) ?: nextRow.also { rowEl["r"] = (it + 1).toString() }
            nextRow = r + 1
            rows[r] = rowEl
            var nextCol = 0
            for (c in rowEl.children) {
                if (c !is XElement || c.localName != "c") continue
                val col = c["r"]?.let { CellRef.parse(it)?.second } ?: nextCol.also { c["r"] = CellRef.name(r, it) }
                nextCol = col + 1
                cells[CellRef.key(r, col)] = c
                if (hasContent(c)) {
                    if (r > mr) mr = r
                    if (col > mc) mc = col
                }
            }
        }
        maxRow = mr
        maxCol = mc
        sharedFormulas = null
        generation++
    }

    private fun hasContent(c: XElement) = c.children.any { it is XElement }

    // ------------------------------------------------------------ values

    fun cellElement(row: Int, col: Int): XElement? = cells[CellRef.key(row, col)]

    internal fun rawValue(c: XElement): XValue {
        val t = c["t"]
        val v = c.childLocal("v")?.textContent
        return when (t) {
            "s" -> v?.toIntOrNull()?.let { doc.sharedString(it) }?.let { XValue.Str(it) } ?: XValue.Empty
            "inlineStr" -> XValue.Str(c.childLocal("is")?.let(XlsxDocument::richText) ?: v ?: "")
            "str" -> XValue.Str(v ?: "")
            "b" -> XValue.Bool(v == "1" || v == "true")
            "e" -> XValue.Err(v ?: "#N/A")
            "d" -> XValue.Str(v ?: "")
            else -> v?.trim()?.toDoubleOrNull()?.let { XValue.Num(it) } ?: XValue.Empty
        }
    }

    fun value(row: Int, col: Int): XValue = cellElement(row, col)?.let(::rawValue) ?: XValue.Empty

    fun styleIndex(row: Int, col: Int): Int = cellElement(row, col)?.get("s")?.toIntOrNull() ?: 0

    fun cell(row: Int, col: Int): CellView? {
        val c = cellElement(row, col) ?: return null
        val s = c["s"]?.toIntOrNull() ?: 0
        val style = doc.styles.style(s)
        val v = rawValue(c)
        return CellView(row, col, v, doc.display(v, style), formulaText(row, col), style, s)
    }

    /** Text to put in the editor for a cell: "=formula", or the raw value. */
    fun inputText(row: Int, col: Int): String {
        formulaText(row, col)?.let { return "=$it" }
        val c = cellElement(row, col) ?: return ""
        return when (val v = rawValue(c)) {
            is XValue.Num -> {
                val st = doc.styles.style(c["s"]?.toIntOrNull() ?: 0)
                when {
                    st.isDate -> NumberFormat.format(v.v, if (v.v != Math.floor(v.v)) "dd.mm.yyyy hh:mm" else "dd.mm.yyyy", doc.date1904)
                    st.numFmt.contains('%') -> Formula.numText(v.v * 100) + "%"
                    else -> Formula.numText(v.v)
                }
            }
            is XValue.Str -> v.s
            is XValue.Bool -> if (v.b) "TRUE" else "FALSE"
            is XValue.Err -> v.code
            XValue.Empty -> ""
        }
    }

    // ------------------------------------------------------------ formulas

    private class SharedMaster(val row: Int, val col: Int, val text: String)

    private var sharedFormulas: HashMap<String, SharedMaster>? = null

    private fun shared(): HashMap<String, SharedMaster> {
        sharedFormulas?.let { return it }
        val m = HashMap<String, SharedMaster>()
        for ((k, c) in cells) {
            val f = c.childLocal("f") ?: continue
            if (f["t"] == "shared" && f["ref"] != null) {
                val si = f["si"] ?: continue
                val text = f.textContent
                if (text.isNotEmpty()) m[si] = SharedMaster(CellRef.keyRow(k), CellRef.keyCol(k), text)
            }
        }
        sharedFormulas = m
        return m
    }

    /** Formula text without '=' (shared formulas expanded), or null. */
    fun formulaText(row: Int, col: Int): String? {
        val c = cellElement(row, col) ?: return null
        val f = c.childLocal("f") ?: return null
        val text = f.textContent
        if (f["t"] == "shared") {
            if (text.isNotEmpty() && f["ref"] != null) return text
            val m = shared()[f["si"] ?: return null] ?: return text.ifEmpty { null }
            return Formula.translate(m.text, row - m.row, col - m.col)
        }
        return text.ifEmpty { null }
    }

    /** Whether the cell's formula is evaluated by us (others keep the value stored in the file). */
    fun isComputed(c: XElement): Boolean {
        val f = c.childLocal("f") ?: return false
        if (f["t"] == "array" || f["t"] == "dataTable") return false
        return true
    }

    /** Turns shared formulas into ordinary ones so that cells can change independently. */
    internal fun unshare() {
        val m = shared()
        if (m.isEmpty() && cells.values.none { it.childLocal("f")?.get("t") == "shared" }) return
        for ((k, c) in cells) {
            val f = c.childLocal("f") ?: continue
            if (f["t"] != "shared") continue
            val text = formulaText(CellRef.keyRow(k), CellRef.keyCol(k)) ?: continue
            f.attrs.remove("t"); f.attrs.remove("ref"); f.attrs.remove("si")
            f.setText(text)
        }
        sharedFormulas = null
        dirty = true
    }

    /** Writes a computed value into a formula cell. Returns true when it changed. */
    internal fun writeComputed(c: XElement, v: XValue): Boolean {
        val old = rawValue(c)
        if (old == v) return false
        val (t, text) = when (v) {
            is XValue.Num -> null to Formula.numText(v.v).let { if (it.contains('E')) v.v.toString() else it }
            is XValue.Str -> "str" to v.s
            is XValue.Bool -> "b" to (if (v.b) "1" else "0")
            is XValue.Err -> "e" to v.code
            XValue.Empty -> null to null
        }
        c["t"] = t
        c.children.removeAll { it is XElement && (it.localName == "v" || it.localName == "is") }
        if (text != null) {
            val ve = XElement(px + "v")
            ve.children.add(XText(text))
            val fIdx = c.children.indexOfFirst { it is XElement && it.localName == "f" }
            c.children.add(fIdx + 1, ve)
        }
        dirty = true
        return true
    }

    // ------------------------------------------------------------ cell placement

    /** Puts (or with null removes) the element for a cell, creating its row when needed. */
    internal fun putCell(row: Int, col: Int, el: XElement?) {
        val key = CellRef.key(row, col)
        var rowEl = rows[row]
        if (rowEl == null) {
            if (el == null) return
            rowEl = XElement(px + "row").also { it["r"] = (row + 1).toString() }
            val sd = sheetData
            val next = rows.higherEntry(row)?.value
            val idx = if (next != null) sd.children.indexOfFirst { it === next } else -1
            if (idx >= 0) sd.children.add(idx, rowEl) else sd.children.add(rowEl)
            rows[row] = rowEl
        }
        rowEl.attrs.remove("spans")
        val old = cells.remove(key)
        if (old != null) rowEl.children.removeAll { it === old }
        if (el != null) {
            el["r"] = CellRef.name(row, col)
            var idx = rowEl.children.size
            for ((i, ch) in rowEl.children.withIndex()) {
                if (ch is XElement && ch.localName == "c") {
                    val cc = ch["r"]?.let { CellRef.parse(it)?.second } ?: continue
                    if (cc > col) { idx = i; break }
                }
            }
            // Keep extLst last.
            if (idx == rowEl.children.size) {
                val ext = rowEl.children.indexOfFirst { it is XElement && it.localName == "extLst" }
                if (ext >= 0) idx = ext
            }
            rowEl.children.add(idx, el)
            cells[key] = el
            if (hasContent(el)) {
                if (row > maxRow) maxRow = row
                if (col > maxCol) maxCol = col
            }
        } else if (rowEl.children.none { it is XElement && it.localName == "c" } &&
            rowEl.attrs.keys.all { it == "r" }
        ) {
            sheetData.children.removeAll { it === rowEl }
            rows.remove(row)
        }
        if (old != null && (row == maxRow || col == maxCol)) recomputeExtent()
        if (el?.childLocal("f") != null || old?.childLocal("f") != null) sharedFormulas = null
        dirty = true
    }

    private fun recomputeExtent() {
        var mr = -1
        var mc = -1
        for ((k, c) in cells) if (hasContent(c)) {
            val r = CellRef.keyRow(k); val cc = CellRef.keyCol(k)
            if (r > mr) mr = r
            if (cc > mc) mc = cc
        }
        maxRow = mr; maxCol = mc
    }

    /** Default style for a new cell: the row's, else the column's. */
    internal fun inheritedStyle(row: Int, col: Int): String? {
        rows[row]?.let { r -> if (r["customFormat"] == "1" || r["customFormat"] == "true") r["s"]?.let { return it } }
        root.childLocal("cols")?.elements?.forEach { c ->
            val mn = c["min"]?.toIntOrNull() ?: return@forEach
            val mx = c["max"]?.toIntOrNull() ?: mn
            if (col + 1 in mn..mx) return c["style"]?.takeIf { it != "0" }
        }
        return null
    }

    // ------------------------------------------------------------ layout

    private val formatPr get() = root.childLocal("sheetFormatPr")

    val defaultRowHeightPt: Float get() = formatPr?.get("defaultRowHeight")?.toFloatOrNull() ?: 15f

    val defaultColWidthPt: Float
        get() {
            formatPr?.get("defaultColWidth")?.toFloatOrNull()?.let { return widthToPt(it) }
            formatPr?.get("baseColWidth")?.toFloatOrNull()?.let { return ((it * 7 + 5) * 0.75f).coerceAtLeast(8f) + 0f }
            return 48f
        }

    private fun widthToPt(w: Float) = (w * 7f).let { Math.round(it) * 0.75f }

    private var colCache: Pair<Int, List<Triple<Int, Int, XElement>>>? = null

    private fun colDefs(): List<Triple<Int, Int, XElement>> {
        val cols = root.childLocal("cols")
        val key = System.identityHashCode(cols) * 31 + (cols?.children?.size ?: 0) + generation * 7
        colCache?.let { if (it.first == key) return it.second }
        val list = cols?.elements?.filter { it.localName == "col" }?.mapNotNull { c ->
            val mn = c["min"]?.toIntOrNull() ?: return@mapNotNull null
            Triple(mn - 1, (c["max"]?.toIntOrNull() ?: mn) - 1, c)
        }?.toList() ?: emptyList()
        colCache = key to list
        return list
    }

    private fun colDef(col: Int): XElement? = colDefs().firstOrNull { col in it.first..it.second }?.third

    fun isColHidden(col: Int): Boolean = colDef(col)?.get("hidden").let { it == "1" || it == "true" }

    fun colWidthPt(col: Int): Float {
        val d = colDef(col) ?: return defaultColWidthPt
        if (d["hidden"] == "1" || d["hidden"] == "true") return 0f
        return d["width"]?.toFloatOrNull()?.let(::widthToPt) ?: defaultColWidthPt
    }

    fun isRowHidden(row: Int): Boolean = rows[row]?.get("hidden").let { it == "1" || it == "true" }

    fun rowHeightPt(row: Int): Float {
        val r = rows[row] ?: return defaultRowHeightPt
        if (r["hidden"] == "1" || r["hidden"] == "true") return 0f
        return r["ht"]?.toFloatOrNull() ?: defaultRowHeightPt
    }

    val merges: List<CellRange>
        get() = root.childLocal("mergeCells")?.elements?.mapNotNull { m -> m["ref"]?.let { CellRange.parse(it) } }?.toList()
            ?: emptyList()

    /** The merged range that starts at or covers (row, col), if any. */
    fun mergeAt(row: Int, col: Int): CellRange? = merges.firstOrNull { it.contains(row, col) }

    private val pane: XElement? get() = root.childLocal("sheetViews")?.childLocal("sheetView")?.childLocal("pane")

    val frozenRows: Int
        get() = pane?.takeIf { it["state"] == "frozen" || it["state"] == "frozenSplit" }?.get("ySplit")
            ?.toDoubleOrNull()?.toInt() ?: 0

    val frozenCols: Int
        get() = pane?.takeIf { it["state"] == "frozen" || it["state"] == "frozenSplit" }?.get("xSplit")
            ?.toDoubleOrNull()?.toInt() ?: 0

    val showGridLines: Boolean
        get() = root.childLocal("sheetViews")?.childLocal("sheetView")?.get("showGridLines") != "0"

    /** Updates the dimension element before saving. */
    internal fun updateDimension() {
        val dim = root.childLocal("dimension") ?: return
        dim["ref"] = if (maxRow < 0) "A1" else CellRange(0, 0, maxRow.coerceAtLeast(0), maxCol.coerceAtLeast(0)).toString()
    }
}
