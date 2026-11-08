// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.pptx

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.pptx.Anchor
import io.github.Prestgg.naroffice.pptx.Crop
import io.github.Prestgg.naroffice.pptx.Fill
import io.github.Prestgg.naroffice.pptx.Frame
import io.github.Prestgg.naroffice.pptx.GeoPath
import io.github.Prestgg.naroffice.pptx.Line
import io.github.Prestgg.naroffice.pptx.PathCmd
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.pptx.ShapeKind
import io.github.Prestgg.naroffice.pptx.ShapeView
import io.github.Prestgg.naroffice.pptx.SlideView
import io.github.Prestgg.naroffice.pptx.TableView
import io.github.Prestgg.naroffice.pptx.TextBodyView
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.ui.common.Images
import io.github.Prestgg.naroffice.ui.common.TextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A text layout placed relative to its shape's top-left corner. */
class PlacedText(val layout: TextLayoutResult, val x: Float, val y: Float)

class CellLayout(
    val rect: Rect,
    val fill: Fill,
    val borders: List<Pair<Line, Pair<Offset, Offset>>>,
    val texts: List<PlacedText>,
)

class ShapeLayout(val shape: ShapeView, val rect: Rect, val texts: List<PlacedText>, val cells: List<CellLayout>?, val hint: Boolean)

class SlideLayout(val size: Size, val pxPerEmu: Float, val shapes: List<ShapeLayout>)

private const val EMU_PER_PT = 12700f

/** Lays out a slide's text at the given pixel width. */
fun layoutSlide(
    view: SlideView,
    slideSize: Pair<Long, Long>,
    size: Size,
    measurer: TextMeasurer,
    density: Float,
    fontScale: Float,
    edit: Boolean,
    hintTitle: String,
    hintText: String,
    overrides: Map<String, Frame>,
): SlideLayout {
    val pxPerEmu = size.width / slideSize.first
    val ctx = TextCtx(measurer, pxPerEmu, density, fontScale)
    val shapes = view.shapes.map { s ->
        val f = overrides[s.id] ?: s.frame
        val rect = Rect(f.x * pxPerEmu, f.y * pxPerEmu, (f.x + f.w) * pxPerEmu, (f.y + f.h) * pxPerEmu)
        val hint = edit && s.isEmptyPlaceholder
        val texts = when {
            hint -> {
                val title = s.placeholder?.type in setOf("title", "ctrTitle")
                val body = s.text
                ctx.hint(if (title) hintTitle else hintText, body, rect.width, rect.height, title)
            }
            s.text != null && !s.text.isEmpty -> ctx.body(s.text, rect.width, rect.height)
            s.kind == ShapeKind.CHART -> ctx.simple(s.chartTitle ?: "📊", rect.width, rect.height)
            else -> emptyList()
        }
        val cells = s.table?.let { ctx.table(it, rect.width) }
        ShapeLayout(s, rect, texts, cells, hint)
    }
    return SlideLayout(size, pxPerEmu, shapes)
}

private class TextCtx(val m: TextMeasurer, val pxPerEmu: Float, val density: Float, val fontScale: Float) {
    val pxPerPt = pxPerEmu * EMU_PER_PT

    /** TextStyles.span scale such that sizePt maps to pixels of the rendered slide. */
    val spanScale = pxPerPt / (density * fontScale)

    fun ptToSp(pt: Float) = (pt * spanScale).coerceAtLeast(0.5f).sp

