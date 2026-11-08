// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.pptx

import io.github.Prestgg.naroffice.core.History
import io.github.Prestgg.naroffice.docx.Numbering
import io.github.Prestgg.naroffice.ooxml.Colors
import io.github.Prestgg.naroffice.ooxml.OfficeFormatException
import io.github.Prestgg.naroffice.ooxml.OpcPackage
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.text.TextProps
import io.github.Prestgg.naroffice.xml.XDocument
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XText
import io.github.Prestgg.naroffice.xml.Xml
import java.io.OutputStream

/**
 * A PowerPoint presentation: resolved slide views for drawing, plus lossless editing.
 *
 * Every edit replaces whole elements (copy on write) through [history], so untouched XML is
 * written back exactly as it was read.
 */
class PptxDocument(val pkg: OpcPackage) {

    val presPart: String = pkg.mainPart() ?: throw OfficeFormatException("No main presentation part")
    private val pres: XElement = pkg.xml(presPart)?.root ?: throw OfficeFormatException("Missing $presPart")

    val history = History()

    /** Bumped on every change; the UI observes it. */
    var version = 0
        private set

    val isModified get() = history.changeCounter != 0

    /** Slide size in EMU (cx, cy). */
    val slideSize: Pair<Long, Long>
        get() {
            val s = pres.child("p:sldSz")
            return (s?.get("cx")?.toLongOrNull() ?: 9144000L) to (s?.get("cy")?.toLongOrNull() ?: 6858000L)
        }

    val slideCount: Int get() = slideParts().size

    private val viewCache = HashMap<Int, SlideView>()
    private var cacheVersion = -1
    private val phCache = HashMap<String, List<Ph>>()
    private val themeCache = HashMap<String, ThemeInfo>()

    private fun changed(part: String) {
        pkg.markDirty(part)
        version++
    }

    // ================================================================ structure

    private fun sldIdLst(): XElement? = pres.child("p:sldIdLst")

    private fun sldIds(): List<XElement> = sldIdLst()?.children("p:sldId") ?: emptyList()

    fun slideParts(): List<String> = sldIds().mapNotNull { s ->
        s["r:id"]?.let { pkg.target(presPart, it) }?.takeIf { pkg.exists(it) }
    }

    private fun slidePart(i: Int): String =
        slideParts().getOrNull(i) ?: throw IndexOutOfBoundsException("No slide $i")

    private fun layoutOf(slidePart: String): String? = pkg.targetsOfType(slidePart, "/slideLayout").firstOrNull()
    private fun masterOf(layoutPart: String): String? = pkg.targetsOfType(layoutPart, "/slideMaster").firstOrNull()

    fun partBytes(part: String): ByteArray? = pkg.bytes(part)

    fun layouts(): List<LayoutInfo> {
        val out = ArrayList<LayoutInfo>()
        val masters = pres.child("p:sldMasterIdLst")?.children("p:sldMasterId")
            ?.mapNotNull { it["r:id"]?.let { id -> pkg.target(presPart, id) } } ?: emptyList()
        for (m in masters) {
            val root = pkg.xml(m)?.root ?: continue
            val ids = root.child("p:sldLayoutIdLst")?.children("p:sldLayoutId") ?: emptyList()
            for (l in ids) {
                val part = l["r:id"]?.let { pkg.target(m, it) } ?: continue
                val lr = pkg.xml(part)?.root ?: continue
                out.add(LayoutInfo(part, lr.child("p:cSld")?.get("name") ?: "", lr["type"]))
            }
        }
        return out
    }

    fun notesText(i: Int): String {
        val notes = pkg.targetsOfType(slidePart(i), "/notesSlide").firstOrNull() ?: return ""
        val root = pkg.xml(notes)?.root ?: return ""
        val sb = StringBuilder()
        for (sp in root.descendants().filter { it.localName == "sp" }) {
            if (phOf(sp)?.type != "body") continue
            sp.child("p:txBody")?.children("a:p")?.forEach { p ->
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(PptxText.read(p).text.replace(SOFT_BREAK, '\n').filter { it != '￼' })
            }
        }
        return sb.toString()
    }

    // ================================================================ placeholders

    private class Ph(val type: String, val idx: String?, val el: XElement)

    private fun nvOf(el: XElement): XElement? = el.elements.firstOrNull { it.localName.startsWith("nv") }

    private fun phOf(el: XElement): Ph? {
        val ph = nvOf(el)?.child("p:nvPr")?.child("p:ph") ?: return null
        return Ph(ph["type"] ?: "obj", ph["idx"], el)
    }

    private fun cNvPr(el: XElement): XElement? = nvOf(el)?.childLocal("cNvPr")

    private fun placeholders(part: String): List<Ph> = phCache.getOrPut(part) {
        val tree = pkg.xml(part)?.root?.path("p:cSld", "p:spTree") ?: return@getOrPut emptyList()
        tree.descendants().filter { it.localName in SHAPES }.mapNotNull { phOf(it) }.toList()
    }

    private fun masterType(t: String) = when (t) {
        "ctrTitle", "title" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> t
        else -> "body"
    }

    private fun matchInLayout(layoutPart: String, ph: Ph): Ph? {
        val list = placeholders(layoutPart)
        if (ph.idx != null && ph.idx != "0") list.firstOrNull { it.idx == ph.idx }?.let { return it }
        list.firstOrNull { it.type == ph.type && (ph.idx == null || it.idx == null || it.idx == ph.idx) }?.let { return it }
        list.firstOrNull { it.type == ph.type }?.let { return it }
        val mt = masterType(ph.type)
        return list.firstOrNull { masterType(it.type) == mt && (mt != "body" || ph.idx == it.idx || ph.idx == null) }
    }

    private fun matchInMaster(masterPart: String, type: String): Ph? {
        val mt = masterType(type)
        return placeholders(masterPart).firstOrNull { masterType(it.type) == mt }
    }

    private class Inh(val el: XElement, val part: String)

    private enum class PartKind { SLIDE, LAYOUT, MASTER }

    /** The shape and the placeholders it inherits from, most specific first. */
    private fun chain(el: XElement, part: String, kind: PartKind): List<Inh> {
        val out = arrayListOf(Inh(el, part))
        val ph = phOf(el) ?: return out
        when (kind) {
            PartKind.SLIDE -> {
                val layout = layoutOf(part) ?: return out
                val lph = matchInLayout(layout, ph)
                if (lph != null) out.add(Inh(lph.el, layout))
                val master = masterOf(layout) ?: return out
                matchInMaster(master, lph?.type ?: ph.type)?.let { out.add(Inh(it.el, master)) }
            }
            PartKind.LAYOUT -> {
                val master = masterOf(part) ?: return out
                matchInMaster(master, ph.type)?.let { out.add(Inh(it.el, master)) }
            }
            PartKind.MASTER -> {}
        }
        return out
    }

    // ================================================================ theme & colours

    private class ThemeInfo(val theme: Colors.Theme, val root: XElement?)

    private fun themeFor(masterPart: String?): ThemeInfo {
        val key = masterPart ?: ""
        return themeCache.getOrPut(key) {
            val root = masterPart?.let { pkg.targetsOfType(it, "/theme").firstOrNull() }?.let { pkg.xml(it)?.root }
            ThemeInfo(Colors.Theme(root), root)
        }
    }

    private fun clrMapFor(slidePart: String?, layoutPart: String?, masterPart: String?): Map<String, String> {
        fun attrs(e: XElement?) = e?.attrs?.filterKeys { !it.startsWith("xmlns") }
        slidePart?.let { pkg.xml(it)?.root?.child("p:clrMapOvr")?.child("a:overrideClrMapping") }
            ?.let { return attrs(it)!! }
        layoutPart?.let { pkg.xml(it)?.root?.child("p:clrMapOvr")?.child("a:overrideClrMapping") }
            ?.let { return attrs(it)!! }
        return masterPart?.let { attrs(pkg.xml(it)?.root?.child("p:clrMap")) }
            ?: mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
    }

