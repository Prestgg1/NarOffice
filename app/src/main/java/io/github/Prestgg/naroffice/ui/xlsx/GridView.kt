// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.xlsx

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.ui.common.TextStyles
import io.github.Prestgg.naroffice.xlsx.CellRange
import io.github.Prestgg.naroffice.xlsx.CellRef
import io.github.Prestgg.naroffice.xlsx.HAlign
import io.github.Prestgg.naroffice.xlsx.Sheet
import io.github.Prestgg.naroffice.xlsx.VAlign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Cumulative offsets along one axis, extended lazily. */
class Axis(private val size: (Int) -> Float) {
    private var offsets = FloatArray(1)
    private var count = 0

    private fun ensure(n: Int) {
        if (n <= count) return
        val newCount = max(n, count * 2).coerceAtLeast(64)
        val arr = FloatArray(newCount + 1)
        System.arraycopy(offsets, 0, arr, 0, count + 1)
        for (i in count until newCount) arr[i + 1] = arr[i] + size(i)
        offsets = arr
        count = newCount
    }

    fun start(i: Int): Float {
        ensure(i + 1); return offsets[i]
    }

    fun end(i: Int): Float = start(i + 1)

    /** Index of the cell containing [pos]. */
    fun indexAt(pos: Float): Int {
        if (pos <= 0f) return 0
        while (offsets[count] < pos) ensure(count * 2)
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }
}

class GridState {
    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1.15f)
    /** Anchor and focus of the selection. */
    var anchor by mutableStateOf(0 to 0)
    var focus by mutableStateOf(0 to 0)

    val selection: CellRange
        get() = CellRange(min(anchor.first, focus.first), min(anchor.second, focus.second),
            max(anchor.first, focus.first), max(anchor.second, focus.second))

    fun select(r: Int, c: Int) {
        anchor = r to c; focus = r to c
    }
}

private val GRID = Color(0xFFDADCE0)
private val HEADER_BG = Color(0xFFF1F3F4)
private val HEADER_SEL = Color(0xFFD3E3D6)
private val HEADER_TEXT = Color(0xFF5F6368)
private val SEL = Color(0xFF1E7145)

/**
 * The spreadsheet canvas: headers, frozen panes, merged cells, text overflow and selection.
 * [tick] changes whenever the sheet content changed.
 */