    fun body(b: TextBodyView, boxW: Float, boxH: Float): List<PlacedText> {
        val l = b.insets.left * pxPerEmu
        val r = b.insets.right * pxPerEmu
        val t = b.insets.top * pxPerEmu
        val bot = b.insets.bottom * pxPerEmu
        val availW = max(1f, boxW - l - r)
        val out = ArrayList<PlacedText>()
        var y = 0f
        for ((i, p) in b.paras.withIndex()) {
            val fs = b.fontScale
            val marL = p.marLEmu * pxPerEmu
            val indent = p.indentEmu * pxPerEmu
            if (i > 0) y += p.spaceBeforePt * pxPerPt * fs
            val lineHeight = when {
                p.lineSpacingPt != null -> (p.lineSpacingPt * spanScale * fs).sp
                else -> ((p.lineSpacingPct ?: 1f) * 1.2f * (1f - b.lineSpaceReduction)).coerceAtLeast(0.5f).em
            }
            val empty = p.text.isEmpty()
            val firstSize = p.runs.firstOrNull { it.text.isNotEmpty() }?.props?.sizePt ?: p.endSizePt
            val text: AnnotatedString = if (empty) AnnotatedString("​") else buildAnnotatedString {
                for (run in p.runs) {
                    val props = run.props.copy(sizePt = run.props.sizePt * fs)
                    val shown = (if (props.caps) run.text.uppercase() else run.text).replace(SOFT_BREAK, '\n').replace('\t', ' ')
                    withStyle(TextStyles.span(props, spanScale, link = true)) { append(shown) }
                }
            }
            val style = TextStyle(
                fontSize = ptToSp((if (empty) p.endSizePt else firstSize) * fs),
                textAlign = align(p.align),
                lineHeight = lineHeight,
                color = Color(0xFF1B1B1B),
            )
            // First line starts at marL + indent when there is no bullet (indent is usually 0 then).
            val textX = marL
            val w = max(1f, availW - textX)
            val layout = if (b.wrap) {
                m.measure(text, style, constraints = Constraints.fixedWidth(w.toInt().coerceAtLeast(1)))
            } else {
                m.measure(text, style, softWrap = false, maxLines = 64)
            }
            var x = l + textX
            if (!b.wrap) {
                val lw = layout.size.width
                x = when (p.align) {
                    Align.CENTER -> l + (availW - lw) / 2
                    Align.END -> l + availW - lw
                    else -> x
                }
            }
            if (p.bullet != null && !empty) {
                val bSize = firstSize * fs * p.bullet.sizePct
                val color = p.bullet.color ?: p.runs.firstOrNull()?.props?.color
                val bs = TextStyle(
                    fontSize = ptToSp(bSize),
                    color = color?.let { Color(it) } ?: Color(0xFF1B1B1B),
                    fontFamily = TextStyles.family(p.bullet.font),
                )
                val bl = m.measure(AnnotatedString(p.bullet.text), bs, softWrap = false, maxLines = 1)
                val bx = l + marL + indent
                // Align bullet baseline with the first text line.
                val by = layout.firstBaseline - bl.firstBaseline
                out.add(PlacedText(bl, if (b.wrap) bx else x + indent, y + by))
            }
            out.add(PlacedText(layout, x, y))
            y += layout.size.height + p.spaceAfterPt * pxPerPt * fs
        }
        val dy = when (b.anchor) {
            Anchor.TOP -> t
            Anchor.CENTER -> t + (boxH - t - bot - y) / 2
            Anchor.BOTTOM -> boxH - bot - y
        }
        return out.map { PlacedText(it.layout, it.x, it.y + dy) }
    }

    fun hint(label: String, b: TextBodyView?, boxW: Float, boxH: Float, title: Boolean): List<PlacedText> {
        val size = (b?.paras?.firstOrNull()?.endSizePt ?: if (title) 40f else 20f) * (b?.fontScale ?: 1f)
        val style = TextStyle(
            fontSize = ptToSp(size.coerceAtMost(44f)),
            color = Color(0xFF8A8A8A),
            textAlign = if (title || b?.paras?.firstOrNull()?.align == Align.CENTER) TextAlign.Center else TextAlign.Start,
        )
        val l = (b?.insets?.left ?: 91440) * pxPerEmu
        val r = (b?.insets?.right ?: 91440) * pxPerEmu
        val w = max(1f, boxW - l - r)
        val layout = m.measure(AnnotatedString(label), style, constraints = Constraints.fixedWidth(w.toInt().coerceAtLeast(1)))
        val y = when (b?.anchor ?: if (title) Anchor.CENTER else Anchor.TOP) {
            Anchor.TOP -> (b?.insets?.top ?: 45720) * pxPerEmu
            Anchor.CENTER -> (boxH - layout.size.height) / 2
            Anchor.BOTTOM -> boxH - layout.size.height - (b?.insets?.bottom ?: 45720) * pxPerEmu
        }
        return listOf(PlacedText(layout, l, y))
    }

