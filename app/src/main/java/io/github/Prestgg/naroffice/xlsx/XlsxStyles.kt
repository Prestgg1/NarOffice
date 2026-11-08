// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.Xml

enum class HAlign { GENERAL, LEFT, CENTER, RIGHT, JUSTIFY, FILL }
enum class VAlign { TOP, CENTER, BOTTOM }

data class Border(val present: Boolean = false, val color: Int? = null, val thick: Boolean = false)

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val fontSizePt: Float = 11f,
    val fontName: String? = null,
    val fontColor: Int? = null,
    val fill: Int? = null,
    val hAlign: HAlign = HAlign.GENERAL,
    val vAlign: VAlign = VAlign.BOTTOM,
    val wrap: Boolean = false,
    val indent: Int = 0,
    val rotation: Int = 0,
    val left: Border = Border(),
    val right: Border = Border(),
    val top: Border = Border(),
    val bottom: Border = Border(),
    val numFmtId: Int = 0,
    val numFmt: String = "General",
) {
    val isDate: Boolean get() = NumberFormat.isDate(numFmt)

    companion object {
        val DEFAULT = CellStyle()
    }
}

/** Number format presets offered by the editor. */
enum class NumFmtPreset(val code: String) {
    GENERAL("General"), DECIMAL2("0.00"), THOUSANDS("#,##0"), PERCENT("0%"), DATE("dd.mm.yyyy"), TEXT("@");
}

/** A change applied to the xf of a cell. */
sealed class StyleChange {
    class Bold(val on: Boolean) : StyleChange()
    class Italic(val on: Boolean) : StyleChange()
    class Underline(val on: Boolean) : StyleChange()
    class FontColor(val argb: Int?) : StyleChange()
    class FontSize(val pt: Float) : StyleChange()
    class Fill(val argb: Int?) : StyleChange()
    class Align(val h: HAlign) : StyleChange()
    class Wrap(val on: Boolean) : StyleChange()
    class NumberFormat(val code: String) : StyleChange()
    class Borders(val on: Boolean) : StyleChange()
}

/** styles.xml: reads cell formats and appends new ones for edits. */
class XlsxStyles(private val root: XElement, private val theme: Colors.Theme?) {

    private val px = root.name.substringBefore(':', "").let { if (it.isEmpty() || !root.name.contains(':')) "" else "$it:" }
    private val cache = HashMap<Int, CellStyle>()

    private fun section(local: String): XElement {
        root.childLocal(local)?.let { return it }
        // Create in schema order.
        val order = listOf("numFmts", "fonts", "fills", "borders", "cellStyleXfs", "cellXfs", "cellStyles", "dxfs",
            "tableStyles", "colors", "extLst")
        val e = XElement(px + local)
        val rank = order.indexOf(local)
        val idx = root.children.indexOfFirst { it is XElement && order.indexOf(it.localName) > rank }
        if (idx >= 0) root.children.add(idx, e) else root.children.add(e)
        return e
    }

    private fun items(local: String, item: String): List<XElement> =
        root.childLocal(local)?.elements?.filter { it.localName == item }?.toList() ?: emptyList()

    private val indexedColors: List<Int> by lazy {
        val custom = root.childLocal("colors")?.childLocal("indexedColors")?.elements
            ?.mapNotNull { Colors.hex(it["rgb"]) }?.toList()
        if (!custom.isNullOrEmpty()) custom else DEFAULT_PALETTE
    }

    fun color(e: XElement?): Int? {
        if (e == null || e["auto"] == "1") return null
        var c: Int? = e["rgb"]?.let { Colors.hex(it) }
        if (c == null) c = e["theme"]?.toIntOrNull()?.let { theme?.indexed(it) }
        if (c == null) c = e["indexed"]?.toIntOrNull()?.let { i ->
            if (i == 64 || i == 65) null else indexedColors.getOrNull(i)
        }
        if (c == null) return null
        val tint = e["tint"]?.toFloatOrNull()
        return if (tint != null && tint != 0f) Colors.tint(c, tint) else c
    }

    fun numFmtCode(id: Int): String {
        items("numFmts", "numFmt").firstOrNull { it["numFmtId"]?.toIntOrNull() == id }?.let { return it["formatCode"] ?: "General" }
        return NumberFormat.BUILTIN[id] ?: "General"
    }

    fun xfCount(): Int = items("cellXfs", "xf").size