@Composable
fun GridView(
    sheet: Sheet,
    state: GridState,
    tick: Int,
    modifier: Modifier = Modifier,
    onTap: (Int, Int) -> Unit,
    onDoubleTap: (Int, Int) -> Unit,
    onLongPress: (Int, Int) -> Unit,
    onSelectHeader: (row: Int?, col: Int?) -> Unit,
) {
    val density = LocalDensity.current
    // The gesture coroutine outlives compositions: always call the latest callbacks.
    val tapCb by androidx.compose.runtime.rememberUpdatedState(onTap)
    val doubleTapCb by androidx.compose.runtime.rememberUpdatedState(onDoubleTap)
    val longPressCb by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    val headerCb by androidx.compose.runtime.rememberUpdatedState(onSelectHeader)
    val measurer = rememberTextMeasurer(cacheSize = 512)
    val scope = rememberCoroutineScope()
    val dp = density.density
    val zoom = state.zoom
    val colAxis = remember(sheet, tick, zoom) { Axis { c -> sheet.colWidthPt(c) * zoom * dp * 1.25f } }
    val rowAxis = remember(sheet, tick, zoom) {
        Axis { r ->
            // Rows grow to fit their largest font, as in Excel (unless hidden).
            val base = sheet.rowHeightPt(r)
            if (base <= 0f || r > sheet.maxRow) base * zoom * dp * 1.25f else {
                var font = 0f
                for (c in 0..minOf(sheet.maxCol, 255)) {
                    val cell = sheet.cell(r, c) ?: continue
                    if (!cell.style.wrap && cell.display.isNotEmpty()) font = max(font, cell.style.fontSizePt)
                }
                max(base, font * 1.32f) * zoom * dp * 1.25f
            }
        }
    }
    val headerH = 24f * dp
    val headerW = 44f * dp
    val frozenRows = sheet.frozenRows
    val frozenCols = sheet.frozenCols
    val frozenW = colAxis.start(frozenCols)
    val frozenH = rowAxis.start(frozenRows)
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var size by remember { mutableStateOf(Size.Zero) }

    fun clampScroll() {
        state.scrollX = state.scrollX.coerceIn(0f, max(0f, colAxis.start(max(sheet.maxCol + 30, 40)) - size.width + headerW))
        state.scrollY = state.scrollY.coerceIn(0f, max(0f, rowAxis.start(max(sheet.maxRow + 100, 200)) - size.height + headerH))
    }

    /** Cell under a point in canvas coordinates, or a header hit encoded with -1. */
    fun hit(p: Offset): Pair<Int, Int> {
        val x = p.x - headerW
        val y = p.y - headerH
        val col = when {
            x < 0 -> -1
            x < frozenW -> colAxis.indexAt(x)
            else -> colAxis.indexAt(x - frozenW + frozenW + state.scrollX)
        }
        val row = when {
            y < 0 -> -1
            y < frozenH -> rowAxis.indexAt(y)
            else -> rowAxis.indexAt(y + state.scrollY)
        }
        return row to col
    }

    val gestures = Modifier.pointerInput(sheet, zoom, frozenRows, frozenCols) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            flingJob?.cancel()
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var moved = false
            var multi = false
            var up = false
            // Wait for a long press, movement, or release.
            val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.first()
                    if (e.changes.size >= 2) {
                        multi = true; return@withTimeoutOrNull true
                    }
                    if (!ch.pressed) {
                        up = true; return@withTimeoutOrNull true
                    }
                    if ((ch.position - down.position).getDistance() > slop) {
                        moved = true; return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (decided == null) {
                // Long press: drag to extend the selection.
                val (r, c) = hit(down.position)
                if (r >= 0 && c >= 0) {
                    longPressCb(r, c)
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.first()
                        if (!ch.pressed) break
                        val (r2, c2) = hit(ch.position)
                        if (r2 >= 0 && c2 >= 0) state.focus = r2 to c2
                        ch.consume()
                    }
                }
                return@awaitEachGesture
            }
            if (up) {
                val (r, c) = hit(down.position)
                when {
                    r < 0 && c < 0 -> {}
                    r < 0 -> headerCb(null, c)
                    c < 0 -> headerCb(r, null)
                    else -> {
                        val sel = state.selection
                        if (sel.r1 == r && sel.c1 == c && sel.rows == 1 && sel.cols == 1) doubleTapCb(r, c) else tapCb(r, c)
                    }
                }
                return@awaitEachGesture
            }
            // Pan and pinch until all fingers lift.
            while (true) {
                val e = awaitPointerEvent()
                if (e.changes.none { it.pressed }) break
                if (e.changes.count { it.pressed } >= 2) multi = true
                val zoomChange = if (multi) e.calculateZoom() else 1f
                if (zoomChange != 1f) {
                    val c = e.calculateCentroid()
                    val newZoom = (state.zoom * zoomChange).coerceIn(0.4f, 3f)
                    val f = newZoom / state.zoom
                    state.scrollX = (state.scrollX + c.x - headerW) * f - (c.x - headerW)
                    state.scrollY = (state.scrollY + c.y - headerH) * f - (c.y - headerH)
                    state.zoom = newZoom
                }
                val pan = e.calculatePan()
                state.scrollX -= pan.x
                state.scrollY -= pan.y
                clampScroll()
                val ch = e.changes.first()
                tracker.addPosition(ch.uptimeMillis, ch.position)
                e.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            }
            if (!multi) {
                val v = tracker.calculateVelocity()
                flingJob = scope.launch {
                    coroutineScope {
                        launch {
                            var last = 0f
                            animateDecay(0f, -v.x, FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { value, _ ->
                                state.scrollX += value - last; last = value; clampScroll()
                            }
                        }
                        launch {
                            var last = 0f
                            animateDecay(0f, -v.y, FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { value, _ ->
                                state.scrollY += value - last; last = value; clampScroll()
                            }
                        }
                    }
                }
            }
        }
    }

    Canvas(modifier.fillMaxSize().then(gestures)) {
        size = this.size
        @Suppress("UNUSED_VARIABLE") val t = tick
        val w = this.size.width
        val h = this.size.height
        drawRect(Color.White)

        val bodyLeft = headerW + frozenW
        val bodyTop = headerH + frozenH

        // Visible ranges of the scrolling body.
        val c0 = colAxis.indexAt(frozenW + state.scrollX).coerceAtLeast(frozenCols)
        val c1 = colAxis.indexAt(frozenW + state.scrollX + (w - bodyLeft))
        val r0 = rowAxis.indexAt(frozenH + state.scrollY).coerceAtLeast(frozenRows)
        val r1 = rowAxis.indexAt(frozenH + state.scrollY + (h - bodyTop))

        // x of a column in screen space for scrolling vs. frozen parts.
        fun colX(c: Int): Float = if (c < frozenCols) headerW + colAxis.start(c) else headerW + colAxis.start(c) - state.scrollX
        fun rowY(r: Int): Float = if (r < frozenRows) headerH + rowAxis.start(r) else headerH + rowAxis.start(r) - state.scrollY

        fun region(rows: IntRange, cols: IntRange, left: Float, top: Float, right: Float, bottom: Float) {
            if (rows.isEmpty() || cols.isEmpty() || right <= left || bottom <= top) return
            clipRect(left, top, right, bottom) {
                drawCells(sheet, rows, cols, ::colX, ::rowY, colAxis, rowAxis, measurer, zoom, dp, right)
            }
        }
        region(r0..r1, c0..c1, bodyLeft, bodyTop, w, h)
        if (frozenRows > 0) region(0 until frozenRows, c0..c1, bodyLeft, headerH, w, bodyTop)
        if (frozenCols > 0) region(r0..r1, 0 until frozenCols, headerW, bodyTop, bodyLeft, h)
        if (frozenRows > 0 && frozenCols > 0) region(0 until frozenRows, 0 until frozenCols, headerW, headerH, bodyLeft, bodyTop)

        // Selection.
        val sel = state.selection
        val m = if (sel.rows == 1 && sel.cols == 1) sheet.mergeAt(sel.r1, sel.c1) ?: sel else sel
        val sx = colX(m.c1)
        val sy = rowY(m.r1)
        val ex = colX(m.c2) + (colAxis.end(m.c2) - colAxis.start(m.c2))
        val ey = rowY(m.r2) + (rowAxis.end(m.r2) - rowAxis.start(m.r2))
        clipRect(headerW, headerH, w, h) {
            if (m.rows > 1 || m.cols > 1) drawRect(SEL.copy(alpha = 0.10f), Offset(sx, sy), Size(ex - sx, ey - sy))
            drawRect(SEL, Offset(sx, sy), Size(ex - sx, ey - sy), style = Stroke(2f * dp))
            drawRect(SEL, Offset(ex - 3 * dp, ey - 3 * dp), Size(6 * dp, 6 * dp))
        }

        // Frozen pane dividers.
        if (frozenCols > 0) drawLine(Color(0xFFBDC1C6), Offset(bodyLeft, headerH), Offset(bodyLeft, h), 2f * dp)
        if (frozenRows > 0) drawLine(Color(0xFFBDC1C6), Offset(headerW, bodyTop), Offset(w, bodyTop), 2f * dp)

        // Headers.
        drawRect(HEADER_BG, Offset(headerW, 0f), Size(w - headerW, headerH))
        drawRect(HEADER_BG, Offset(0f, headerH), Size(headerW, h - headerH))
        val headerStyle = TextStyle(fontSize = 11.sp, color = HEADER_TEXT)
        fun colHeader(c: Int) {
            val x = colX(c)
            val cw = colAxis.end(c) - colAxis.start(c)
            if (cw <= 0f) return
            if (c in sel.c1..sel.c2) drawRect(HEADER_SEL, Offset(x, 0f), Size(cw, headerH))
            drawLine(GRID, Offset(x + cw, 0f), Offset(x + cw, headerH), 1f)
            val l = measurer.measure(CellRef.colName(c), headerStyle)
            drawText(l, topLeft = Offset(x + (cw - l.size.width) / 2, (headerH - l.size.height) / 2))
        }
        fun rowHeader(r: Int) {
            val y = rowY(r)
            val rh = rowAxis.end(r) - rowAxis.start(r)
            if (rh <= 0f) return
            if (r in sel.r1..sel.r2) drawRect(HEADER_SEL, Offset(0f, y), Size(headerW, rh))
            drawLine(GRID, Offset(0f, y + rh), Offset(headerW, y + rh), 1f)
            val l = measurer.measure((r + 1).toString(), headerStyle)
            drawText(l, topLeft = Offset((headerW - l.size.width) / 2, y + (rh - l.size.height) / 2))
        }
        clipRect(bodyLeft, 0f, w, headerH) { for (c in c0..c1) colHeader(c) }
        clipRect(headerW, 0f, bodyLeft, headerH) { for (c in 0 until frozenCols) colHeader(c) }
        clipRect(0f, bodyTop, headerW, h) { for (r in r0..r1) rowHeader(r) }
        clipRect(0f, headerH, headerW, bodyTop) { for (r in 0 until frozenRows) rowHeader(r) }
        drawRect(HEADER_BG, Offset.Zero, Size(headerW, headerH))
        drawLine(GRID, Offset(0f, headerH), Offset(w, headerH), 1f)
        drawLine(GRID, Offset(headerW, 0f), Offset(headerW, h), 1f)
    }
}

