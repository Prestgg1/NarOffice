// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

import io.github.Prestgg.naroffice.core.History
import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.ooxml.OfficeFormatException
import io.github.Prestgg.naroffice.ooxml.OpcPackage
import io.github.Prestgg.naroffice.xml.XDocument
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XText
import io.github.Prestgg.naroffice.xml.Xml
import java.io.OutputStream
import java.time.LocalDate

class SheetInfo(val name: String, val part: String, val state: String, val relId: String, internal val el: XElement) {
    val hidden: Boolean get() = state != "visible"
}

class XlsxDocument(val pkg: OpcPackage) {

    val workbookPart: String = pkg.mainPart() ?: throw OfficeFormatException("No workbook part")
    private val wbDoc: XDocument = pkg.xml(workbookPart) ?: throw OfficeFormatException("Missing $workbookPart")
    private val wb: XElement get() = wbDoc.root
    private val px: String = wb.name.let { if (it.contains(':')) it.substringBefore(':') + ":" else "" }

    val theme = Colors.Theme(pkg.targetsOfType(workbookPart, "/theme").firstOrNull()?.let { pkg.xml(it)?.root })

    private val stylesPart: String = pkg.targetsOfType(workbookPart, "/styles").firstOrNull() ?: run {
        val part = "xl/styles.xml"
        pkg.putXml(part, Xml.parse(BLANK_STYLES), CT_STYLES)
        pkg.addRel(workbookPart, REL_STYLES, relTarget(part))
        part
    }
    val styles = XlsxStyles(pkg.xml(stylesPart)!!.root, theme)

    private val sharedStrings: List<String> by lazy {
        val part = pkg.targetsOfType(workbookPart, "/sharedStrings").firstOrNull()
        pkg.xml(part ?: "")?.root?.elements?.filter { it.localName == "si" }?.map(::richText)?.toList() ?: emptyList()
    }

    val date1904: Boolean
        get() = wb.childLocal("workbookPr")?.get("date1904").let { it == "1" || it == "true" }

    val history = History()

    var version = 0
        private set

    val isModified get() = history.changeCounter != 0

    private val sheetCache = HashMap<String, Sheet>()

    internal fun sharedString(i: Int): String? = sharedStrings.getOrNull(i)

    // ---------------------------------------------------------------- sheets

    private val sheetsEl: XElement
        get() = wb.childLocal("sheets") ?: throw OfficeFormatException("Workbook has no sheets")

    val sheets: List<SheetInfo>
        get() = sheetsEl.elements.filter { it.localName == "sheet" }.mapNotNull { s ->
            val rid = s["r:id"] ?: s.attrLocal("id") ?: return@mapNotNull null
            val part = pkg.target(workbookPart, rid) ?: return@mapNotNull null
            SheetInfo(s["name"] ?: "?", part, s["state"] ?: "visible", rid, s)
        }.toList()

    val sheetCount: Int get() = sheets.size

    fun sheet(index: Int): Sheet {
        val info = sheets[index]
        val x = pkg.xml(info.part) ?: throw OfficeFormatException("Missing ${info.part}")
        sheetCache[info.part]?.let { if (it.xdoc === x) return it }
        return Sheet(this, info.part, x).also { sheetCache[info.part] = it }
    }

    fun sheetIndex(name: String): Int = sheets.indexOfFirst { it.name.equals(name, true) }

    /** Index of the sheet Excel opens with. */
    val activeSheet: Int
        get() = wb.childLocal("bookViews")?.childLocal("workbookView")?.get("activeTab")?.toIntOrNull()
            ?.coerceIn(0, (sheetCount - 1).coerceAtLeast(0)) ?: 0

    fun display(v: XValue, style: CellStyle): String = when (v) {
        is XValue.Num -> NumberFormat.format(v.v, style.numFmt, date1904)
        is XValue.Str -> v.s
        is XValue.Bool -> if (v.b) "TRUE" else "FALSE"
        is XValue.Err -> v.code
        XValue.Empty -> ""
    }