    fun simple(label: String, boxW: Float, boxH: Float): List<PlacedText> {
        val style = TextStyle(fontSize = ptToSp(14f), color = Color(0xFF5F6368), textAlign = TextAlign.Center)
        val layout = m.measure(AnnotatedString(label), style, constraints = Constraints.fixedWidth(max(1f, boxW).toInt()))
        return listOf(PlacedText(layout, 0f, (boxH - layout.size.height) / 2))
    }

    fun table(t: TableView, boxW: Float): List<CellLayout> {
        val colX = FloatArray(t.colWidths.size + 1)
        for (i in t.colWidths.indices) colX[i + 1] = colX[i] + t.colWidths[i] * pxPerEmu
        val rows = t.rows.size
        // Measure cell text first so that rows can grow to fit it.
        val heights = FloatArray(rows) { t.rows[it].heightEmu * pxPerEmu }
        val texts = HashMap<Pair<Int, Int>, List<PlacedText>>()
        for ((ri, row) in t.rows.withIndex()) {
            for ((ci, cell) in row.cells.withIndex()) {
                if (cell.hMerge || cell.vMerge) continue
                val end = (ci + cell.gridSpan).coerceAtMost(t.colWidths.size)
                val w = colX[end] - colX[ci.coerceAtMost(t.colWidths.size)]
                val placed = if (cell.text.isEmpty) emptyList() else body(cell.text.copy(anchor = Anchor.TOP), w, 0f)
                texts[ri to ci] = placed
                if (cell.rowSpan <= 1 && placed.isNotEmpty()) {
                    val need = placed.maxOf { it.y + it.layout.size.height } + cell.text.insets.bottom * pxPerEmu
                    heights[ri] = max(heights[ri], need)
                }
            }
        }
        val rowY = FloatArray(rows + 1)
        for (i in 0 until rows) rowY[i + 1] = rowY[i] + heights[i]
        val out = ArrayList<CellLayout>()
        for ((ri, row) in t.rows.withIndex()) {
            for ((ci, cell) in row.cells.withIndex()) {
                if (cell.hMerge || cell.vMerge || ci >= t.colWidths.size) continue
                val endC = (ci + cell.gridSpan).coerceAtMost(t.colWidths.size)
                val endR = (ri + cell.rowSpan).coerceAtMost(rows)
                val rect = Rect(colX[ci], rowY[ri], colX[endC], rowY[endR])
                val placed = texts[ri to ci].orEmpty()
                // Vertical anchoring inside the (possibly taller) cell.
                val contentH = placed.maxOfOrNull { it.y + it.layout.size.height }?.plus(cell.text.insets.bottom * pxPerEmu) ?: 0f
                val dy = when (cell.text.anchor) {
                    Anchor.TOP -> 0f
                    Anchor.CENTER -> (rect.height - contentH) / 2
                    Anchor.BOTTOM -> rect.height - contentH
                }
                val borders = buildList {
                    cell.borderTop?.let { add(it to (rect.topLeft to rect.topRight)) }
                    cell.borderBottom?.let { add(it to (rect.bottomLeft to rect.bottomRight)) }
                    cell.borderLeft?.let { add(it to (rect.topLeft to rect.bottomLeft)) }
                    cell.borderRight?.let { add(it to (rect.topRight to rect.bottomRight)) }
                }
                out.add(CellLayout(rect, cell.fill, borders, placed.map { PlacedText(it.layout, it.x, it.y + dy) }))
            }
        }
        return out
    }