private fun DrawScope.drawCells(
    sheet: Sheet,
    rows: IntRange,
    cols: IntRange,
    colX: (Int) -> Float,
    rowY: (Int) -> Float,
    colAxis: Axis,
    rowAxis: Axis,
    measurer: androidx.compose.ui.text.TextMeasurer,
    zoom: Float,
    dp: Float,
    clipRight: Float,
) {
    val merges = sheet.merges.filter { it.r2 >= rows.first && it.r1 <= rows.last && it.c2 >= cols.first && it.c1 <= cols.last }
    fun inMerge(r: Int, c: Int) = merges.any { it.contains(r, c) }

    // Fills.
    for (r in rows) for (c in cols) {
        if (inMerge(r, c)) continue
        val cell = sheet.cell(r, c) ?: continue
        val fill = cell.style.fill ?: continue
        drawRect(Color(fill), Offset(colX(c), rowY(r)), Size(colAxis.end(c) - colAxis.start(c), rowAxis.end(r) - rowAxis.start(r)))
    }
    // Grid lines.
    if (sheet.showGridLines) {
        val top = rowY(rows.first)
        val bottom = rowY(rows.last) + rowAxis.end(rows.last) - rowAxis.start(rows.last)
        val left = colX(cols.first)
        val right = colX(cols.last) + colAxis.end(cols.last) - colAxis.start(cols.last)
        for (c in cols) {
            val x = colX(c) + colAxis.end(c) - colAxis.start(c)
            drawLine(GRID, Offset(x, top), Offset(x, bottom), 1f)
        }
        for (r in rows) {
            val y = rowY(r) + rowAxis.end(r) - rowAxis.start(r)
            drawLine(GRID, Offset(left, y), Offset(right, y), 1f)
        }
    }
    // Merged areas cover the grid lines inside them.
    for (m in merges) {
        val x = colX(m.c1)
        val y = rowY(m.r1)
        val mw = colX(m.c2) + colAxis.end(m.c2) - colAxis.start(m.c2) - x
        val mh = rowY(m.r2) + rowAxis.end(m.r2) - rowAxis.start(m.r2) - y
        val cell = sheet.cell(m.r1, m.c1)
        drawRect(cell?.style?.fill?.let { Color(it) } ?: Color.White, Offset(x, y), Size(mw, mh))
        if (sheet.showGridLines) drawRect(GRID, Offset(x, y), Size(mw, mh), style = Stroke(1f))
        if (cell != null && cell.display.isNotEmpty()) drawCellText(cell, x, y, mw, mh, measurer, zoom, dp, x + mw)
    }
    // Text and borders.
    for (r in rows) {
        for (c in cols) {
            if (inMerge(r, c)) continue
            val cell = sheet.cell(r, c) ?: continue
            val x = colX(c)
            val y = rowY(r)
            val cw = colAxis.end(c) - colAxis.start(c)
            val rh = rowAxis.end(r) - rowAxis.start(r)
            if (cw <= 0f || rh <= 0f) continue
            val st = cell.style
            if (cell.display.isNotEmpty()) {
                // Left-aligned text may spill into empty cells on the right.
                var limit = x + cw
                if (!st.wrap && cell.effectiveAlign == HAlign.LEFT) {
                    var k = c + 1
                    while (k <= c + 12 && sheet.cell(r, k)?.display.isNullOrEmpty() && !inMerge(r, k)) {
                        limit = colX(k) + colAxis.end(k) - colAxis.start(k); k++
                    }
                }
                drawCellText(cell, x, y, cw, rh, measurer, zoom, dp, min(limit, clipRight))
            }
            fun border(b: io.github.Prestgg.naroffice.xlsx.Border, a: Offset, e: Offset) {
                if (b.present) drawLine(b.color?.let { Color(it) } ?: Color.Black, a, e, if (b.thick) 2.5f * dp else 1f * dp)
            }
            border(st.top, Offset(x, y), Offset(x + cw, y))
            border(st.bottom, Offset(x, y + rh), Offset(x + cw, y + rh))
            border(st.left, Offset(x, y), Offset(x, y + rh))
            border(st.right, Offset(x + cw, y), Offset(x + cw, y + rh))
        }
    }
}