    // ---------------------------------------------------------------- calculation

    /** Recomputes every formula we can evaluate; others keep their stored values. */
    fun recalculate() {
        val infos = sheets
        val loaded = infos.indices.map { sheet(it) }
        val memo = HashMap<Long, XValue>()
        val visiting = HashSet<Long>()
        val ctx = object : EvalContext {
            override fun sheetIndex(name: String?): Int =
                if (name == null) -1 else infos.indexOfFirst { it.name.equals(name, true) }

            override fun value(sheet: Int, row: Int, col: Int): XValue {
                val sh = loaded.getOrNull(sheet) ?: return XValue.REF
                val el = sh.cellElement(row, col) ?: return XValue.Empty
                if (!sh.isComputed(el)) return sh.rawValue(el)
                val text = sh.formulaText(row, col) ?: return sh.rawValue(el)
                val parsed = Formula.parse(text)
                if (!parsed.supported || parsed.expr == null) return sh.rawValue(el)
                val key = (sheet.toLong() shl 42) or CellRef.key(row, col)
                memo[key]?.let { return it }
                if (!visiting.add(key)) return XValue.REF
                val v = Formula.evaluate(parsed.expr, sheet, this)
                visiting.remove(key)
                memo[key] = v
                return v
            }

            override fun extent(sheet: Int): Pair<Int, Int> {
                val sh = loaded.getOrNull(sheet) ?: return 0 to 0
                return sh.maxRow.coerceAtLeast(0) to sh.maxCol.coerceAtLeast(0)
            }

            override val date1904: Boolean get() = this@XlsxDocument.date1904
        }
        for ((si, sh) in loaded.withIndex()) {
            val keys = sh.cells.entries.filter { it.value.childLocal("f") != null }.map { it.key }.sorted()
            for (k in keys) {
                val r = CellRef.keyRow(k)
                val c = CellRef.keyCol(k)
                val el = sh.cellElement(r, c) ?: continue
                if (!sh.isComputed(el)) continue
                val text = sh.formulaText(r, c) ?: continue
                if (!Formula.parse(text).supported) continue
                sh.writeComputed(el, ctx.value(si, r, c))
            }
        }
    }

    private fun afterChange() {
        recalculate()
        version++
    }

    private fun record(steps: List<History.Step>) {
        if (steps.isEmpty()) return
        history.push(if (steps.size == 1) steps[0] else History.Group(steps))
        afterChange()
    }

    fun undo(): Boolean = history.undo().also { if (it) afterChange() }
    fun redo(): Boolean = history.redo().also { if (it) afterChange() }

    private class CellStep(val sheet: Sheet, val r: Int, val c: Int, val old: XElement?, val new: XElement?) : History.Step {
        override fun undo() = sheet.putCell(r, c, old)
        override fun redo() = sheet.putCell(r, c, new)
    }

    private fun cellStep(sh: Sheet, r: Int, c: Int, new: XElement?): History.Step {
        val step = CellStep(sh, r, c, sh.cellElement(r, c), new)
        step.redo()
        return step
    }

    // ---------------------------------------------------------------- cell editing