    fun align(a: Align) = when (a) {
        Align.START -> TextAlign.Start
        Align.CENTER -> TextAlign.Center
        Align.END -> TextAlign.End
        Align.JUSTIFY -> TextAlign.Justify
    }
}

/** Image parts used by a slide (pictures, picture fills and the background). */
fun imageParts(view: SlideView): Set<String> = buildSet {
    (view.background as? Fill.Image)?.let { add(it.part) }
    for (s in view.shapes) {
        s.imagePart?.let { add(it) }
        (s.fill as? Fill.Image)?.let { add(it.part) }
    }
}

fun imageKey(doc: PptxDocument, part: String) = "pptx:${System.identityHashCode(doc)}:$part"

/** Loads the pictures of [view] off the main thread into a state map. */
@Composable
fun rememberSlideImages(doc: PptxDocument, view: SlideView): SnapshotStateMap<String, ImageBitmap> {
    val map = remember(doc) { mutableStateMapOf<String, ImageBitmap>() }
    val parts = remember(view) { imageParts(view) }
    LaunchedEffect(parts) {
        for (p in parts) {
            if (map.containsKey(p)) continue
            val key = imageKey(doc, p)
            val bmp = Images.cached(key) ?: withContext(Dispatchers.IO) { Images.decode(key, doc.partBytes(p)) }
            if (bmp != null) map[p] = bmp
        }
    }
    return map
}

/**
 * Draws one slide filling [modifier]'s size (give it the slide's aspect ratio). Empty
 * placeholders get a dashed outline and a hint only when [edit] is true.
 */