private fun DrawScope.drawCellText(
    cell: io.github.Prestgg.naroffice.xlsx.CellView,
    x: Float, y: Float, cw: Float, rh: Float,
    measurer: androidx.compose.ui.text.TextMeasurer,
    zoom: Float, dp: Float, limitRight: Float,
) {
    val st = cell.style
    val pad = 3f * dp * zoom
    val deco = buildList {
        if (st.underline) add(TextDecoration.Underline)
        if (st.strike) add(TextDecoration.LineThrough)
    }
    val style = TextStyle(
        fontSize = (st.fontSizePt * zoom * 1.25f * dp / fontScale / density).sp,
        color = st.fontColor?.let { Color(it) } ?: Color(0xFF202124),
        fontWeight = if (st.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (st.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = TextStyles.family(st.fontName),
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
    )
    val availW = (if (st.wrap) cw else limitRight - x) - 2 * pad
    if (availW <= 0) return
    val layout = measurer.measure(
        cell.display, style,
        overflow = if (st.wrap) TextOverflow.Clip else TextOverflow.Clip,
        softWrap = st.wrap,
        maxLines = if (st.wrap) Int.MAX_VALUE else 1,
        constraints = Constraints(maxWidth = availW.toInt().coerceAtLeast(1)),
    )
    val tw = layout.size.width.toFloat()
    val th = layout.size.height.toFloat()
    val tx = when (cell.effectiveAlign) {
        HAlign.CENTER -> x + (cw - tw) / 2
        HAlign.RIGHT -> x + cw - pad - tw
        else -> x + pad + st.indent * 8 * dp * zoom
    }
    val ty = when (st.vAlign) {
        VAlign.TOP -> y + pad / 2
        VAlign.CENTER -> y + (rh - th) / 2
        VAlign.BOTTOM -> y + rh - th - pad / 2
    }
    clipRect(x, y, max(x + cw, limitRight), y + rh) {
        drawText(layout, topLeft = Offset(tx, ty))
    }
}

@Suppress("unused")
private fun near(a: Float, b: Float) = abs(a - b) < 0.5f