    /**
     * Sets a cell from what the user typed: "" clears, "=…" is a formula, numbers (1.5, 1,5,
     * 15%), dates (31.12.2024, 2024-12-31), TRUE/FALSE, otherwise text ('… forces text).
     */
    fun setCellInput(sheetIndex: Int, row: Int, col: Int, input: String) {
        val sh = sheet(sheetIndex)
        sh.unshare()
        val old = sh.cellElement(row, col)
        var s = old?.get("s") ?: sh.inheritedStyle(row, col)
        val styleIdx = s?.toIntOrNull() ?: 0
        val style = styles.style(styleIdx)
        val p = sh.px
        fun cellWith(t: String?, vText: String?): XElement {
            val c = XElement(p + "c")
            c["t"] = t
            if (vText != null) c.children.add(XElement(p + "v").also { it.children.add(XText(vText)) })
            return c
        }

        val trimmed = input.trim()
        val new: XElement? = when {
            input.isEmpty() -> if (s != null && s != "0") XElement(p + "c") else null
            input.startsWith("=") && input.length > 1 -> {
                val c = XElement(p + "c")
                c.children.add(XElement(p + "f").also { it.children.add(XText(Formula.normalizeInput(input.substring(1)))) })
                c
            }
            input.startsWith("'") -> inlineString(p, input.substring(1))
            trimmed.equals("TRUE", true) || trimmed.equals("FALSE", true) ->
                cellWith("b", if (trimmed.equals("TRUE", true)) "1" else "0")
            trimmed.uppercase() in XValue.ERRORS -> cellWith("e", trimmed.uppercase())
            else -> {
                val date = parseDate(trimmed)
                val num = if (date == null) Formula.parseNumber(trimmed) else null
                when {
                    date != null -> {
                        if (!style.isDate) s = styles.derive(styleIdx, StyleChange.NumberFormat(NumFmtPreset.DATE.code)).toString()
                        cellWith(null, Formula.numText(Dates.toSerial(date, date1904)))
                    }
                    num != null && style.numFmt != "@" -> {
                        if (trimmed.endsWith("%") && !style.numFmt.contains('%')) {
                            s = styles.derive(styleIdx, StyleChange.NumberFormat(NumFmtPreset.PERCENT.code)).toString()
                        }
                        cellWith(null, Formula.numText(num).let { if (it.contains('E')) num.toString() else it })
                    }
                    else -> inlineString(p, input)
                }
            }
        }
        if (new != null && s != null && s != "0") new["s"] = s
        if (old == null && new == null) return
        record(listOf(cellStep(sh, row, col, new)))
    }