    /** Everything needed to resolve a shape in one part. */
    private inner class Ctx(
        val part: String,
        val kind: PartKind,
        val theme: ThemeInfo,
        val clrMap: Map<String, String>,
        val masterPart: String?,
        val slideNumber: Int,
    ) {
        fun color(parent: XElement?, phClr: Int? = null): Int? = Colors.drawing(parent, theme.theme, clrMap, phClr)

        fun font(face: String?): String? = when {
            face == null -> null
            face.startsWith("+mj") -> theme.theme.majorFont
            face.startsWith("+mn") -> theme.theme.minorFont
            else -> face
        }
    }

    // ================================================================ views

    fun slide(i: Int): SlideView {
        if (cacheVersion != version) {
            viewCache.clear(); cacheVersion = version
        }
        return viewCache.getOrPut(i) { buildSlide(i) }
    }

    private fun buildSlide(i: Int): SlideView {
        val part = slidePart(i)
        val root = pkg.xml(part)?.root ?: throw OfficeFormatException("Missing $part")
        val layout = layoutOf(part)
        val master = layout?.let { masterOf(it) }
        val theme = themeFor(master)
        val clrMap = clrMapFor(part, layout, master)
        val shapes = ArrayList<ShapeView>()
        val showMaster = root["showMasterSp"] != "0"
        val layoutRoot = layout?.let { pkg.xml(it)?.root }
        if (showMaster) {
            if (master != null && layoutRoot?.get("showMasterSp") != "0") {
                val ctx = Ctx(master, PartKind.MASTER, theme, clrMap, master, i + 1)
                pkg.xml(master)?.root?.path("p:cSld", "p:spTree")?.let { collect(it, ctx, Xf.ID, shapes, skipPh = true) }
            }
            if (layout != null) {
                val ctx = Ctx(layout, PartKind.LAYOUT, theme, clrMap, master, i + 1)
                layoutRoot?.path("p:cSld", "p:spTree")?.let { collect(it, ctx, Xf.ID, shapes, skipPh = true) }
            }
        }
        val ctx = Ctx(part, PartKind.SLIDE, theme, clrMap, master, i + 1)
        root.path("p:cSld", "p:spTree")?.let { collect(it, ctx, Xf.ID, shapes, skipPh = false) }

        val bg = background(root, part, ctx)
            ?: layoutRoot?.let { background(it, layout, ctx) }
            ?: master?.let { m -> pkg.xml(m)?.root?.let { background(it, m, ctx) } }
            ?: Fill.Solid(0xFFFFFFFF.toInt())
        return SlideView(i, part, bg, shapes, root["show"] == "0", layoutRoot?.child("p:cSld")?.get("name"))
    }

    private fun background(root: XElement, part: String, ctx: Ctx): Fill? {
        val bg = root.path("p:cSld", "p:bg") ?: return null
        bg.child("p:bgPr")?.let { return fill(it, part, ctx) }
        val ref = bg.child("p:bgRef") ?: return null
        val idx = ref["idx"]?.toIntOrNull() ?: 0
        val color = ctx.color(ref)
        if (idx >= 1001) {
            val list = ctx.theme.root?.path("a:themeElements", "a:fmtScheme", "a:bgFillStyleLst")
            val style = list?.elements?.toList()?.getOrNull(idx - 1001)
            if (style != null) {
                val holder = XElement("x").also { it.children.add(style) }
                val masterPart = ctx.masterPart?.let { m -> pkg.targetsOfType(m, "/theme").firstOrNull() } ?: part
                fill(holder, masterPart, ctx, color)?.let { return it }
            }
        }
        return color?.let { Fill.Solid(it) }
    }

    /** Group coordinate transform: abs = t + s * local. */
    private data class Xf(val sx: Double, val sy: Double, val tx: Double, val ty: Double) {
        fun x(v: Long) = (tx + sx * v).toLong()
        fun y(v: Long) = (ty + sy * v).toLong()
        fun then(inner: Xf) = Xf(sx * inner.sx, sy * inner.sy, tx + sx * inner.tx, ty + sy * inner.ty)

        companion object {
            val ID = Xf(1.0, 1.0, 0.0, 0.0)
        }
    }

    private fun groupXf(grp: XElement): Xf {
        val x = grp.child("p:grpSpPr")?.child("a:xfrm") ?: return Xf.ID
        val off = x.child("a:off"); val ext = x.child("a:ext")
        val chOff = x.child("a:chOff"); val chExt = x.child("a:chExt")
        val ox = off?.get("x")?.toDoubleOrNull() ?: 0.0
        val oy = off?.get("y")?.toDoubleOrNull() ?: 0.0
        val ex = ext?.get("cx")?.toDoubleOrNull() ?: 0.0
        val ey = ext?.get("cy")?.toDoubleOrNull() ?: 0.0
        val cox = chOff?.get("x")?.toDoubleOrNull() ?: ox
        val coy = chOff?.get("y")?.toDoubleOrNull() ?: oy
        val cex = chExt?.get("cx")?.toDoubleOrNull() ?: ex
        val cey = chExt?.get("cy")?.toDoubleOrNull() ?: ey
        val sx = if (cex != 0.0) ex / cex else 1.0
        val sy = if (cey != 0.0) ey / cey else 1.0
        return Xf(sx, sy, ox - cox * sx, oy - coy * sy)
    }