    fun style(index: Int): CellStyle {
        cache[index]?.let { return it }
        val xfs = items("cellXfs", "xf")
        val xf = xfs.getOrNull(index) ?: xfs.firstOrNull() ?: return CellStyle.DEFAULT
        val font = items("fonts", "font").getOrNull(xf["fontId"]?.toIntOrNull() ?: 0)
        val fill = items("fills", "fill").getOrNull(xf["fillId"]?.toIntOrNull() ?: 0)
        val border = items("borders", "border").getOrNull(xf["borderId"]?.toIntOrNull() ?: 0)
        val align = xf.childLocal("alignment")
        val numFmtId = xf["numFmtId"]?.toIntOrNull() ?: 0
        fun flag(name: String): Boolean {
            val f = font?.childLocal(name) ?: return false
            val v = f["val"]
            return v == null || (v != "0" && v != "false")
        }
        val pattern = fill?.childLocal("patternFill")
        val fillColor = when {
            pattern != null && pattern["patternType"] != null && pattern["patternType"] != "none" ->
                color(pattern.childLocal("fgColor")) ?: if (pattern["patternType"] == "solid") null else color(pattern.childLocal("bgColor"))
            fill?.childLocal("gradientFill") != null ->
                color(fill.childLocal("gradientFill")?.elements?.firstOrNull()?.childLocal("color"))
            else -> null
        }
        fun side(name: String): Border {
            val s = border?.childLocal(name) ?: return Border()
            val st = s["style"] ?: return Border()
            if (st == "none") return Border()
            return Border(true, color(s.childLocal("color")), st in setOf("medium", "thick", "double", "mediumDashed"))
        }
        val st = CellStyle(
            bold = flag("b"),
            italic = flag("i"),
            underline = font?.childLocal("u")?.let { it["val"] != "none" } ?: false,
            strike = flag("strike"),
            fontSizePt = font?.childLocal("sz")?.get("val")?.toFloatOrNull() ?: 11f,
            fontName = font?.childLocal("name")?.get("val"),
            fontColor = color(font?.childLocal("color")),
            fill = fillColor,
            hAlign = when (align?.get("horizontal")) {
                "left" -> HAlign.LEFT
                "center", "centerContinuous" -> HAlign.CENTER
                "right" -> HAlign.RIGHT
                "justify", "distributed" -> HAlign.JUSTIFY
                "fill" -> HAlign.FILL
                else -> HAlign.GENERAL
            },
            vAlign = when (align?.get("vertical")) {
                "top" -> VAlign.TOP
                "center" -> VAlign.CENTER
                else -> VAlign.BOTTOM
            },
            wrap = align?.get("wrapText") == "1" || align?.get("wrapText") == "true",
            indent = align?.get("indent")?.toIntOrNull() ?: 0,
            rotation = align?.get("textRotation")?.toIntOrNull() ?: 0,
            left = side("left"), right = side("right"), top = side("top"), bottom = side("bottom"),
            numFmtId = numFmtId,
            numFmt = numFmtCode(numFmtId),
        )
        cache[index] = st
        return st
    }

