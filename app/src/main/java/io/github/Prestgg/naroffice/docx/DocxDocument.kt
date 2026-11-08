// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.docx

import io.github.Prestgg.naroffice.core.History
import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.ooxml.OfficeFormatException
import io.github.Prestgg.naroffice.ooxml.OpcPackage
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.RichPara
import io.github.Prestgg.naroffice.text.Span
import io.github.Prestgg.naroffice.xml.XDocument
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.Xml
import java.io.OutputStream

/** A paragraph or table and the element list it lives in. */
sealed class DocBlock {
    abstract val el: XElement
    abstract val container: XElement

    class Para(override val el: XElement, override val container: XElement) : DocBlock()
    class Table(override val el: XElement, override val container: XElement) : DocBlock() {
        val rows: List<XElement> get() = el.children("w:tr")
    }
}

class DocxDocument(val pkg: OpcPackage) {

    val mainPart: String = pkg.mainPart() ?: throw OfficeFormatException("No main document part")
    private val xdoc: XDocument = pkg.xml(mainPart) ?: throw OfficeFormatException("Missing $mainPart")
    val body: XElement = xdoc.root.child("w:body") ?: throw OfficeFormatException("No document body")

    val theme = Colors.Theme(pkg.targetsOfType(mainPart, "/theme").firstOrNull()?.let { pkg.xml(it)?.root })
    private var stylesPart = pkg.targetsOfType(mainPart, "/styles").firstOrNull()
    var styles = WordStyles(stylesPart?.let { pkg.xml(it)?.root }, theme)
        private set
    private var numberingPart = pkg.targetsOfType(mainPart, "/numbering").firstOrNull()
    var numbering = Numbering(numberingPart?.let { pkg.xml(it)?.root })
        private set

    val history = History()

    /** Bumped on every change; the UI observes it. */
    var version = 0
        private set

    private fun changed() {
        version++
        pkg.markDirty(mainPart)
    }

    val isModified get() = history.changeCounter != 0

    // ---------------------------------------------------------------- reading

    /** Top-level blocks in reading order, looking through content controls. */
    fun blocks(): List<DocBlock> = blocksOf(body)