@Composable
fun SlideImage(
    doc: PptxDocument,
    view: SlideView,
    modifier: Modifier,
    edit: Boolean = false,
    overrides: Map<String, Frame> = emptyMap(),
    onLayout: ((SlideLayout) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val images = rememberSlideImages(doc, view)
    val hintTitle = stringResource(R.string.click_title)
    val hintText = stringResource(R.string.click_text)
    val slideSize = doc.slideSize
    Spacer(
        modifier.drawWithCache {
            val layout = layoutSlide(view, slideSize, size, measurer, density, fontScale, edit, hintTitle, hintText, overrides)
            onLayout?.invoke(layout)
            onDrawBehind {
                clipRect(0f, 0f, size.width, size.height) {
                    drawSlide(view, layout, images)
                }
            }
        },
    )
}

fun DrawScope.drawSlide(view: SlideView, layout: SlideLayout, images: Map<String, ImageBitmap>) {
    val full = Rect(Offset.Zero, size)
    drawRect(Color.White)
    drawFill(view.background, full, null, images)
    for (sl in layout.shapes) drawShape(sl, layout.pxPerEmu, images)
}

private fun DrawScope.drawShape(sl: ShapeLayout, pxPerEmu: Float, images: Map<String, ImageBitmap>) {
    val s = sl.shape
    val r = sl.rect
    val center = r.center
    rotate(s.rotationDeg, center) {
        val sx = if (s.flipH) -1f else 1f
        val sy = if (s.flipV) -1f else 1f
        scale(sx, sy, center) {
            val path = shapePath(s, r)
            val isLine = isLineGeometry(s.geometry)
            if (!isLine) drawFill(s.fill, r, path, images)
            s.imagePart?.let { part ->
                val img = images[part]
                if (img != null) {
                    if (path != null) clipPath(path) { drawPicture(img, r, s.imageCrop) } else drawPicture(img, r, s.imageCrop)
                } else {
                    drawRect(Color(0x11000000), r.topLeft, r.size)
                }
            }
            if (s.kind == ShapeKind.CHART) {
                drawRect(Color(0xFFF5F5F5), r.topLeft, r.size)
                drawRect(Color(0xFFBDBDBD), r.topLeft, r.size, style = Stroke(1f))
            }
            s.line?.let { ln -> drawOutline(ln, r, path, isLine, pxPerEmu) }
        }
        if (sl.hint) {
            drawRect(
                Color(0xFF9E9E9E), r.topLeft, r.size,
                style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
            )
        }
        sl.cells?.let { cells ->
            for (c in cells) {
                val cr = c.rect.translate(r.topLeft)
                drawFill(c.fill, cr, null, images)
            }
            for (c in cells) {
                val cr = c.rect.translate(r.topLeft)
                for ((ln, seg) in c.borders) {
                    drawLine(Color(ln.argb), seg.first + r.topLeft, seg.second + r.topLeft,
                        max(1f, (if (ln.widthEmu > 0) ln.widthEmu else 12700L) * pxPerEmu))
                }
                clipRect(cr.left, cr.top, cr.right, cr.bottom) {
                    for (t in c.texts) drawText(t.layout, topLeft = Offset(cr.left + t.x, cr.top + t.y))
                }
            }
        }
        for (t in sl.texts) drawText(t.layout, topLeft = Offset(r.left + t.x, r.top + t.y))
    }
}

private fun DrawScope.drawPicture(img: ImageBitmap, r: Rect, crop: Crop?) {
    val iw = img.width
    val ih = img.height
    val c = crop ?: Crop(0f, 0f, 0f, 0f)
    val left = (c.left.coerceIn(0f, 1f) * iw).toInt()
    val top = (c.top.coerceIn(0f, 1f) * ih).toInt()
    val right = iw - (c.right.coerceIn(0f, 1f) * iw).toInt()
    val bottom = ih - (c.bottom.coerceIn(0f, 1f) * ih).toInt()
    if (right <= left || bottom <= top || r.width < 1f || r.height < 1f) return
    drawImage(
        img,
        srcOffset = IntOffset(left, top),
        srcSize = IntSize(right - left, bottom - top),
        dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
        dstSize = IntSize(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.drawFill(fill: Fill, r: Rect, path: Path?, images: Map<String, ImageBitmap>) {
    when (fill) {
        Fill.None -> {}
        is Fill.Solid -> if (path != null) drawPath(path, Color(fill.argb)) else drawRect(Color(fill.argb), r.topLeft, r.size)
        is Fill.Gradient -> {
            if (fill.stops.isEmpty()) return
            val stops = fill.stops.sortedBy { it.pos }.map { it.pos.coerceIn(0f, 1f) to Color(it.argb) }.toTypedArray()
            val brush = if (fill.radial || stops.size == 1) {
                if (stops.size == 1) Brush.linearGradient(listOf(stops[0].second, stops[0].second))
                else Brush.radialGradient(*stops, center = r.center, radius = max(r.width, r.height) / 2)
            } else {
                val a = fill.angleDeg * PI / 180
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val half = (kotlin.math.abs(r.width * dx) + kotlin.math.abs(r.height * dy)) / 2
                Brush.linearGradient(
                    *stops,
                    start = Offset(r.center.x - dx * half, r.center.y - dy * half),
                    end = Offset(r.center.x + dx * half, r.center.y + dy * half),
                )
            }
            if (path != null) drawPath(path, brush) else drawRect(brush, r.topLeft, r.size)
        }
        is Fill.Image -> {
            val img = images[fill.part] ?: return
            if (path != null) clipPath(path) { drawPicture(img, r, fill.crop) } else drawPicture(img, r, fill.crop)
        }
    }
}

private fun DrawScope.drawOutline(ln: Line, r: Rect, path: Path?, isLine: Boolean, pxPerEmu: Float) {
    val w = max(1f, (if (ln.widthEmu > 0) ln.widthEmu else 9525L) * pxPerEmu)
    val effect = when (ln.dash) {
        null, "solid" -> null
        "dot", "sysDot" -> PathEffect.dashPathEffect(floatArrayOf(w, w * 2))
        else -> PathEffect.dashPathEffect(floatArrayOf(w * 4, w * 3))
    }
    val color = Color(ln.argb)
    when {
        isLine -> drawLine(color, r.topLeft, r.bottomRight, w, pathEffect = effect)
        path != null -> drawPath(path, color, style = Stroke(w, pathEffect = effect))
        else -> drawRect(color, r.topLeft, r.size, style = Stroke(w, pathEffect = effect))
    }
}

private fun isLineGeometry(g: String?) = g != null && (g == "line" || g.contains("Connector"))

/** Path for the shape geometry, or null for a plain rectangle. */
fun shapePath(s: ShapeView, r: Rect): Path? {
    s.paths?.let { return customPath(it, r) }
    val g = s.geometry ?: return null
    val w = r.width
    val h = r.height
    val x0 = r.left
    val y0 = r.top
    val ss = min(w, h)
    fun poly(vararg pts: Float): Path = Path().apply {
        moveTo(x0 + pts[0] * w, y0 + pts[1] * h)
        var i = 2
        while (i < pts.size) {
            lineTo(x0 + pts[i] * w, y0 + pts[i + 1] * h); i += 2
        }
        close()
    }
    // Fractions of width/height for an absolute adjustment in pixels.
    fun fx(px: Float) = px / w
    fun fy(px: Float) = px / h
    return when (g) {
        "rect", "flowChartProcess", "line", "straightConnector1" -> null
        "roundRect", "flowChartAlternateProcess" -> Path().apply {
            val rad = ss * 0.1667f
            addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(rad, rad)))
        }
        "ellipse", "flowChartConnector" -> Path().apply { addOval(r) }
        "triangle", "flowChartExtract" -> poly(0.5f, 0f, 1f, 1f, 0f, 1f)
        "rtTriangle" -> poly(0f, 0f, 1f, 1f, 0f, 1f)
        "diamond", "flowChartDecision" -> poly(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
        "parallelogram", "flowChartInputOutput" -> {
            val a = fx(ss * 0.25f); poly(a, 0f, 1f, 0f, 1f - a, 1f, 0f, 1f)
        }
        "trapezoid" -> {
            val a = fx(ss * 0.25f); poly(0f, 1f, a, 0f, 1f - a, 0f, 1f, 1f)
        }
        "pentagon" -> poly(0.5f, 0f, 1f, 0.38f, 0.81f, 1f, 0.19f, 1f, 0f, 0.38f)
        "hexagon" -> {
            val a = fx(ss * 0.25f); poly(a, 0f, 1f - a, 0f, 1f, 0.5f, 1f - a, 1f, a, 1f, 0f, 0.5f)
        }
        "octagon" -> {
            val a = fx(ss * 0.29f)
            val b = fy(ss * 0.29f)
            poly(a, 0f, 1f - a, 0f, 1f, b, 1f, 1f - b, 1f - a, 1f, a, 1f, 0f, 1f - b, 0f, b)
        }
        "star5" -> {
            val pts = FloatArray(20)
            for (i in 0 until 10) {
                val ang = -PI / 2 + i * PI / 5
                val rad = if (i % 2 == 0) 0.5f else 0.19f
                pts[i * 2] = 0.5f + (rad * cos(ang)).toFloat()
                pts[i * 2 + 1] = 0.5f + (rad * sin(ang) * 1.05f).toFloat() + 0.03f
            }
            poly(*pts)
        }
        "rightArrow" -> {
            val a = fx(ss * 0.5f); poly(0f, 0.25f, 1f - a, 0.25f, 1f - a, 0f, 1f, 0.5f, 1f - a, 1f, 1f - a, 0.75f, 0f, 0.75f)
        }
        "leftArrow" -> {
            val a = fx(ss * 0.5f); poly(1f, 0.25f, a, 0.25f, a, 0f, 0f, 0.5f, a, 1f, a, 0.75f, 1f, 0.75f)
        }
        "upArrow" -> {
            val a = fy(ss * 0.5f); poly(0.25f, 1f, 0.25f, a, 0f, a, 0.5f, 0f, 1f, a, 0.75f, a, 0.75f, 1f)
        }
        "downArrow" -> {
            val a = fy(ss * 0.5f); poly(0.25f, 0f, 0.75f, 0f, 0.75f, 1f - a, 1f, 1f - a, 0.5f, 1f, 0f, 1f - a, 0.25f, 1f - a)
        }
        "chevron" -> {
            val a = fx(ss * 0.5f); poly(0f, 0f, 1f - a, 0f, 1f, 0.5f, 1f - a, 1f, 0f, 1f, a, 0.5f)
        }
        "homePlate" -> {
            val a = fx(ss * 0.5f); poly(0f, 0f, 1f - a, 0f, 1f, 0.5f, 1f - a, 1f, 0f, 1f)
        }
        "plus" -> {
            val a = fx(ss * 0.25f)
            val b = fy(ss * 0.25f)
            poly(a, 0f, 1f - a, 0f, 1f - a, b, 1f, b, 1f, 1f - b, 1f - a, 1f - b, 1f - a, 1f, a, 1f, a, 1f - b, 0f, 1f - b, 0f, b, a, b)
        }
        else -> null
    }
}

private fun customPath(paths: List<GeoPath>, r: Rect): Path? {
    if (paths.isEmpty()) return null
    val p = Path()
    for (gp in paths) {
        if (!gp.filled && !gp.stroked) continue
        var cx = r.left
        var cy = r.top
        for (c in gp.cmds) {
            when (c) {
                is PathCmd.MoveTo -> {
                    cx = r.left + c.x * r.width; cy = r.top + c.y * r.height; p.moveTo(cx, cy)
                }
                is PathCmd.LineTo -> {
                    cx = r.left + c.x * r.width; cy = r.top + c.y * r.height; p.lineTo(cx, cy)
                }
                is PathCmd.CubicTo -> {
                    p.cubicTo(r.left + c.x1 * r.width, r.top + c.y1 * r.height, r.left + c.x2 * r.width,
                        r.top + c.y2 * r.height, r.left + c.x * r.width, r.top + c.y * r.height)
                    cx = r.left + c.x * r.width; cy = r.top + c.y * r.height
                }
                is PathCmd.QuadTo -> {
                    p.quadraticTo(r.left + c.x1 * r.width, r.top + c.y1 * r.height, r.left + c.x * r.width, r.top + c.y * r.height)
                    cx = r.left + c.x * r.width; cy = r.top + c.y * r.height
                }
                is PathCmd.ArcTo -> {
                    val wr = c.wR * r.width
                    val hr = c.hR * r.height
                    val st = c.startDeg * PI / 180
                    val ocx = cx - wr * cos(st).toFloat()
                    val ocy = cy - hr * sin(st).toFloat()
                    p.arcTo(Rect(ocx - wr, ocy - hr, ocx + wr, ocy + hr), c.startDeg, c.sweepDeg, false)
                    val en = (c.startDeg + c.sweepDeg) * PI / 180
                    cx = ocx + wr * cos(en).toFloat(); cy = ocy + hr * sin(en).toFloat()
                }
                PathCmd.Close -> p.close()
            }
        }
    }
    return p
}

/** Top-most editable shape under a point in slide pixels. */
fun hitShape(layout: SlideLayout, p: Offset): ShapeView? =
    layout.shapes.lastOrNull { it.shape.editable && it.rect.inflate(6f).contains(p) }?.shape