    /** Returns the xf index for the style [index] with [change] applied, adding entries as needed. */
    fun derive(index: Int, change: StyleChange): Int {
        val xfs = items("cellXfs", "xf")
        if (xfs.isEmpty()) ensureDefaults()
        val base = (items("cellXfs", "xf").getOrNull(index) ?: items("cellXfs", "xf").first()).deepCopy()
        val cur = style(index)
        when (change) {
            is StyleChange.Bold -> editFont(base) { f -> setFlag(f, "b", change.on) }
            is StyleChange.Italic -> editFont(base) { f -> setFlag(f, "i", change.on) }
            is StyleChange.Underline -> editFont(base) { f -> setFlag(f, "u", change.on) }
            is StyleChange.FontSize -> editFont(base) { f ->
                f.children.removeAll { it is XElement && it.localName == "sz" }
                insertFontChild(f, XElement(px + "sz").also { it["val"] = fmtNum(change.pt) })
            }
            is StyleChange.FontColor -> editFont(base) { f ->
                f.children.removeAll { it is XElement && it.localName == "color" }
                if (change.argb != null) insertFontChild(f, XElement(px + "color").also { it["rgb"] = argbHex(change.argb) })
            }
            is StyleChange.Fill -> {
                val fill = if (change.argb == null) {
                    Xml.parseElement("<${px}fill><${px}patternFill patternType=\"none\"/></${px}fill>")
                } else {
                    Xml.parseElement("<${px}fill><${px}patternFill patternType=\"solid\"><${px}fgColor rgb=\"${argbHex(change.argb)}\"/><${px}bgColor indexed=\"64\"/></${px}patternFill></${px}fill>")
                }
                base["fillId"] = addUnique("fills", "fill", fill).toString()
                base["applyFill"] = "1"
            }
            is StyleChange.Align -> {
                val a = base.childLocal("alignment") ?: XElement(px + "alignment").also { base.children.add(0, it) }
                a["horizontal"] = when (change.h) {
                    HAlign.GENERAL -> null
                    HAlign.LEFT -> "left"
                    HAlign.CENTER -> "center"
                    HAlign.RIGHT -> "right"
                    HAlign.JUSTIFY -> "justify"
                    HAlign.FILL -> "fill"
                }
                if (a.attrs.isEmpty()) base.children.remove(a)
                base["applyAlignment"] = "1"
            }
            is StyleChange.Wrap -> {
                val a = base.childLocal("alignment") ?: XElement(px + "alignment").also { base.children.add(0, it) }
                a["wrapText"] = if (change.on) "1" else null
                if (a.attrs.isEmpty()) base.children.remove(a)
                base["applyAlignment"] = "1"
            }
            is StyleChange.NumberFormat -> {
                base["numFmtId"] = numFmtId(change.code).toString()
                base["applyNumberFormat"] = "1"
            }
            is StyleChange.Borders -> {
                val b = if (change.on) {
                    val side = { n: String -> "<$px$n style=\"thin\"><${px}color indexed=\"64\"/></$px$n>" }
                    Xml.parseElement("<${px}border>${side("left")}${side("right")}${side("top")}${side("bottom")}<${px}diagonal/></${px}border>")
                } else {
                    Xml.parseElement("<${px}border><${px}left/><${px}right/><${px}top/><${px}bottom/><${px}diagonal/></${px}border>")
                }
                base["borderId"] = addUnique("borders", "border", b).toString()
                base["applyBorder"] = "1"
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = cur
        return addUnique("cellXfs", "xf", base)
    }

    private fun ensureDefaults() {
        if (items("fonts", "font").isEmpty()) {
            section("fonts").children.add(Xml.parseElement("<${px}font><${px}sz val=\"11\"/><${px}name val=\"Calibri\"/></${px}font>"))
        }
        if (items("fills", "fill").isEmpty()) {
            section("fills").children.add(Xml.parseElement("<${px}fill><${px}patternFill patternType=\"none\"/></${px}fill>"))
            section("fills").children.add(Xml.parseElement("<${px}fill><${px}patternFill patternType=\"gray125\"/></${px}fill>"))
        }
        if (items("borders", "border").isEmpty()) {
            section("borders").children.add(Xml.parseElement("<${px}border><${px}left/><${px}right/><${px}top/><${px}bottom/><${px}diagonal/></${px}border>"))
        }
        if (items("cellXfs", "xf").isEmpty()) {
            section("cellXfs").children.add(Xml.parseElement("<${px}xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"))
        }
        fixCounts()
    }

    private fun editFont(xf: XElement, edit: (XElement) -> Unit) {
        if (items("fonts", "font").isEmpty()) ensureDefaults()
        val fonts = items("fonts", "font")
        val f = (fonts.getOrNull(xf["fontId"]?.toIntOrNull() ?: 0) ?: fonts.first()).deepCopy()
        edit(f)
        xf["fontId"] = addUnique("fonts", "font", f).toString()
        xf["applyFont"] = "1"
    }

    private val FONT_ORDER = listOf("b", "i", "strike", "condense", "extend", "outline", "shadow", "u", "vertAlign",
        "sz", "color", "name", "family", "charset", "scheme")

    private fun insertFontChild(f: XElement, e: XElement) {
        val rank = FONT_ORDER.indexOf(e.localName)
        val idx = f.children.indexOfFirst { it is XElement && FONT_ORDER.indexOf(it.localName).let { r -> r > rank || r < 0 } }
        if (idx >= 0) f.children.add(idx, e) else f.children.add(e)
    }

    private fun setFlag(f: XElement, name: String, on: Boolean) {
        f.children.removeAll { it is XElement && it.localName == name }
        if (on) insertFontChild(f, XElement(px + name))
    }

    /** Appends [e] to a section unless an identical entry exists; returns its index. */
    private fun addUnique(local: String, item: String, e: XElement): Int {
        val list = items(local, item)
        val key = e.toString()
        list.indexOfFirst { it.toString() == key }.let { if (it >= 0) return it }
        section(local).children.add(e)
        fixCounts()
        cache.clear()
        return list.size
    }

    private fun fixCounts() {
        for ((sec, item) in listOf("fonts" to "font", "fills" to "fill", "borders" to "border", "cellXfs" to "xf", "numFmts" to "numFmt")) {
            val s = root.childLocal(sec) ?: continue
            s["count"] = s.elements.count { it.localName == item }.toString()
        }
    }

    fun numFmtId(code: String): Int {
        NumberFormat.BUILTIN.entries.firstOrNull { it.value == code && it.key != 14 && it.key != 22 }?.let { return it.key }
        items("numFmts", "numFmt").firstOrNull { it["formatCode"] == code }?.let { return it["numFmtId"]!!.toInt() }
        val next = (items("numFmts", "numFmt").mapNotNull { it["numFmtId"]?.toIntOrNull() }.maxOrNull() ?: 163)
            .coerceAtLeast(163) + 1
        val e = XElement(px + "numFmt")
        e["numFmtId"] = next.toString()
        e["formatCode"] = code
        section("numFmts").children.add(e)
        fixCounts()
        return next
    }

    private fun argbHex(argb: Int) = String.format("FF%06X", argb and 0xFFFFFF)

    private fun fmtNum(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    companion object {
        /** The legacy 64-colour palette used by `indexed` colours. */
        val DEFAULT_PALETTE: List<Int> = listOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
            0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
            0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
            0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
            0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
            0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
            0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
        ).map { it or 0xFF000000.toInt() }
    }
}