    fun blocksOf(container: XElement): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        for (c in container.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "p" -> out.add(DocBlock.Para(c, container))
                "tbl" -> out.add(DocBlock.Table(c, container))
                "sdt" -> c.child("w:sdtContent")?.let { out.addAll(blocksOf(it)) }
                "customXml" -> out.addAll(blocksOf(c))
                "AlternateContent" -> (c.childLocal("Choice") ?: c.childLocal("Fallback"))?.let { out.addAll(blocksOf(it)) }
            }
        }
        return out
    }

    /** Cells of a table row, each a container of blocks. */
    fun cells(row: XElement): List<XElement> {
        val out = ArrayList<XElement>()
        for (c in row.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "tc" -> out.add(c)
                "sdt" -> c.child("w:sdtContent")?.children("w:tc")?.let { out.addAll(it) }
                "customXml" -> out.addAll(c.children("w:tc"))
            }
        }
        return out
    }

    /** List labels for every paragraph, computed in document order. */
    fun listLabels(): Map<XElement, String> {
        val out = java.util.IdentityHashMap<XElement, String>()
        val counter = numbering.Counter()
        fun visit(container: XElement) {
            for (b in blocksOf(container)) when (b) {
                is DocBlock.Para -> {
                    val pp = styles.resolvePara(b.el.child("w:pPr"))
                    val id = pp.numId ?: continue
                    counter.label(id, pp.numLevel)?.let { out[b.el] = it }
                }
                is DocBlock.Table -> b.rows.forEach { r -> cells(r).forEach { visit(it) } }
            }
        }
        visit(body)
        return out
    }

    fun imageBytes(relId: String): ByteArray? = pkg.target(mainPart, relId)?.let { pkg.bytes(it) }

    fun hyperlink(wrapper: XElement): String? {
        val id = wrapper["r:id"] ?: return wrapper["w:anchor"]?.let { "#$it" }
        return pkg.rel(mainPart, id)?.target
    }

    /** Plain text of the whole document, for search and sharing. */
    fun plainText(): String {
        val sb = StringBuilder()
        fun visit(container: XElement) {
            for (b in blocksOf(container)) when (b) {
                is DocBlock.Para -> sb.append(DocxPara.read(b.el).text.filter { it != '￼' }).append('\n')
                is DocBlock.Table -> b.rows.forEach { r -> cells(r).forEach { visit(it) } }
            }
        }
        visit(body)
        return sb.toString()
    }

    // ---------------------------------------------------------------- editing

    private fun indexIn(container: XElement, el: XElement): Int {
        val i = container.children.indexOfFirst { it === el }
        require(i >= 0) { "Element is not in its container" }
        return i
    }

    fun replace(container: XElement, old: List<XElement>, new: List<XElement>) {
        val i = indexIn(container, old.first())
        history.splice(container, i, old.size, new, ::changed)
    }

    /** Writes an edited paragraph back, replacing [p] by one or more paragraphs. */
    fun commitParagraphs(container: XElement, p: XElement, paras: List<Pair<WPara, XElement?>>) {
        val els = paras.map { (rp, pPr) -> DocxPara.write(rp, p, pPr) }
        replace(container, listOf(p), els)
    }

    fun pPrOf(p: XElement): XElement? = p.child("w:pPr")

    /** Returns a copy of [p] whose pPr was changed by [edit]. */
    fun withPPr(p: XElement, edit: (XElement) -> Unit): XElement {
        val copy = XElement(p.name, LinkedHashMap(p.attrs))
        val pPr = p.child("w:pPr")?.deepCopy() ?: XElement("w:pPr")
        edit(pPr)
        if (pPr.children.isNotEmpty()) copy.children.add(pPr)
        p.children.forEach { if (!(it is XElement && it.name == "w:pPr")) copy.children.add(it) }
        return copy
    }

    fun setAlign(container: XElement, p: XElement, align: Align) {
        val np = withPPr(p) { pPr ->
            pPr.removeChild("w:jc")
            if (align != Align.START) DocxPara.insertPPr(pPr, XElement("w:jc").also {
                it["w:val"] = when (align) {
                    Align.CENTER -> "center"
                    Align.END -> "right"
                    Align.JUSTIFY -> "both"
                    Align.START -> "left"
                }
            })
        }
        replace(container, listOf(p), listOf(np))
    }

    /** Paragraph style choices: null = Normal, 0 = Title, 1..3 = headings. */
    fun setHeading(container: XElement, p: XElement, level: Int?) {
        val id = when (level) {
            null -> null
            0 -> styles.titleStyleId() ?: ensureStyle("Title", "Title", 28, true)
            else -> styles.headingStyleId(level) ?: ensureStyle("Heading$level", "heading $level",
                when (level) { 1 -> 20; 2 -> 16; else -> 14 }, true)
        }
        val np = withPPr(p) { pPr ->
            pPr.removeChild("w:pStyle")
            if (id != null) DocxPara.insertPPr(pPr, XElement("w:pStyle").also { it["w:val"] = id })
        }
        replace(container, listOf(p), listOf(np))
    }

    fun headingOf(p: XElement): Int? {
        val sid = p.path("w:pPr", "w:pStyle")?.get("w:val") ?: return null
        if (sid == styles.titleStyleId()) return 0
        for (l in 1..3) if (styles.headingStyleId(l) == sid) return l
        return null
    }

    /** Adds a simple paragraph style when the document has none for headings. */
    private fun ensureStyle(id: String, name: String, sizePt: Int, bold: Boolean): String {
        val part = stylesPart ?: run {
            val part = "word/styles.xml"
            pkg.putXml(part, Xml.parse(EMPTY_STYLES),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml")
            pkg.addRel(mainPart, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles", "styles.xml")
            stylesPart = part
            part
        }
        val root = pkg.xml(part)!!.root
        if (root.children("w:style").none { it["w:styleId"] == id }) {
            val s = Xml.parseElement(
                """<w:style xmlns:w="$W" w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="${if (id == "Title") 0 else (id.last().digitToIntOrNull() ?: 1) - 1}"/></w:pPr><w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val="${sizePt * 2}"/></w:rPr></w:style>""",
            )
            s.attrs.remove("xmlns:w")
            root.children.add(s)
            pkg.markDirty(part)
            styles = WordStyles(root, theme)
        }
        return id
    }

    fun listKind(p: XElement): Boolean? {
        val id = styles.resolvePara(p.child("w:pPr")).numId ?: return null
        return numbering.isBullet(id)
    }

    /** Toggles a bullet (or numbered) list on paragraph [p]. */
    fun toggleList(container: XElement, p: XElement, bullet: Boolean) {
        val current = listKind(p)
        val np = withPPr(p) { pPr ->
            pPr.removeChild("w:numPr")
            if (current != bullet) {
                val numId = ensureList(bullet)
                DocxPara.insertPPr(pPr, Xml.parseElement(
                    """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="$numId"/></w:numPr>""",
                ))
                if (pPr.child("w:ind") == null) {
                    // List style indents come from the numbering definition.
                }
            } else if (styles.resolvePara(pPr).numId != null) {
                // The list comes from the paragraph style: switch it off explicitly.
                DocxPara.insertPPr(pPr, Xml.parseElement("""<w:numPr><w:numId w:val="0"/></w:numPr>"""))
            }
        }
        replace(container, listOf(p), listOf(np))
    }

    fun changeListLevel(container: XElement, p: XElement, delta: Int) {
        val pp = styles.resolvePara(p.child("w:pPr"))
        val id = pp.numId ?: return
        val lvl = (pp.numLevel + delta).coerceIn(0, 8)
        val np = withPPr(p) { pPr ->
            pPr.removeChild("w:numPr")
            DocxPara.insertPPr(pPr, Xml.parseElement(
                """<w:numPr><w:ilvl w:val="$lvl"/><w:numId w:val="$id"/></w:numPr>""",
            ))
        }
        replace(container, listOf(p), listOf(np))
    }

    private fun ensureList(bullet: Boolean): String {
        numbering.findNumId(bullet)?.let { return it }
        val part = numberingPart ?: run {
            val part = "word/numbering.xml"
            pkg.putXml(part, Xml.parse("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W"/>"""),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml")
            pkg.addRel(mainPart, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering", "numbering.xml")
            numberingPart = part
            part
        }
        val root = pkg.xml(part)!!.root
        val absIds = root.children("w:abstractNum").mapNotNull { it["w:abstractNumId"]?.toIntOrNull() }
        val numIds = root.children("w:num").mapNotNull { it["w:numId"]?.toIntOrNull() }
        val absId = (absIds.maxOrNull() ?: -1) + 1
        val numId = (numIds.maxOrNull() ?: 0) + 1
        val lvls = (0..8).joinToString("") { l ->
            val ind = 720 * (l + 1)
            if (bullet) {
                val glyph = listOf("•", "◦", "▪")[l % 3]
                """<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="$glyph"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="$ind" w:hanging="360"/></w:pPr></w:lvl>"""
            } else {
                val fmt = listOf("decimal", "lowerLetter", "lowerRoman")[l % 3]
                """<w:lvl w:ilvl="$l"><w:start w:val="1"/><w:numFmt w:val="$fmt"/><w:lvlText w:val="%${l + 1}."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="$ind" w:hanging="360"/></w:pPr></w:lvl>"""
            }
        }
        val abs = Xml.parseElement("""<w:abstractNum xmlns:w="$W" w:abstractNumId="$absId"><w:multiLevelType w:val="hybridMultilevel"/>$lvls</w:abstractNum>""")
        abs.attrs.remove("xmlns:w")
        // abstractNum elements must precede num elements.
        val firstNum = root.children.indexOfFirst { it is XElement && it.name == "w:num" }
        if (firstNum >= 0) root.children.add(firstNum, abs) else root.children.add(abs)
        root.children.add(Xml.parseElement("""<w:num w:numId="$numId"><w:abstractNumId w:val="$absId"/></w:num>"""))
        pkg.markDirty(part)
        numbering = Numbering(root)
        return numId.toString()
    }

    /** A new empty paragraph inheriting the paragraph style of [like]. */
    fun newParagraph(like: XElement?): XElement {
        val p = XElement("w:p")
        like?.child("w:pPr")?.let { src ->
            val pPr = src.deepCopy()
            pPr.removeChild("w:sectPr")
            pPr.removeChild("w:pageBreakBefore")
            p.children.add(pPr)
        }
        return p
    }

    fun insertAfter(container: XElement, after: XElement?, els: List<XElement>) {
        val idx = if (after == null) insertionPoint(container) else indexIn(container, after) + 1
        history.splice(container, idx, 0, els, ::changed)
    }

    /** Index before the final w:sectPr / w:tcPr-only end of a container. */
    private fun insertionPoint(container: XElement): Int {
        val i = container.children.indexOfLast { it is XElement && it.name == "w:sectPr" }
        return if (i >= 0) i else container.children.size
    }

    fun delete(container: XElement, el: XElement) {
        val idx = indexIn(container, el)
        history.splice(container, idx, 1, emptyList(), ::changed)
        // A table cell must contain at least one paragraph.
        if (container.localName == "tc" && container.children.none { it is XElement && it.localName in setOf("p", "tbl") }) {
            history.splice(container, container.children.size, 0, listOf(XElement("w:p")), ::changed)
        }
        if (container === body && blocks().isEmpty()) {
            history.splice(body, 0, 0, listOf(XElement("w:p")), ::changed)
        }
    }

    fun insertTable(container: XElement, after: XElement?, rows: Int, cols: Int) {
        val sectW = body.child("w:sectPr")?.child("w:pgSz")?.get("w:w")?.toIntOrNull() ?: 11906
        val margins = body.child("w:sectPr")?.child("w:pgMar")?.let {
            (it["w:left"]?.toIntOrNull() ?: 1440) + (it["w:right"]?.toIntOrNull() ?: 1440)
        } ?: 2880
        val colW = ((sectW - margins) / cols).coerceAtLeast(500)
        val grid = (1..cols).joinToString("") { """<w:gridCol w:w="$colW"/>""" }
        val cell = """<w:tc><w:tcPr><w:tcW w:w="$colW" w:type="dxa"/></w:tcPr><w:p/></w:tc>"""
        val row = "<w:tr>" + cell.repeat(cols) + "</w:tr>"
        val b = """<w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/>"""
        val tbl = Xml.parseElement(
            """<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>$b</w:tblBorders><w:tblLook w:val="04A0"/></w:tblPr><w:tblGrid>$grid</w:tblGrid>${row.repeat(rows)}</w:tbl>""",
        )
        // Word needs a paragraph after a table at the end of a cell or body.
        insertAfter(container, after, listOf(tbl, XElement("w:p")))
    }

    fun addRow(table: XElement, afterRow: XElement?) {
        val rows = table.children("w:tr")
        val src = afterRow ?: rows.lastOrNull() ?: return
        val nr = src.deepCopy()
        for (tc in nr.children("w:tc")) {
            tc.children.removeAll { !(it is XElement && it.name == "w:tcPr") }
            tc.children.add(XElement("w:p"))
        }
        val idx = table.children.indexOfFirst { it === src } + 1
        history.splice(table, idx, 0, listOf(nr), ::changed)
    }

    fun deleteRow(table: XElement, row: XElement) {
        if (table.children("w:tr").size <= 1) return
        history.splice(table, table.children.indexOfFirst { it === row }, 1, emptyList(), ::changed)
    }

    fun addColumn(table: XElement, colIndex: Int) {
        val newTable = table.deepCopy()
        newTable.child("w:tblGrid")?.let { g ->
            val cols = g.children("w:gridCol")
            val ref = cols.getOrNull(colIndex) ?: cols.lastOrNull()
            val nc = ref?.deepCopy() ?: XElement("w:gridCol")
            val pos = ref?.let { r -> g.children.indexOfFirst { it === r } + 1 } ?: g.children.size
            g.children.add(pos, nc)
        }
        for (tr in newTable.children("w:tr")) {
            val tcs = tr.children("w:tc")
            val ref = tcs.getOrNull(colIndex) ?: tcs.lastOrNull() ?: continue
            val nc = XElement("w:tc")
            ref.child("w:tcPr")?.let { nc.children.add(it.deepCopy().also { p -> p.removeChild("w:gridSpan"); p.removeChild("w:vMerge") }) }
            nc.children.add(XElement("w:p"))
            tr.children.add(tr.children.indexOfFirst { it === ref } + 1, nc)
        }
        replaceTable(table, newTable)
    }

    fun deleteColumn(table: XElement, colIndex: Int) {
        val newTable = table.deepCopy()
        val grid = newTable.child("w:tblGrid")?.children("w:gridCol")
        if (grid != null && grid.size <= 1) return
        grid?.getOrNull(colIndex)?.let { c -> newTable.child("w:tblGrid")!!.children.remove(c) }
        for (tr in newTable.children("w:tr")) {
            val tcs = tr.children("w:tc")
            if (tcs.size <= 1) continue
            tcs.getOrNull(colIndex)?.let { tr.children.remove(it) }
        }
        replaceTable(table, newTable)
    }

    private fun replaceTable(old: XElement, new: XElement) {
        val container = findParent(body, old) ?: return
        replace(container, listOf(old), listOf(new))
    }

    fun findParent(root: XElement, target: XElement): XElement? {
        for (c in root.children) {
            if (c === target) return root
            if (c is XElement) findParent(c, target)?.let { return it }
        }
        return null
    }

    /** Inserts a picture paragraph after [after]. */
    fun insertImage(container: XElement, after: XElement?, data: ByteArray, ext: String, widthPx: Int, heightPx: Int) {
        val e = ext.lowercase().let { if (it == "jpeg") "jpg" else it }
        var n = 1
        while (pkg.exists("word/media/image$n.$e")) n++
        val part = "word/media/image$n.$e"
        pkg.putBytes(part, data, OpcPackage.MEDIA_TYPES[e] ?: "image/$e")
        val rid = pkg.addRel(mainPart, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "media/image$n.$e")
        val maxW = 5_943_600L // 6.5 inches in EMU
        var cx = widthPx * 9525L
        var cy = heightPx * 9525L
        if (cx > maxW) {
            cy = cy * maxW / cx; cx = maxW
        }
        val id = 1000 + n
        val p = Xml.parseElement(
            """<w:p xmlns:w="$W" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Picture $n"/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="image$n.$e"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
        )
        // Namespaces are declared on the document root already.
        p.attrs.clear()
        p.descendants().forEach { el -> el.attrs.keys.filter { it.startsWith("xmlns:") }.forEach { el.attrs.remove(it) } }
        ensureRootNamespaces()
        insertAfter(container, after, listOf(p))
    }

    private fun ensureRootNamespaces() {
        val ns = mapOf(
            "xmlns:wp" to "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
            "xmlns:a" to "http://schemas.openxmlformats.org/drawingml/2006/main",
            "xmlns:pic" to "http://schemas.openxmlformats.org/drawingml/2006/picture",
            "xmlns:r" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        )
        for ((k, v) in ns) if (xdoc.root[k] == null) xdoc.root[k] = v
    }

    fun undo(): Boolean = history.undo()
    fun redo(): Boolean = history.redo()

    fun save(out: OutputStream) {
        pkg.markDirty(mainPart)
        pkg.write(out)
        history.markSaved()
    }

    /** Finds paragraphs containing [query]; returns them in order. */
    fun search(query: String): List<XElement> {
        if (query.isBlank()) return emptyList()
        val out = ArrayList<XElement>()
        fun visit(container: XElement) {
            for (b in blocksOf(container)) when (b) {
                is DocBlock.Para -> if (DocxPara.read(b.el).text.contains(query, ignoreCase = true)) out.add(b.el)
                is DocBlock.Table -> b.rows.forEach { r -> cells(r).forEach { visit(it) } }
            }
        }
        visit(body)
        return out
    }

    /** Replaces every occurrence of [query] in paragraph text; returns the number replaced. */
    fun replaceAll(query: String, replacement: String): Int {
        if (query.isEmpty()) return 0
        var count = 0
        val steps = ArrayList<History.Step>()
        fun visit(container: XElement) {
            for (b in blocksOf(container)) when (b) {
                is DocBlock.Para -> {
                    var rp = DocxPara.read(b.el)
                    var from = 0
                    var changedHere = false
                    while (true) {
                        val i = rp.text.indexOf(query, from, ignoreCase = true)
                        if (i < 0) break
                        rp = rp.replace(i, i + query.length, replacement)
                        from = i + replacement.length
                        count++
                        changedHere = true
                    }
                    if (changedHere) {
                        val np = DocxPara.write(rp, b.el, b.el.child("w:pPr"))
                        val idx = b.container.children.indexOfFirst { it === b.el }
                        val step = History.Splice(b.container, idx, listOf(b.el), listOf(np), ::changed)
                        step.redo()
                        steps.add(step)
                    }
                }
                is DocBlock.Table -> b.rows.forEach { r -> cells(r).forEach { visit(it) } }
            }
        }
        visit(body)
        if (steps.isNotEmpty()) history.push(History.Group(steps))
        return count
    }

    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun open(bytes: ByteArray) = DocxDocument(OpcPackage.read(bytes))

        val EMPTY_STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="az-Latn-AZ"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style></w:styles>"""

        /** A blank document with Normal, Title and Heading 1–3 styles. */
        fun blank(): DocxDocument {
            val heading = { id: String, name: String, sz: Int, lvl: Int, color: String ->
                """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="$lvl"/></w:pPr><w:rPr><w:b/><w:color w:val="$color"/><w:sz w:val="$sz"/></w:rPr></w:style>"""
            }
            val styles = EMPTY_STYLES.replace(
                "</w:styles>",
                heading("Title", "Title", 56, 0, "1F1F1F") + heading("Heading1", "heading 1", 36, 0, "B5202E") +
                    heading("Heading2", "heading 2", 28, 1, "B5202E") + heading("Heading3", "heading 3", 24, 2, "404040") +
                    """<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>""",
            )
            val pkg = OpcPackage.fromParts(
                linkedMapOf(
                    "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>""",
                    "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>""",
                    "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$W" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body><w:p/><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""",
                    "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                    "word/styles.xml" to styles,
                    "docProps/core.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>NarOffice</dc:creator></cp:coreProperties>""",
                    "docProps/app.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>NarOffice</Application></Properties>""",
                ),
            )
            return DocxDocument(pkg)
        }
    }
}

/** Convenience used by the editor when a paragraph has no runs yet. */
fun emptyWPara(fmt: RunFmt = RunFmt(null)): WPara = RichPara(listOf(Span("", fmt)))