    private fun collect(container: XElement, ctx: Ctx, xf: Xf, out: MutableList<ShapeView>, skipPh: Boolean) {
        for (c in container.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "sp", "cxnSp", "pic", "graphicFrame" -> {
                    if (cNvPr(c)?.get("hidden") == "1") continue
                    if (skipPh && phOf(c) != null) continue
                    val v = try {
                        when (c.localName) {
                            "pic" -> picture(c, ctx, xf)
                            "graphicFrame" -> frameShape(c, ctx, xf)
                            else -> shape(c, ctx, xf)
                        }
                    } catch (e: RuntimeException) {
                        null // A malformed shape must not hide the rest of the slide.
                    }
                    v?.let { out.add(it) }
                }
                "grpSp" -> {
                    if (cNvPr(c)?.get("hidden") == "1") continue
                    collect(c, ctx, xf.then(groupXf(c)), out, skipPh)
                }
                "AlternateContent" -> {
                    val before = out.size
                    c.childLocal("Choice")?.let { collect(it, ctx, xf, out, skipPh) }
                    if (out.size == before) c.childLocal("Fallback")?.let { collect(it, ctx, xf, out, skipPh) }
                }
            }
        }
    }

    private class XfrmInfo(val frame: Frame, val rot: Float, val flipH: Boolean, val flipV: Boolean)

    private fun readXfrm(x: XElement?): XfrmInfo? {
        x ?: return null
        val off = x.child("a:off") ?: return null
        val ext = x.child("a:ext") ?: return null
        return XfrmInfo(
            Frame(
                off["x"]?.toLongOrNull() ?: 0, off["y"]?.toLongOrNull() ?: 0,
                ext["cx"]?.toLongOrNull() ?: 0, ext["cy"]?.toLongOrNull() ?: 0,
            ),
            (x["rot"]?.toFloatOrNull() ?: 0f) / 60000f, x["flipH"] == "1", x["flipV"] == "1",
        )
    }

    private fun spPrXfrm(el: XElement): XElement? =
        if (el.localName == "graphicFrame") el.child("p:xfrm") else el.childLocal("spPr")?.child("a:xfrm")

    private fun applyXf(f: Frame, xf: Xf) = Frame(xf.x(f.x), xf.y(f.y), (f.w * xf.sx).toLong(), (f.h * xf.sy).toLong())

    private fun kindOf(part: String): PartKind = when {
        part.contains("slideLayouts/") -> PartKind.LAYOUT
        part.contains("slideMasters/") -> PartKind.MASTER
        else -> PartKind.SLIDE
    }

    private fun shape(el: XElement, ctx: Ctx, xf: Xf): ShapeView? {
        val chain = chain(el, ctx.part, ctx.kind)
        val xi = chain.firstNotNullOfOrNull { readXfrm(spPrXfrm(it.el)) } ?: return null
        val ph = phOf(el)
        val nv = cNvPr(el)
        val nvId = nv?.get("id")?.toIntOrNull() ?: 0

        var geometry: String? = "rect"
        var paths: List<GeoPath>? = null
        for (inh in chain) {
            val spPr = inh.el.childLocal("spPr") ?: continue
            spPr.child("a:prstGeom")?.let { geometry = it["prst"] ?: "rect"; break }
            spPr.child("a:custGeom")?.let { cg ->
                val p = customPaths(cg)
                if (p != null) {
                    geometry = null; paths = p
                } else geometry = "rect"
                break
            }
        }

        val style = el.child("p:style")
        var fill: Fill? = null
        for (inh in chain) {
            val spPr = inh.el.childLocal("spPr") ?: continue
            fill = fill(spPr, inh.part, ctx)
            if (fill != null) break
        }
        if (fill == null) {
            val ref = style?.child("a:fillRef")
            val idx = ref?.get("idx")?.toIntOrNull() ?: 0
            fill = if (idx > 0) ctx.color(ref)?.let { Fill.Solid(it) } ?: Fill.None else Fill.None
        }

        val lnEl = chain.firstNotNullOfOrNull { it.el.childLocal("spPr")?.child("a:ln") }
        val line = line(lnEl, style, ctx)

        val txBody = el.child("p:txBody")
        val textStyle = textStyle(chain, ph)
        val fontColor = style?.child("a:fontRef")?.let { ctx.color(it) }
        val bodyPrs = chain.mapNotNull { it.el.child("p:txBody")?.child("a:bodyPr") }
        val editable = ctx.kind == PartKind.SLIDE
        val text = textBody(txBody, bodyPrs, textStyle, ctx, fontColor, header = false)
        val isText = ph != null || nvOf(el)?.child("p:cNvSpPr")?.get("txBox") == "1" ||
            (geometry == "rect" && fill == Fill.None && line == null && text != null)
        val kind = when {
            el.localName == "cxnSp" -> ShapeKind.SHAPE
            isText -> ShapeKind.TEXT
            else -> ShapeKind.SHAPE
        }
        return ShapeView(
            id = "${ctx.part}#$nvId", part = ctx.part, nvId = nvId, name = nv?.get("name") ?: "",
            editable = editable, kind = kind, frame = applyXf(xi.frame, xf), rotationDeg = xi.rot,
            flipH = xi.flipH, flipV = xi.flipV, geometry = geometry, paths = paths, fill = fill, line = line,
            imagePart = null, imageCrop = null, text = text, table = null,
            placeholder = ph?.let { PlaceholderInfo(it.type, it.idx) },
            isEmptyPlaceholder = editable && ph != null && (text == null || text.isEmpty),
            chartTitle = null,
        )
    }

    private fun picture(el: XElement, ctx: Ctx, xf: Xf): ShapeView? {
        val chain = chain(el, ctx.part, ctx.kind)
        val xi = chain.firstNotNullOfOrNull { readXfrm(spPrXfrm(it.el)) } ?: return null
        val nv = cNvPr(el)
        val nvId = nv?.get("id")?.toIntOrNull() ?: 0
        val blipFill = el.childLocal("blipFill")
        val blip = blipFill?.childLocal("blip")
        val embed = blip?.attrLocal("embed") ?: blip?.attrLocal("link")
        val media = embed?.let { pkg.target(ctx.part, it) }
        val spPr = el.childLocal("spPr")
        val geometry = spPr?.child("a:prstGeom")?.get("prst") ?: "rect"
        val line = line(spPr?.child("a:ln"), el.child("p:style"), ctx)
        return ShapeView(
            id = "${ctx.part}#$nvId", part = ctx.part, nvId = nvId, name = nv?.get("name") ?: "",
            editable = ctx.kind == PartKind.SLIDE, kind = ShapeKind.PICTURE, frame = applyXf(xi.frame, xf),
            rotationDeg = xi.rot, flipH = xi.flipH, flipV = xi.flipV, geometry = geometry, paths = null,
            fill = spPr?.let { fill(it, ctx.part, ctx) } ?: Fill.None, line = line,
            imagePart = media, imageCrop = crop(blipFill?.childLocal("srcRect")), text = null, table = null,
            placeholder = phOf(el)?.let { PlaceholderInfo(it.type, it.idx) }, isEmptyPlaceholder = false,
            chartTitle = null,
        )
    }

    private fun crop(src: XElement?): Crop? {
        src ?: return null
        fun v(a: String) = (src[a]?.toFloatOrNull() ?: 0f) / 100000f
        val c = Crop(v("l"), v("t"), v("r"), v("b"))
        return if (c.left == 0f && c.top == 0f && c.right == 0f && c.bottom == 0f) null else c
    }

    private fun frameShape(el: XElement, ctx: Ctx, xf: Xf): ShapeView? {
        val chain = chain(el, ctx.part, ctx.kind)
        val xi = chain.firstNotNullOfOrNull { readXfrm(spPrXfrm(it.el)) } ?: return null
        val nv = cNvPr(el)
        val nvId = nv?.get("id")?.toIntOrNull() ?: 0
        val data = el.path("a:graphic", "a:graphicData")
        val uri = data?.get("uri") ?: ""
        var kind = ShapeKind.OTHER
        var table: TableView? = null
        var chartTitle: String? = null
        val tbl = data?.child("a:tbl")
        if (tbl != null) {
            kind = ShapeKind.TABLE
            table = table(tbl, ctx)
        } else if (uri.endsWith("/chart")) {
            kind = ShapeKind.CHART
            val rid = data?.elements?.firstOrNull { it.localName == "chart" }?.attrLocal("id")
            val chartPart = rid?.let { pkg.target(ctx.part, it) }
            val title = chartPart?.let { pkg.xml(it)?.root }?.descendants()?.firstOrNull { it.localName == "title" }
            chartTitle = title?.descendants()?.filter { it.localName == "t" }?.joinToString("") { it.textContent }
                ?.takeIf { it.isNotBlank() }
        }
        return ShapeView(
            id = "${ctx.part}#$nvId", part = ctx.part, nvId = nvId, name = nv?.get("name") ?: "",
            editable = ctx.kind == PartKind.SLIDE, kind = kind, frame = applyXf(xi.frame, xf),
            rotationDeg = xi.rot, flipH = xi.flipH, flipV = xi.flipV, geometry = "rect", paths = null,
            fill = Fill.None, line = null, imagePart = null, imageCrop = null, text = null, table = table,
            placeholder = phOf(el)?.let { PlaceholderInfo(it.type, it.idx) }, isEmptyPlaceholder = false,
            chartTitle = chartTitle,
        )
    }

    private fun table(tbl: XElement, ctx: Ctx): TableView {
        val tblPr = tbl.child("a:tblPr")
        val styled = tblPr?.child("a:tableStyleId") != null || tblPr?.get("firstRow") == "1" || tblPr?.get("bandRow") == "1"
        val firstRow = tblPr?.get("firstRow") == "1"
        val bandRow = tblPr?.get("bandRow") == "1"
        val accent = ctx.theme.theme.color("accent1") ?: 0xFF4472C4.toInt()
        val white = 0xFFFFFFFF.toInt()
        val cols = tbl.child("a:tblGrid")?.children("a:gridCol")?.map { it["w"]?.toLongOrNull() ?: 0L } ?: emptyList()
        val style = TextStyle(listOf(presDefaults(), txStyle("p:otherStyle", ctx)))
        val rows = tbl.children("a:tr").mapIndexed { r, tr ->
            val cells = tr.children("a:tc").map { tc ->
                val tcPr = tc.child("a:tcPr")
                val explicit = tcPr?.let { fill(it, ctx.part, ctx) }
                val header = styled && firstRow && r == 0
                val dataRow = if (firstRow) r - 1 else r
                val f = explicit ?: when {
                    !styled -> Fill.None
                    header -> Fill.Solid(accent)
                    bandRow && dataRow % 2 == 0 -> Fill.Solid(Colors.mix(accent, white, 0.6f))
                    else -> Fill.Solid(Colors.mix(accent, white, 0.8f))
                }
                val defBorder = if (styled) Line(white, 12700, null) else null
                fun border(n: String): Line? {
                    val ln = tcPr?.child(n) ?: return defBorder
                    if (ln.child("a:noFill") != null) return null
                    val c = ctx.color(ln.child("a:solidFill")) ?: return defBorder
                    return Line(c, ln["w"]?.toLongOrNull() ?: 12700, ln.child("a:prstDash")?.get("val"))
                }
                val bodyPr = XElement("a:bodyPr").also { b ->
                    b["lIns"] = tcPr?.get("marL") ?: "91440"; b["rIns"] = tcPr?.get("marR") ?: "91440"
                    b["tIns"] = tcPr?.get("marT") ?: "45720"; b["bIns"] = tcPr?.get("marB") ?: "45720"
                    b["anchor"] = tcPr?.get("anchor") ?: "t"
                }
                val text = textBody(tc.child("a:txBody"), listOf(bodyPr), style, ctx,
                    if (header && explicit == null) white else null, header = header && explicit == null)
                    ?: emptyBody(bodyPr)
                CellView(
                    text, f, tc["gridSpan"]?.toIntOrNull() ?: 1, tc["rowSpan"]?.toIntOrNull() ?: 1,
                    tc["hMerge"] == "1", tc["vMerge"] == "1",
                    border("a:lnL"), border("a:lnT"), border("a:lnR"), border("a:lnB"),
                )
            }
            RowView(tr["h"]?.toLongOrNull() ?: 370840, cells)
        }
        return TableView(cols, rows)
    }

    private fun emptyBody(bodyPr: XElement) = TextBodyView(
        emptyList(), insets(listOf(bodyPr)), Anchor.TOP, true, 1f, 0f, null, false,
    )

    // ---------------------------------------------------------------- fills and lines

    /** Reads the fill child of [parent], or null when it has none. */
    private fun fill(parent: XElement, part: String, ctx: Ctx, phClr: Int? = null): Fill? {
        for (c in parent.elements) {
            when (c.localName) {
                "noFill" -> return Fill.None
                "solidFill" -> return ctx.color(c, phClr)?.let { Fill.Solid(it) } ?: Fill.None
                "gradFill" -> {
                    val stops = c.child("a:gsLst")?.children("a:gs")?.mapNotNull { gs ->
                        val col = ctx.color(gs, phClr) ?: return@mapNotNull null
                        GradientStop((gs["pos"]?.toFloatOrNull() ?: 0f) / 100000f, col)
                    }?.sortedBy { it.pos } ?: emptyList()
                    if (stops.isEmpty()) return Fill.None
                    if (stops.size == 1) return Fill.Solid(stops[0].argb)
                    val ang = (c.child("a:lin")?.get("ang")?.toFloatOrNull() ?: 0f) / 60000f
                    return Fill.Gradient(stops, ang, c.child("a:path") != null)
                }
                "blipFill" -> {
                    val id = c.childLocal("blip")?.attrLocal("embed") ?: return Fill.None
                    val target = pkg.target(part, id) ?: return Fill.None
                    return Fill.Image(target, crop(c.childLocal("srcRect")))
                }
                "pattFill" -> return ctx.color(c.child("a:fgClr"), phClr)?.let { Fill.Solid(it) } ?: Fill.None
                "grpFill" -> return Fill.None
            }
        }
        return null
    }

    private fun line(ln: XElement?, style: XElement?, ctx: Ctx): Line? {
        val ref = style?.child("a:lnRef")
        val refIdx = ref?.get("idx")?.toIntOrNull() ?: 0
        val refColor = if (refIdx > 0) ctx.color(ref) else null
        val refWidth = if (refIdx > 0) {
            ctx.theme.root?.path("a:themeElements", "a:fmtScheme", "a:lnStyleLst")?.children("a:ln")
                ?.getOrNull(refIdx - 1)?.get("w")?.toLongOrNull() ?: 9525L
        } else null
        if (ln?.child("a:noFill") != null) return null
        val color = ln?.child("a:solidFill")?.let { ctx.color(it) }
            ?: ln?.child("a:gradFill")?.child("a:gsLst")?.children("a:gs")?.firstOrNull()?.let { ctx.color(it) }
            ?: refColor ?: return null
        val w = ln?.get("w")?.toLongOrNull() ?: refWidth ?: 9525L
        return Line(color, w, ln?.child("a:prstDash")?.get("val"))
    }

    private fun customPaths(cg: XElement): List<GeoPath>? {
        val out = ArrayList<GeoPath>()
        for (path in cg.child("a:pathLst")?.children("a:path") ?: return null) {
            val w = path["w"]?.toFloatOrNull()?.takeIf { it > 0 } ?: return null
            val h = path["h"]?.toFloatOrNull()?.takeIf { it > 0 } ?: return null
            val cmds = ArrayList<PathCmd>()
            fun pts(e: XElement): List<Pair<Float, Float>>? = e.children("a:pt").map { pt ->
                val x = pt["x"]?.toFloatOrNull() ?: return null
                val y = pt["y"]?.toFloatOrNull() ?: return null
                x / w to y / h
            }
            for (c in path.elements) {
                when (c.localName) {
                    "moveTo" -> pts(c)?.firstOrNull()?.let { cmds.add(PathCmd.MoveTo(it.first, it.second)) } ?: return null
                    "lnTo" -> pts(c)?.firstOrNull()?.let { cmds.add(PathCmd.LineTo(it.first, it.second)) } ?: return null
                    "cubicBezTo" -> {
                        val p = pts(c)?.takeIf { it.size == 3 } ?: return null
                        cmds.add(PathCmd.CubicTo(p[0].first, p[0].second, p[1].first, p[1].second, p[2].first, p[2].second))
                    }
                    "quadBezTo" -> {
                        val p = pts(c)?.takeIf { it.size == 2 } ?: return null
                        cmds.add(PathCmd.QuadTo(p[0].first, p[0].second, p[1].first, p[1].second))
                    }
                    "arcTo" -> {
                        val wr = c["wR"]?.toFloatOrNull() ?: return null
                        val hr = c["hR"]?.toFloatOrNull() ?: return null
                        val st = c["stAng"]?.toFloatOrNull() ?: return null
                        val sw = c["swAng"]?.toFloatOrNull() ?: return null
                        cmds.add(PathCmd.ArcTo(wr / w, hr / h, st / 60000f, sw / 60000f))
                    }
                    "close" -> cmds.add(PathCmd.Close)
                }
            }
            out.add(GeoPath(cmds, path["fill"] != "none", path["stroke"] != "0" && path["stroke"] != "false"))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- text

    /** List-style containers (each holding a:lvlNpPr), lowest priority first. */
    private class TextStyle(val lists: List<XElement?>) {
        fun layers(lvl: Int): List<XElement> {
            val out = ArrayList<XElement>()
            for ((i, l) in lists.withIndex()) {
                l ?: continue
                if (i == 0) l.child("a:defPPr")?.let { out.add(it) }
                l.child("a:lvl${lvl + 1}pPr")?.let { out.add(it) }
            }
            return out
        }
    }

    private fun presDefaults(): XElement? = pres.child("p:defaultTextStyle")

    private fun txStyle(name: String, ctx: Ctx): XElement? =
        ctx.masterPart?.let { pkg.xml(it)?.root }?.child("p:txStyles")?.child(name)

    private fun textStyle(chain: List<Inh>, ph: Ph?): TextStyle {
        val masterPart = chain.firstOrNull { kindOf(it.part) == PartKind.MASTER }?.part ?: masterPartFor(chain.first().part)
        val master = masterPart?.let { pkg.xml(it)?.root }
        val category = when (ph?.let { masterType(it.type) }) {
            "title" -> "p:titleStyle"
            "body" -> "p:bodyStyle"
            else -> "p:otherStyle"
        }
        val lists = ArrayList<XElement?>()
        lists.add(presDefaults())
        lists.add(master?.child("p:txStyles")?.child(category))
        for (inh in chain.asReversed()) lists.add(inh.el.child("p:txBody")?.child("a:lstStyle"))
        return TextStyle(lists)
    }

    private fun masterPartFor(part: String): String? = when (kindOf(part)) {
        PartKind.MASTER -> part
        PartKind.LAYOUT -> masterOf(part)
        PartKind.SLIDE -> layoutOf(part)?.let { masterOf(it) }
    }

    private fun insets(bodyPrs: List<XElement>): Insets {
        fun a(n: String, d: Long) = bodyPrs.firstNotNullOfOrNull { it[n]?.toLongOrNull() } ?: d
        return Insets(a("lIns", 91440), a("tIns", 45720), a("rIns", 91440), a("bIns", 45720))
    }

    private fun textBody(
        txBody: XElement?,
        bodyPrs: List<XElement>,
        style: TextStyle,
        ctx: Ctx,
        fontColor: Int?,
        header: Boolean,
    ): TextBodyView? {
        txBody ?: return null
        fun attr(n: String) = bodyPrs.firstNotNullOfOrNull { it[n] }
        val autofit = bodyPrs.firstNotNullOfOrNull { b ->
            b.elements.firstOrNull { it.localName in setOf("normAutofit", "spAutoFit", "noAutofit") }
        }
        val fontScale = autofit?.takeIf { it.localName == "normAutofit" }?.get("fontScale")?.toFloatOrNull()?.div(100000f) ?: 1f
        val lnRed = autofit?.takeIf { it.localName == "normAutofit" }?.get("lnSpcReduction")?.toFloatOrNull()?.div(100000f) ?: 0f
        val anchor = when (attr("anchor")) {
            "ctr" -> Anchor.CENTER
            "b" -> Anchor.BOTTOM
            else -> Anchor.TOP
        }
        val paras = ArrayList<ParaView>()
        val counters = IntArray(9)
        val schemes = arrayOfNulls<String>(9)
        for (p in txBody.children("a:p")) {
            val pPr = p.child("a:pPr")
            val lvl = (pPr?.get("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val layers = style.layers(lvl) + listOfNotNull(pPr)
            fun pAttr(n: String) = layers.asReversed().firstNotNullOfOrNull { it[n] }
            fun pChild(vararg n: String) = layers.asReversed().firstNotNullOfOrNull { l ->
                l.elements.firstOrNull { it.localName in n }
            }
            val defRPrs = layers.mapNotNull { it.child("a:defRPr") }

            val runs = ArrayList<RunView>()
            for (c in p.children) {
                if (c !is XElement) continue
                when (c.localName) {
                    "r" -> {
                        val t = c.child("a:t")?.textContent ?: ""
                        if (t.isNotEmpty()) runs.add(RunView(t, runProps(defRPrs + listOfNotNull(c.child("a:rPr")), ctx, fontColor, header)))
                    }
                    "br" -> runs.add(RunView(SOFT_BREAK.toString(), runProps(defRPrs + listOfNotNull(c.child("a:rPr")), ctx, fontColor, header)))
                    "fld" -> {
                        val t = if (c["type"] == "slidenum") ctx.slideNumber.toString() else c.child("a:t")?.textContent ?: ""
                        if (t.isNotEmpty()) runs.add(RunView(t, runProps(defRPrs + listOfNotNull(c.child("a:rPr")), ctx, fontColor, header)))
                    }
                }
            }
            val endProps = runProps(defRPrs + listOfNotNull(p.child("a:endParaRPr")), ctx, fontColor, header)
            val firstSize = runs.firstOrNull()?.props?.sizePt ?: endProps.sizePt
            val hasText = runs.any { it.text.isNotBlank() }

            // Bullets.
            var bullet: BulletView? = null
            val bu = pChild("buNone", "buChar", "buAutoNum", "buBlip")
            if (bu?.localName == "buAutoNum") {
                val scheme = bu["type"] ?: "arabicPeriod"
                if (hasText) {
                    counters[lvl] = if (schemes[lvl] == scheme) counters[lvl] + 1 else (bu["startAt"]?.toIntOrNull() ?: 1)
                    schemes[lvl] = scheme
                }
            } else if (hasText) {
                schemes[lvl] = null
            }
            if (hasText) for (k in lvl + 1 until 9) schemes[k] = null
            if (hasText && bu != null && bu.localName != "buNone") {
                val clr = pChild("buClr", "buClrTx")?.takeIf { it.localName == "buClr" }?.let { ctx.color(it) }
                val sz = pChild("buSzPct", "buSzTx", "buSzPts")?.let {
                    when (it.localName) {
                        "buSzPct" -> (it["val"]?.toFloatOrNull() ?: 100000f) / 100000f
                        "buSzPts" -> ((it["val"]?.toFloatOrNull() ?: 0f) / 100f) / firstSize
                        else -> 1f
                    }
                } ?: 1f
                val font = pChild("buFont", "buFontTx")?.takeIf { it.localName == "buFont" }?.get("typeface")
                val text = when (bu.localName) {
                    "buChar" -> bulletChar(bu["char"] ?: "•", font)
                    "buAutoNum" -> autoNumber(counters[lvl], bu["type"] ?: "arabicPeriod")
                    else -> "•"
                }
                bullet = BulletView(text, clr, sz, font?.takeIf { bu.localName == "buAutoNum" })
            }

            fun spacing(n: String): Float {
                val s = pChild(n) ?: return 0f
                s.child("a:spcPts")?.get("val")?.toFloatOrNull()?.let { return it / 100f }
                s.child("a:spcPct")?.get("val")?.toFloatOrNull()?.let { return it / 100000f * firstSize }
                return 0f
            }
            val lnSpc = pChild("lnSpc")
            paras.add(
                ParaView(
                    runs = runs,
                    align = pAttr("algn")?.let(TextPara::parseAlign) ?: Align.START,
                    level = lvl,
                    bullet = bullet,
                    marLEmu = pAttr("marL")?.toLongOrNull() ?: 0,
                    indentEmu = pAttr("indent")?.toLongOrNull() ?: 0,
                    spaceBeforePt = spacing("spcBef"),
                    spaceAfterPt = spacing("spcAft"),
                    lineSpacingPct = lnSpc?.child("a:spcPct")?.get("val")?.toFloatOrNull()?.div(100000f),
                    lineSpacingPt = lnSpc?.child("a:spcPts")?.get("val")?.toFloatOrNull()?.div(100f),
                    endSizePt = endProps.sizePt,
                ),
            )
        }
        return TextBodyView(
            paras, insets(bodyPrs), anchor, attr("wrap") != "none", fontScale, lnRed,
            attr("vert")?.takeIf { it != "horz" }, attr("anchorCtr") == "1",
        )
    }

    private fun runProps(layers: List<XElement>, ctx: Ctx, fontColor: Int?, header: Boolean): TextProps {
        var p = TextProps(sizePt = 18f)
        var colorSet = false
        for ((li, l) in layers.withIndex()) {
            // A shape style's font colour beats inherited list styles, not the run's own colour.
            val colorAllowed = fontColor == null || li == layers.size - 1
            l["sz"]?.toFloatOrNull()?.let { p = p.copy(sizePt = it / 100f) }
            l["b"]?.let { p = p.copy(bold = it == "1" || it == "true") }
            l["i"]?.let { p = p.copy(italic = it == "1" || it == "true") }
            l["u"]?.let { p = p.copy(underline = it != "none") }
            l["strike"]?.let { p = p.copy(strike = it != "noStrike") }
            l["cap"]?.let { p = p.copy(caps = it == "all" || it == "small") }
            l["baseline"]?.toIntOrNull()?.let {
                p = p.copy(baseline = when {
                    it > 0 -> Baseline.SUPER
                    it < 0 -> Baseline.SUB
                    else -> Baseline.NORMAL
                })
            }
            if (colorAllowed) l.child("a:solidFill")?.let { f -> ctx.color(f)?.let { p = p.copy(color = it); colorSet = true } }
            if (colorAllowed) l.child("a:gradFill")?.child("a:gsLst")?.children("a:gs")?.firstOrNull()?.let { g ->
                ctx.color(g)?.let { p = p.copy(color = it); colorSet = true }
            }
            l.child("a:latin")?.get("typeface")?.let { f -> ctx.font(f)?.let { p = p.copy(font = it) } }
            l.child("a:highlight")?.let { h -> ctx.color(h)?.let { p = p.copy(highlight = it) } }
            l.child("a:hlinkClick")?.attrLocal("id")?.takeIf { it.isNotEmpty() }?.let { id ->
                pkg.rel(ctx.part, id)?.let { r -> p = p.copy(link = r.target) }
            }
        }
        if (header) p = p.copy(bold = true, color = fontColor ?: p.color)
        else if (!colorSet) p = p.copy(color = fontColor ?: ctx.theme.theme.color(ctx.clrMap["tx1"] ?: "dk1"))
        if (p.font == null) p = p.copy(font = ctx.theme.theme.minorFont)
        return p
    }

    private fun bulletChar(c: String, font: String?): String {
        if (font == null || !font.startsWith("Wingdings")) return c
        return when (c) {
            "§" -> "▪"
            "Ø" -> "➢"
            "ü" -> "✓"
            "q" -> "❑"
            "v" -> "❖"
            "n" -> "■"
            "l" -> "●"
            "o" -> "□"
            "Ÿ" -> "•"
            else -> "•"
        }
    }

    private fun autoNumber(n: Int, scheme: String): String {
        val v = when {
            scheme.startsWith("alphaLc") -> Numbering.format(n, "lowerLetter")
            scheme.startsWith("alphaUc") -> Numbering.format(n, "upperLetter")
            scheme.startsWith("romanLc") -> Numbering.format(n, "lowerRoman")
            scheme.startsWith("romanUc") -> Numbering.format(n, "upperRoman")
            else -> n.toString()
        }
        return when {
            scheme.endsWith("ParenBoth") -> "($v)"
            scheme.endsWith("ParenR") -> "$v)"
            scheme.endsWith("Period") -> "$v."
            scheme.endsWith("Minus") -> "- $v -"
            else -> v
        }
    }

    // ================================================================ editing: shapes

    /** Parses "part#id"; the part must be slide [slideIndex]. */
    private fun locate(slideIndex: Int, shapeId: String): Triple<String, XElement, List<XElement>> {
        val part = slidePart(slideIndex)
        val sp = shapeId.substringBeforeLast('#')
        val nvId = shapeId.substringAfterLast('#').toIntOrNull()
        require(sp == part && nvId != null) { "Shape $shapeId is not on slide $slideIndex" }
        val tree = pkg.xml(part)?.root?.path("p:cSld", "p:spTree") ?: error("Slide has no shape tree")
        val path = findPath(tree, nvId) ?: error("Shape $shapeId not found")
        return Triple(part, path.last(), path)
    }

    /** Path from the shape tree down to the shape with cNvPr [nvId] (inclusive). */
    private fun findPath(container: XElement, nvId: Int): List<XElement>? {
        for (c in container.children) {
            if (c !is XElement) continue
            when (c.localName) {
                in SHAPES -> if (cNvPr(c)?.get("id")?.toIntOrNull() == nvId) return listOf(container, c)
                "grpSp" -> {
                    if (cNvPr(c)?.get("id")?.toIntOrNull() == nvId) return listOf(container, c)
                    findPath(c, nvId)?.let { return listOf(container) + it }
                }
                "AlternateContent" -> c.childLocal("Choice")?.let { ch -> findPath(ch, nvId)?.let { return listOf(container) + it } }
            }
        }
        return null
    }

    private fun replaceShape(part: String, parent: XElement, old: XElement, new: XElement?) {
        val idx = parent.children.indexOfFirst { it === old }
        require(idx >= 0)
        history.splice(parent, idx, 1, listOfNotNull(new)) { changed(part) }
    }

    /** Paragraphs of a slide shape, ready for editing. */
    fun editableText(slideIndex: Int, shapeId: String): List<TextPara> {
        val (_, el, _) = locate(slideIndex, shapeId)
        val body = el.child("p:txBody") ?: return listOf(TextPara.empty())
        return body.children("a:p").map(PptxText::read).ifEmpty { listOf(TextPara.empty()) }
    }

    /** Writes edited paragraphs back, keeping bodyPr and lstStyle. Undoable. */
    fun setText(slideIndex: Int, shapeId: String, paras: List<TextPara>) {
        val (part, el, path) = locate(slideIndex, shapeId)
        val copy = el.deepCopy()
        val old = copy.child("p:txBody")
        val body = XElement("p:txBody", old?.attrs?.let { LinkedHashMap(it) } ?: LinkedHashMap())
        if (old != null) {
            old.children.forEach { if (!(it is XElement && it.name == "a:p")) body.children.add(it) }
        } else {
            body.children.add(XElement("a:bodyPr"))
            body.children.add(XElement("a:lstStyle"))
        }
        val ps = paras.ifEmpty { listOf(TextPara.empty()) }.map(PptxText::write)
        // a:p elements come after bodyPr/lstStyle and before any extLst.
        val ext = body.children.indexOfFirst { it is XElement && it.localName == "extLst" }
        if (ext >= 0) body.children.addAll(ext, ps) else body.children.addAll(ps)
        if (old != null) {
            copy.children[copy.children.indexOf(old)] = body
        } else {
            val after = copy.children.indexOfLast { it is XElement && it.localName in setOf("nvSpPr", "spPr", "style") }
            copy.children.add(after + 1, body)
        }
        replaceShape(part, path[path.size - 2], el, copy)
    }

    /** Effective formatting of a run with [fmt] at [level] in a slide shape (for toolbar state). */
    fun resolveRun(slideIndex: Int, shapeId: String, level: Int, fmt: ARunFmt): TextProps {
        val (part, el, _) = locate(slideIndex, shapeId)
        val ctx = slideCtx(slideIndex, part)
        val style = textStyle(chain(el, part, PartKind.SLIDE), phOf(el))
        val layers = style.layers(level.coerceIn(0, 8)).mapNotNull { it.child("a:defRPr") } + listOfNotNull(fmt.rPr)
        val fontColor = el.child("p:style")?.child("a:fontRef")?.let { ctx.color(it) }
        return runProps(layers, ctx, fontColor, header = false)
    }

    private fun slideCtx(i: Int, part: String): Ctx {
        val layout = layoutOf(part)
        val master = layout?.let { masterOf(it) }
        return Ctx(part, PartKind.SLIDE, themeFor(master), clrMapFor(part, layout, master), master, i + 1)
    }

    /** Moves/resizes a slide shape; [frame] is in absolute slide EMU. Undoable. */
    fun setFrame(slideIndex: Int, shapeId: String, frame: Frame) {
        val (part, el, path) = locate(slideIndex, shapeId)
        var xf = Xf.ID
        for (a in path.dropLast(1)) if (a.localName == "grpSp") xf = xf.then(groupXf(a))
        val local = Frame(
            ((frame.x - xf.tx) / xf.sx).toLong(), ((frame.y - xf.ty) / xf.sy).toLong(),
            (frame.w / xf.sx).toLong(), (frame.h / xf.sy).toLong(),
        )
        val copy = el.deepCopy()
        val holder = when (copy.localName) {
            "graphicFrame" -> copy
            "grpSp" -> copy.getOrCreate("p:grpSpPr")
            else -> copy.childLocal("spPr") ?: XElement("p:spPr").also {
                val nvIdx = copy.children.indexOfFirst { c -> c is XElement && c.localName.startsWith("nv") }
                copy.children.add(nvIdx + 1, it)
            }
        }
        val xName = if (copy.localName == "graphicFrame") "p:xfrm" else "a:xfrm"
        val x = holder.child(xName) ?: XElement(xName).also { holder.children.add(0, it) }
        val off = x.child("a:off") ?: XElement("a:off").also { x.children.add(0, it) }
        off["x"] = local.x.toString(); off["y"] = local.y.toString()
        val ext = x.child("a:ext") ?: XElement("a:ext").also { x.children.add(x.children.indexOf(off) + 1, it) }
        ext["cx"] = local.w.coerceAtLeast(0).toString(); ext["cy"] = local.h.coerceAtLeast(0).toString()
        replaceShape(part, path[path.size - 2], el, copy)
    }

    fun deleteShape(slideIndex: Int, shapeId: String) {
        val (part, el, path) = locate(slideIndex, shapeId)
        replaceShape(part, path[path.size - 2], el, null)
    }

    private fun nextShapeId(root: XElement): Int =
        (root.descendants().filter { it.localName == "cNvPr" }.mapNotNull { it["id"]?.toIntOrNull() }.maxOrNull() ?: 1) + 1

    private fun insertShape(part: String, el: XElement) {
        val root = pkg.xml(part)!!.root
        ensureNamespaces(root)
        val tree = root.path("p:cSld", "p:spTree") ?: error("Slide has no shape tree")
        val ext = tree.children.indexOfFirst { it is XElement && it.localName == "extLst" }
        val idx = if (ext >= 0) ext else tree.children.size
        history.splice(tree, idx, 0, listOf(el)) { changed(part) }
    }

    private fun ensureNamespaces(root: XElement) {
        for ((k, v) in NS) if (root[k] == null) root[k] = v
    }

    /** Adds a text box and returns its shape id. Undoable. */
    fun addTextBox(slideIndex: Int, frame: Frame, text: String = ""): String {
        val part = slidePart(slideIndex)
        val id = nextShapeId(pkg.xml(part)!!.root)
        val sp = Xml.parseElement(
            """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="TextBox ${id - 1}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="${frame.x}" y="${frame.y}"/><a:ext cx="${frame.w}" cy="${frame.h}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0"><a:spAutoFit/></a:bodyPr><a:lstStyle/><a:p><a:endParaRPr lang="az-Latn-AZ" dirty="0"/></a:p></p:txBody></p:sp>""",
        )
        if (text.isNotEmpty()) {
            val p = sp.path("p:txBody", "a:p")!!
            p.children.add(0, XElement("a:r").also { r ->
                r.children.add(XElement("a:rPr").also { it["lang"] = "az-Latn-AZ"; it["dirty"] = "0" })
                r.children.add(XElement("a:t").also { it.children.add(XText(text)) })
            })
        }
        insertShape(part, sp)
        return "$part#$id"
    }

    /**
     * Adds a picture; it is centred and fitted into 60 % of the slide unless [frame] is given.
     * Returns the new shape id. Undoable (the media part stays in the package).
     */
    fun addPicture(slideIndex: Int, data: ByteArray, ext: String, widthPx: Int, heightPx: Int, frame: Frame? = null): String {
        val part = slidePart(slideIndex)
        val e = ext.lowercase().let { if (it == "jpeg") "jpg" else it }
        var n = 1
        while (pkg.exists("ppt/media/image$n.$e")) n++
        val media = "ppt/media/image$n.$e"
        pkg.putBytes(media, data, OpcPackage.MEDIA_TYPES[e] ?: "image/$e")
        val rid = pkg.addRel(part, IMAGE_REL, relTarget(part, media))
        val f = frame ?: run {
            val (sw, sh) = slideSize
            val maxW = sw * 0.6; val maxH = sh * 0.6
            val ratio = if (widthPx > 0 && heightPx > 0) widthPx.toDouble() / heightPx else 4.0 / 3
            var w = maxW; var h = w / ratio
            if (h > maxH) { h = maxH; w = h * ratio }
            Frame(((sw - w) / 2).toLong(), ((sh - h) / 2).toLong(), w.toLong(), h.toLong())
        }
        val id = nextShapeId(pkg.xml(part)!!.root)
        val pic = Xml.parseElement(
            """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture ${id - 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="${f.x}" y="${f.y}"/><a:ext cx="${f.w}" cy="${f.h}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""",
        )
        insertShape(part, pic)
        return "$part#$id"
    }

    // ================================================================ editing: slides

    private fun freePart(prefix: String, suffix: String): String {
        var n = 1
        while (pkg.exists("$prefix$n$suffix")) n++
        return "$prefix$n$suffix"
    }

    private fun nextPresRelId(): String {
        val ids = pkg.rels(presPart).map { it.id }.toSet()
        var k = ids.size + 1
        while ("rId$k" in ids) k++
        return "rId$k"
    }

    private fun ensureSldIdLst(): XElement {
        sldIdLst()?.let { return it }
        val lst = XElement("p:sldIdLst")
        val after = pres.children.indexOfLast {
            it is XElement && it.localName in setOf("sldMasterIdLst", "notesMasterIdLst", "handoutMasterIdLst")
        }
        pres.children.add(after + 1, lst)
        pkg.markDirty(presPart)
        return lst
    }

    /** Inserts a prepared slide part as slide number [at]; returns its index. */
    private fun insertSlide(part: String, doc: XDocument, rels: XDocument?, at: Int): Int {
        val lst = ensureSldIdLst()
        val rid = nextPresRelId()
        val newId = ((sldIds().mapNotNull { it["id"]?.toLongOrNull() }.maxOrNull() ?: 255L) + 1).coerceAtLeast(256L)
        val sldId = XElement("p:sldId").also { it["id"] = newId.toString(); it["r:id"] = rid }
        val relsPart = pkg.relsPartOf(part)
        val index = at.coerceIn(0, sldIds().size)
        val step = History.Lambda(
            u = {
                lst.children.removeAll { it === sldId }
                pkg.removeRel(presPart, rid)
                pkg.remove(part)
                if (rels != null) pkg.remove(relsPart)
                changed(presPart)
            },
            r = {
                pkg.putXml(part, doc, CT_SLIDE)
                if (rels != null) pkg.putXml(relsPart, rels, CT_RELS)
                pkg.addRel(presPart, SLIDE_REL, relTarget(presPart, part), rid)
                insertElementAt(lst, sldId, index)
                changed(presPart)
            },
        )
        step.redo()
        history.push(step)
        return index
    }

    private fun insertElementAt(parent: XElement, el: XElement, elementIndex: Int) {
        val els = parent.children.withIndex().filter { it.value is XElement }
        val pos = els.getOrNull(elementIndex)?.index ?: parent.children.size
        parent.children.add(pos, el)
    }

    /** Adds a new slide using [layoutPart] at position [at]; returns its index. Undoable. */
    fun addSlide(layoutPart: String, at: Int = slideCount): Int {
        require(pkg.xml(layoutPart) != null) { "No layout $layoutPart" }
        val part = freePart("ppt/slides/slide", ".xml")
        val sb = StringBuilder()
        var id = 2
        for (ph in placeholders(layoutPart)) {
            if (ph.type in setOf("dt", "ftr", "sldNum", "hdr")) continue
            val src = nvOf(ph.el)?.child("p:nvPr")?.child("p:ph") ?: continue
            val phAttrs = src.attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" }
            val name = StringBuilder().also { Xml.escape(cNvPr(ph.el)?.get("name") ?: "Placeholder $id", it, attr = true) }
            sb.append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph$phAttrs/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="az-Latn-AZ" dirty="0"/></a:p></p:txBody></p:sp>""")
            id++
        }
        val doc = Xml.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>$sb</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""",
        )
        val rels = Xml.parse(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$LAYOUT_REL" Target="${relTarget(part, layoutPart)}"/></Relationships>""",
        )
        return insertSlide(part, doc, rels, at)
    }

    /** Copies slide [i] right after itself; returns the new index. Undoable. */
    fun duplicateSlide(i: Int): Int {
        val src = slidePart(i)
        val part = freePart("ppt/slides/slide", ".xml")
        val doc = Xml.parse(pkg.bytes(src) ?: error("Missing $src"))
        val relsSrc = pkg.bytes(pkg.relsPartOf(src))
        val rels = relsSrc?.let { Xml.parse(it) }
        rels?.root?.children?.removeAll {
            it is XElement && (it["Type"]?.endsWith("/notesSlide") == true || it["Type"]?.endsWith("/comments") == true)
        }
        // Targets are relative to the slides folder, which does not change.
        return insertSlide(part, doc, rels, i + 1)
    }

    /** Removes slide [i] together with its notes. Undoable. */
    fun deleteSlide(i: Int) {
        val lst = sldIdLst() ?: return
        val sldId = sldIds().getOrNull(i) ?: return
        val rid = sldId["r:id"] ?: return
        val rel = pkg.rel(presPart, rid) ?: return
        val part = pkg.resolve(presPart, rel.target)
        val doc = pkg.xml(part) ?: return
        val ct = pkg.contentType(part) ?: CT_SLIDE
        val relsPart = pkg.relsPartOf(part)
        val rels = pkg.xml(relsPart)
        val notes = pkg.targetsOfType(part, "/notesSlide").firstOrNull()
        val notesDoc = notes?.let { pkg.xml(it) }
        val notesRelsPart = notes?.let { pkg.relsPartOf(it) }
        val notesRels = notesRelsPart?.let { pkg.xml(it) }
        val notesCt = notes?.let { pkg.contentType(it) } ?: CT_NOTES
        val step = History.Lambda(
            u = {
                pkg.putXml(part, doc, ct)
                if (rels != null) pkg.putXml(relsPart, rels, CT_RELS)
                if (notes != null && notesDoc != null) {
                    pkg.putXml(notes, notesDoc, notesCt)
                    if (notesRels != null) pkg.putXml(notesRelsPart!!, notesRels, CT_RELS)
                }
                pkg.addRel(presPart, rel.type, rel.target, rid)
                insertElementAt(lst, sldId, i)
                changed(presPart)
            },
            r = {
                lst.children.removeAll { it === sldId }
                pkg.removeRel(presPart, rid)
                pkg.remove(part)
                if (rels != null) pkg.remove(relsPart)
                if (notes != null) {
                    pkg.remove(notes)
                    if (notesRels != null) pkg.remove(notesRelsPart!!)
                }
                changed(presPart)
            },
        )
        step.redo()
        history.push(step)
    }

    /** Moves slide [from] to position [to]. Undoable. */
    fun moveSlide(from: Int, to: Int) {
        val lst = sldIdLst() ?: return
        val ids = sldIds()
        val el = ids.getOrNull(from) ?: return
        val target = to.coerceIn(0, ids.size - 1)
        if (target == from) return
        val step = History.Lambda(
            u = {
                lst.children.removeAll { it === el }
                insertElementAt(lst, el, from)
                changed(presPart)
            },
            r = {
                lst.children.removeAll { it === el }
                insertElementAt(lst, el, target)
                changed(presPart)
            },
        )
        step.redo()
        history.push(step)
    }

    /** Hides or shows slide [i] in the slide show. Undoable. */
    fun setHidden(i: Int, hidden: Boolean) {
        val part = slidePart(i)
        val root = pkg.xml(part)!!.root
        val old = root["show"]
        val new = if (hidden) "0" else null
        val step = History.Lambda(
            u = { root["show"] = old; changed(part) },
            r = { root["show"] = new; changed(part) },
        )
        step.redo()
        history.push(step)
    }

    fun undo(): Boolean = history.undo()
    fun redo(): Boolean = history.redo()

    fun save(out: OutputStream) {
        pkg.write(out)
        history.markSaved()
    }

    /** Plain text of all slides, for search and sharing. */
    fun plainText(): String = (0 until slideCount).joinToString("\n\n") { i ->
        slide(i).shapes.filter { it.editable }.flatMap { s ->
            (s.text?.paras ?: emptyList()) + (s.table?.rows?.flatMap { r -> r.cells.flatMap { it.text.paras } } ?: emptyList())
        }.joinToString("\n") { it.text.replace(SOFT_BREAK, '\n') }.trim()
    }

    companion object {
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        private val NS = mapOf("xmlns:a" to A, "xmlns:r" to R, "xmlns:p" to P)
        private val SHAPES = setOf("sp", "pic", "graphicFrame", "cxnSp")

        const val SLIDE_REL = "$R/slide"
        const val LAYOUT_REL = "$R/slideLayout"
        const val IMAGE_REL = "$R/image"
        const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        const val CT_NOTES = "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"
        const val CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"

        fun open(bytes: ByteArray) = PptxDocument(OpcPackage.read(bytes))

        fun blank(): PptxDocument = PptxDocument(OpcPackage.fromParts(PptxTemplate.parts()))

        /** Relationship target from part [from] to part [to]. */
        fun relTarget(from: String, to: String): String {
            val fromDir = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
            val toSegs = to.split('/')
            var common = 0
            while (common < fromDir.size && common < toSegs.size - 1 && fromDir[common] == toSegs[common]) common++
            return "../".repeat(fromDir.size - common) + toSegs.drop(common).joinToString("/")
        }
    }
}