    private fun inlineString(p: String, text: String): XElement {
        val c = XElement(p + "c")
        c["t"] = "inlineStr"
        val t = XElement(p + "t")
        if (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace())) t["xml:space"] = "preserve"
        t.children.add(XText(text))
        c.children.add(XElement(p + "is").also { it.children.add(t) })
        return c
    }

    private fun parseDate(s: String): LocalDate? {
        val dmy = Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})$""").matchEntire(s)
        val iso = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").matchEntire(s)
        return try {
            when {
                dmy != null -> LocalDate.of(dmy.groupValues[3].toInt(), dmy.groupValues[2].toInt(), dmy.groupValues[1].toInt())
                iso != null -> LocalDate.of(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
                else -> null
            }
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /** Clears values (keeping formats) in a range. */
    fun clearRange(sheetIndex: Int, range: CellRange) {
        val sh = sheet(sheetIndex)
        sh.unshare()
        val steps = ArrayList<History.Step>()
        for ((k, el) in sh.cells.entries.toList()) {
            val r = CellRef.keyRow(k); val c = CellRef.keyCol(k)
            if (!range.contains(r, c)) continue
            val s = el["s"]
            val new = if (s != null && s != "0") XElement(sh.px + "c").also { it["s"] = s } else null
            steps.add(cellStep(sh, r, c, new))
        }
        record(steps)
    }

    /** Applies a formatting change to every cell of [range] (limited to 20 000 cells). */
    fun applyStyle(sheetIndex: Int, range: CellRange, change: StyleChange) {
        val sh = sheet(sheetIndex)
        val r2 = minOf(range.r2, maxOf(range.r1, sh.maxRow, range.r1 + 999))
        val c2 = minOf(range.c2, maxOf(range.c1, sh.maxCol, range.c1 + 99))
        val derived = HashMap<Int, Int>()
        val steps = ArrayList<History.Step>()
        var n = 0
        loop@ for (r in range.r1..r2) for (c in range.c1..c2) {
            if (++n > 20000) break@loop
            val old = sh.cellElement(r, c)
            val oldIdx = old?.get("s")?.toIntOrNull() ?: sh.inheritedStyle(r, c)?.toIntOrNull() ?: 0
            val newIdx = derived.getOrPut(oldIdx) { styles.derive(oldIdx, change) }
            if (newIdx == oldIdx && old != null) continue
            val el = old?.deepCopy() ?: XElement(sh.px + "c")
            el["s"] = if (newIdx == 0) null else newIdx.toString()
            steps.add(cellStep(sh, r, c, el))
        }
        if (steps.isNotEmpty()) pkg.markDirty(stylesPart)
        record(steps)
    }

    // ---------------------------------------------------------------- structure

    fun insertRows(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, true, at, count)
    fun deleteRows(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, true, at, -count)
    fun insertColumns(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, false, at, count)
    fun deleteColumns(sheetIndex: Int, at: Int, count: Int = 1) = structural(sheetIndex, false, at, -count)

    private fun shiftIndex(v: Int, at: Int, count: Int): Int? = when {
        count > 0 -> if (v >= at) v + count else v
        v in at until at - count -> null
        v >= at - count -> v + count
        else -> v
    }

    private fun shiftList(sqref: String, rows: Boolean, at: Int, count: Int): String =
        sqref.split(' ').filter { it.isNotBlank() }
            .map { Formula.shift(it, rows, at, count) { s -> s == null } }
            .filter { !it.contains("#REF!") }
            .joinToString(" ")

    private fun structural(sheetIndex: Int, rows: Boolean, at: Int, count: Int) {
        if (count == 0) return
        val infos = sheets
        val name = infos[sheetIndex].name
        val all = infos.indices.map { sheet(it) }
        all.forEach { it.unshare() }
        val sh = all[sheetIndex]
        val copy = sh.root.deepCopy()
        val own: (String?) -> Boolean = { it == null || it.equals(name, true) }

        // Cells and rows.
        copy.childLocal("sheetData")?.let { sd ->
            val keep = ArrayList<Any>()
            for (rowEl in sd.children) {
                if (rowEl !is XElement || rowEl.localName != "row") { keep.add(rowEl); continue }
                val r = (rowEl["r"]?.toIntOrNull() ?: continue) - 1
                val nr = if (rows) shiftIndex(r, at, count) ?: continue else r
                rowEl["r"] = (nr + 1).toString()
                rowEl.attrs.remove("spans")
                rowEl.children.removeAll { c ->
                    if (c !is XElement || c.localName != "c") return@removeAll false
                    val (_, col) = CellRef.parse(c["r"] ?: return@removeAll false) ?: return@removeAll false
                    val nc = if (!rows) shiftIndex(col, at, count) ?: return@removeAll true else col
                    c["r"] = CellRef.name(nr, nc)
                    false
                }
                keep.add(rowEl)
            }
            sd.children.clear()
            @Suppress("UNCHECKED_CAST")
            sd.children.addAll(keep as List<io.github.Prestgg.naroffice.xml.XNode>)
        }
        // Formulas and range attributes.
        for (e in copy.descendants()) {
            when (e.localName) {
                "f" -> {
                    val t = e.textContent
                    if (t.isNotEmpty()) e.setText(Formula.shift(t, rows, at, count, own))
                    e["ref"]?.let { e["ref"] = shiftList(it, rows, at, count).ifEmpty { null } }
                }
                "formula" -> e.textContent.let { if (it.isNotEmpty()) e.setText(Formula.shift(it, rows, at, count, own)) }
                "selection" -> { e.attrs.remove("activeCell"); e.attrs.remove("sqref") }
            }
        }
        for ((parent, item, attr) in listOf(
            Triple("mergeCells", "mergeCell", "ref"), Triple("hyperlinks", "hyperlink", "ref"),
            Triple("dataValidations", "dataValidation", "sqref"),
        )) {
            val p = copy.childLocal(parent) ?: continue
            p.children.removeAll { it is XElement && it.localName == item && shiftList(it[attr] ?: "", rows, at, count).also { s -> it[attr] = s }.isEmpty() }
            if (p.elements.none()) copy.children.remove(p) else if (p["count"] != null) p["count"] = p.elements.count().toString()
        }
        copy.children.removeAll { it is XElement && it.localName == "conditionalFormatting" &&
            shiftList(it["sqref"] ?: "", rows, at, count).also { s -> it["sqref"] = s }.isEmpty() }
        copy.childLocal("autoFilter")?.let { af ->
            val s = shiftList(af["ref"] ?: "", rows, at, count)
            if (s.isEmpty()) copy.children.remove(af) else af["ref"] = s
        }
        if (!rows) copy.childLocal("cols")?.let { cols ->
            cols.children.removeAll { c ->
                if (c !is XElement || c.localName != "col") return@removeAll false
                val a = (c["min"]?.toIntOrNull() ?: return@removeAll false) - 1
                val b = (c["max"]?.toIntOrNull() ?: (a + 1)) - 1
                val (na, nb) = if (count > 0) {
                    if (a >= at) (a + count) to (b + count) else if (b >= at) a to (b + count) else a to b
                } else {
                    val k = -count; val end = at + k - 1
                    if (a >= at && b <= end) return@removeAll true
                    val na = if (a > end) a - k else if (a >= at) at else a
                    val nb = if (b > end) b - k else if (b >= at) at - 1 else b
                    na to nb
                }
                c["min"] = (na + 1).toString(); c["max"] = (nb + 1).toString()
                false
            }
            if (cols.elements.none()) copy.children.remove(cols)
        }

        val steps = ArrayList<History.Step>()
        val splice = History.Splice(sh.root, 0, ArrayList(sh.root.children), ArrayList(copy.children)) {
            sh.reindex(); sh.dirty = true
        }
        splice.redo()
        steps.add(splice)

        // References to this sheet from other sheets and defined names.
        for ((i, other) in all.withIndex()) {
            if (i == sheetIndex) continue
            steps.addAll(rewriteFormulas(other) { Formula.shift(it, rows, at, count) { s -> s != null && s.equals(name, true) } })
        }
        steps.addAll(rewriteDefinedNames { Formula.shift(it, rows, at, count) { s -> s != null && s.equals(name, true) } })
        record(steps)
    }

    private fun rewriteFormulas(sh: Sheet, f: (String) -> String): List<History.Step> {
        val out = ArrayList<History.Step>()
        for ((k, el) in sh.cells.entries.toList()) {
            val fe = el.childLocal("f") ?: continue
            val text = fe.textContent
            if (text.isEmpty() || !text.contains('!')) continue
            val nt = f(text)
            if (nt == text) continue
            val copy = el.deepCopy()
            copy.childLocal("f")!!.setText(nt)
            out.add(cellStep(sh, CellRef.keyRow(k), CellRef.keyCol(k), copy))
        }
        return out
    }

    private fun rewriteDefinedNames(f: (String) -> String): List<History.Step> {
        val dn = wb.childLocal("definedNames") ?: return emptyList()
        val out = ArrayList<History.Step>()
        for ((i, c) in dn.children.withIndex()) {
            if (c !is XElement || c.localName != "definedName") continue
            val t = c.textContent
            val nt = f(t)
            if (nt == t) continue
            val copy = c.deepCopy()
            copy.setText(nt)
            val step = History.Splice(dn, i, listOf(c), listOf(copy)) { pkg.markDirty(workbookPart) }
            step.redo()
            out.add(step)
        }
        return out
    }

    // ---------------------------------------------------------------- sheet management

    fun isValidSheetName(name: String, except: Int = -1): Boolean {
        if (name.isBlank() || name.length > 31) return false
        if (name.any { it in "[]:*?/\\" } || name.startsWith("'") || name.endsWith("'")) return false
        return sheets.withIndex().none { (i, s) -> i != except && s.name.equals(name, true) }
    }

    fun renameSheet(index: Int, newName: String): Boolean {
        val name = newName.trim()
        if (!isValidSheetName(name, index)) return false
        val info = sheets[index]
        val old = info.name
        if (old == name) return true
        val steps = ArrayList<History.Step>()
        val copy = info.el.deepCopy()
        copy["name"] = name
        val idx = sheetsEl.children.indexOfFirst { it === info.el }
        val s = History.Splice(sheetsEl, idx, listOf(info.el), listOf(copy)) { pkg.markDirty(workbookPart) }
        s.redo()
        steps.add(s)
        for (i in sheets.indices) steps.addAll(rewriteFormulas(sheet(i)) { Formula.renameSheet(it, old, name) })
        steps.addAll(rewriteDefinedNames { Formula.renameSheet(it, old, name) })
        record(steps)
        return true
    }

    /** Adds an empty sheet at the end and returns its index. */
    fun addSheet(name: String? = null): Int {
        var n = sheets.size + 1
        var nm = name?.trim()?.takeIf { isValidSheetName(it) }
        while (nm == null) {
            val cand = "Sheet$n"
            if (isValidSheetName(cand)) nm = cand else n++
        }
        var k = 1
        while (pkg.exists("xl/worksheets/sheet$k.xml")) k++
        val part = "xl/worksheets/sheet$k.xml"
        val sheetId = (sheets.mapNotNull { it.el["sheetId"]?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        if (wb["xmlns:r"] == null) wb["xmlns:r"] = REL_NS
        val el = XElement(px + "sheet")
        el["name"] = nm
        el["sheetId"] = sheetId.toString()
        val sheetsElement = sheetsEl
        val newDoc = Xml.parse(BLANK_SHEET)
        var rid: String? = null
        val step = History.Lambda(
            u = {
                sheetsElement.children.removeAll { it === el }
                rid?.let { pkg.removeRel(workbookPart, it) }
                pkg.remove(part)
                pkg.markDirty(workbookPart)
            },
            r = {
                pkg.putXml(part, newDoc, CT_SHEET)
                rid = pkg.addRel(workbookPart, REL_SHEET, relTarget(part))
                el["r:id"] = rid
                sheetsElement.children.add(el)
                pkg.markDirty(workbookPart)
            },
        )
        step.redo()
        record(listOf(step))
        return sheets.size - 1
    }

    fun deleteSheet(index: Int): Boolean {
        val infos = sheets
        if (infos.size <= 1 || infos.count { !it.hidden } <= 1 && !infos[index].hidden) return false
        val info = infos[index]
        val steps = ArrayList<History.Step>()
        val sIdx = sheetsEl.children.indexOfFirst { it === info.el }
        steps.add(History.Splice(sheetsEl, sIdx, listOf(info.el), emptyList()) { pkg.markDirty(workbookPart) }.also { it.redo() })

        wb.childLocal("definedNames")?.let { dn ->
            val copy = dn.deepCopy()
            copy.children.removeAll { it is XElement && it["localSheetId"]?.toIntOrNull() == index }
            copy.elements.forEach { d ->
                val l = d["localSheetId"]?.toIntOrNull() ?: return@forEach
                if (l > index) d["localSheetId"] = (l - 1).toString()
            }
            val i = wb.children.indexOfFirst { it === dn }
            val newList = if (copy.elements.any()) listOf(copy) else emptyList()
            steps.add(History.Splice(wb, i, listOf(dn), newList) { pkg.markDirty(workbookPart) }.also { it.redo() })
        }
        wb.childLocal("bookViews")?.let { bv ->
            val copy = bv.deepCopy()
            val remaining = infos.size - 1
            copy.elements.forEach { v ->
                for (a in listOf("activeTab", "firstSheet")) {
                    val t = v[a]?.toIntOrNull() ?: continue
                    v[a] = (if (t >= index && t > 0) t - 1 else t).coerceIn(0, remaining - 1).toString()
                }
            }
            val i = wb.children.indexOfFirst { it === bv }
            steps.add(History.Splice(wb, i, listOf(bv), listOf(copy)) { pkg.markDirty(workbookPart) }.also { it.redo() })
        }
        val part = info.part
        // Keep the parsed tree itself, so that the Sheet and older undo steps stay valid.
        val sheetDoc = pkg.xml(part)
        val ct = pkg.contentType(part) ?: CT_SHEET
        val relsPart = pkg.relsPartOf(part)
        val relsBytes = pkg.bytes(relsPart)
        val rel = pkg.rel(workbookPart, info.relId)
        var rid = info.relId
        val pkgStep = History.Lambda(
            u = {
                if (sheetDoc != null) pkg.putXml(part, sheetDoc, ct)
                if (relsBytes != null) pkg.putBytes(relsPart, relsBytes)
                rid = pkg.addRel(workbookPart, rel?.type ?: REL_SHEET, rel?.target ?: relTarget(part))
                info.el["r:id"] = rid
            },
            r = {
                pkg.removeRel(workbookPart, rid)
                pkg.remove(part)
                if (relsBytes != null) pkg.remove(relsPart)
            },
        )
        pkgStep.redo()
        steps.add(pkgStep)
        record(steps)
        return true
    }

    private fun relTarget(part: String): String {
        val dir = workbookPart.substringBeforeLast('/', "")
        return if (dir.isNotEmpty() && part.startsWith("$dir/")) part.removePrefix("$dir/") else "/$part"
    }

    // ---------------------------------------------------------------- saving

    fun save(out: OutputStream) {
        // Excel rebuilds the calculation chain; a stale one makes it report corruption.
        for (r in pkg.rels(workbookPart).filter { it.type.endsWith("/calcChain") }) {
            pkg.removeRel(workbookPart, r.id)
            pkg.remove(pkg.resolve(workbookPart, r.target))
        }
        val calc = wb.childLocal("calcPr") ?: XElement(px + "calcPr").also { c ->
            val after = setOf("fileVersion", "fileSharing", "workbookPr", "workbookProtection", "bookViews", "sheets",
                "functionGroups", "externalReferences", "definedNames")
            var idx = 0
            for ((i, ch) in wb.children.withIndex()) if (ch is XElement && ch.localName in after) idx = i + 1
            wb.children.add(idx, c)
        }
        calc["fullCalcOnLoad"] = "1"
        pkg.markDirty(workbookPart)
        pkg.markDirty(stylesPart)
        for (sh in sheetCache.values) {
            if (!pkg.exists(sh.part) || pkg.xml(sh.part) !== sh.xdoc) continue
            if (sh.dirty) {
                sh.updateDimension()
                pkg.markDirty(sh.part)
            }
        }
        pkg.write(out)
        history.markSaved()
    }

    // ---------------------------------------------------------------- CSV

    fun sheetToCsv(index: Int, delimiter: Char = ','): String {
        val sh = sheet(index)
        val sb = StringBuilder()
        for (r in 0..sh.maxRow) {
            for (c in 0..sh.maxCol) {
                if (c > 0) sb.append(delimiter)
                val t = sh.cell(r, c)?.display ?: ""
                if (t.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
                    sb.append('"').append(t.replace("\"", "\"\"")).append('"')
                } else sb.append(t)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    companion object {
        const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val REL_SHEET = "$REL_NS/worksheet"
        const val REL_STYLES = "$REL_NS/styles"
        const val CT_SHEET = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
        const val CT_STYLES = "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"

        val BLANK_SHEET = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="$MAIN_NS" xmlns:r="$REL_NS"><dimension ref="A1"/><sheetViews><sheetView workbookViewId="0"/></sheetViews><sheetFormatPr defaultRowHeight="15"/><sheetData/><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>"""

        val BLANK_STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="$MAIN_NS"><fonts count="1"><font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        fun open(bytes: ByteArray) = XlsxDocument(OpcPackage.read(bytes))

        /** Plain text of a si/is element (rich text runs concatenated, phonetic runs skipped). */
        fun richText(e: XElement): String {
            val sb = StringBuilder()
            for (c in e.elements) when (c.localName) {
                "t" -> sb.append(c.textContent)
                "r" -> c.childLocal("t")?.let { sb.append(it.textContent) }
            }
            return sb.toString()
        }

        fun blank(sheetName: String = "Sheet1"): XlsxDocument {
            val pkg = OpcPackage.fromParts(
                linkedMapOf(
                    "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="$CT_SHEET"/><Override PartName="/xl/styles.xml" ContentType="$CT_STYLES"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>""",
                    "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="$REL_NS/extended-properties" Target="docProps/app.xml"/></Relationships>""",
                    "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS"><workbookPr/><bookViews><workbookView activeTab="0"/></bookViews><sheets><sheet name="${Xml.run { val sb = StringBuilder(); escape(sheetName, sb, true); sb }}" sheetId="1" r:id="rId1"/></sheets><calcPr calcId="191029"/></workbook>""",
                    "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL_SHEET" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL_STYLES" Target="styles.xml"/></Relationships>""",
                    "xl/worksheets/sheet1.xml" to BLANK_SHEET,
                    "xl/styles.xml" to BLANK_STYLES,
                    "docProps/core.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>NarOffice</dc:creator></cp:coreProperties>""",
                    "docProps/app.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>NarOffice</Application></Properties>""",
                ),
            )
            return XlsxDocument(pkg)
        }

        /** Parses CSV text (delimiter auto-detected among , ; and tab) into a new workbook. */
        fun fromCsv(text: String, sheetName: String = "Sheet1"): XlsxDocument {
            val doc = blank(sheetName)
            val rows = parseCsv(text)
            val sh = doc.sheet(0)
            val sd = sh.sheetData
            for ((r, fields) in rows.withIndex()) {
                if (fields.all { it.isEmpty() }) continue
                val rowEl = XElement("row").also { it["r"] = (r + 1).toString() }
                for ((c, f) in fields.withIndex()) {
                    if (f.isEmpty()) continue
                    val num = Formula.parseNumber(f)?.takeIf { !f.trim().endsWith("%") && !(f.length > 1 && f.startsWith("0") && !f.startsWith("0.") && !f.startsWith("0,")) }
                    val cell = if (num != null) {
                        XElement("c").also { el ->
                            el.children.add(XElement("v").also { it.children.add(XText(Formula.numText(num).let { s -> if (s.contains('E')) num.toString() else s })) })
                        }
                    } else doc.inlineString("", f)
                    cell["r"] = CellRef.name(r, c)
                    rowEl.children.add(cell)
                }
                sd.children.add(rowEl)
            }
            sh.reindex()
            sh.dirty = true
            return doc
        }

        fun detectDelimiter(text: String): Char {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
            var q = false
            val counts = HashMap<Char, Int>()
            for (ch in line) {
                if (ch == '"') q = !q
                else if (!q && ch in ",;\t") counts[ch] = (counts[ch] ?: 0) + 1
            }
            return counts.maxByOrNull { it.value }?.key ?: ','
        }

        fun parseCsv(text0: String): List<List<String>> {
            val text = text0.removePrefix("﻿")
            val d = detectDelimiter(text)
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val sb = StringBuilder()
            var i = 0
            var inQ = false
            var any = false
            while (i < text.length) {
                val c = text[i]
                if (inQ) {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                    } else sb.append(c)
                } else when (c) {
                    '"' -> { inQ = true; any = true }
                    d -> { row.add(sb.toString()); sb.setLength(0); any = true }
                    '\r' -> {}
                    '\n' -> { row.add(sb.toString()); sb.setLength(0); rows.add(row); row = ArrayList(); any = false }
                    else -> { sb.append(c); any = true }
                }
                i++
            }
            if (any || sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
            return rows
        }
    }
}
