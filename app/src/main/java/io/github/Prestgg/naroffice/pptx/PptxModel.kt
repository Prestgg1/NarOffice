// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.pptx

import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.TextProps

/** Position and size in EMU (914400 per inch, 12700 per point). */
data class Frame(val x: Long, val y: Long, val w: Long, val h: Long) {
    val right get() = x + w
    val bottom get() = y + h
}

data class GradientStop(val pos: Float, val argb: Int)

sealed class Fill {
    object None : Fill()
    data class Solid(val argb: Int) : Fill()

    /** Linear (or radial when [radial]) gradient; [angleDeg] is clockwise from left-to-right. */
    data class Gradient(val stops: List<GradientStop>, val angleDeg: Float, val radial: Boolean) : Fill()

    /** A picture fill; read it with [PptxDocument.partBytes]. */
    data class Image(val part: String, val crop: Crop?) : Fill()
}

/** Crop fractions (0..1) cut from each edge of a picture. */
data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class Line(val argb: Int, val widthEmu: Long, val dash: String?)

/** One sub-path of a custom geometry, coordinates relative to the shape (0..1). */
sealed class PathCmd {
    data class MoveTo(val x: Float, val y: Float) : PathCmd()
    data class LineTo(val x: Float, val y: Float) : PathCmd()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCmd()
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCmd()

    /** Elliptic arc as in DrawingML: radii relative to the shape, angles in degrees. */
    data class ArcTo(val wR: Float, val hR: Float, val startDeg: Float, val sweepDeg: Float) : PathCmd()
    object Close : PathCmd()
}

data class GeoPath(val cmds: List<PathCmd>, val filled: Boolean, val stroked: Boolean)

enum class ShapeKind { TEXT, SHAPE, PICTURE, TABLE, CHART, OTHER }

enum class Anchor { TOP, CENTER, BOTTOM }

data class RunView(val text: String, val props: TextProps)

data class BulletView(val text: String, val color: Int?, val sizePct: Float, val font: String?)

data class ParaView(
    val runs: List<RunView>,
    val align: Align,
    val level: Int,
    val bullet: BulletView?,
    val marLEmu: Long,
    val indentEmu: Long,
    val spaceBeforePt: Float,
    val spaceAfterPt: Float,
    /** Line spacing as a multiple of single spacing, if given in percent. */
    val lineSpacingPct: Float?,
    /** Exact line spacing in points, if given in points. */
    val lineSpacingPt: Float?,
    /** Size of the paragraph mark, used for the height of empty paragraphs. */
    val endSizePt: Float,
) {
    val text: String get() = runs.joinToString("") { it.text }
}

data class Insets(val left: Long, val top: Long, val right: Long, val bottom: Long)

data class TextBodyView(
    val paras: List<ParaView>,
    val insets: Insets,
    val anchor: Anchor,
    /** False when bodyPr wrap="none". */
    val wrap: Boolean,
    /** From normAutofit: multiply run sizes by this (1 = no shrink). */
    val fontScale: Float,
    /** From normAutofit: reduce line spacing by this fraction. */
    val lineSpaceReduction: Float,
    /** bodyPr vert (horz, vert, vert270…), or null for horizontal text. */
    val vertical: String?,
    val anchorCenter: Boolean,
) {
    val isEmpty: Boolean get() = paras.all { it.text.isEmpty() }
}

data class CellView(
    val text: TextBodyView,
    val fill: Fill,
    val gridSpan: Int,
    val rowSpan: Int,
    /** Covered by a merged neighbour; do not draw. */
    val hMerge: Boolean,
    val vMerge: Boolean,
    val borderLeft: Line?,
    val borderTop: Line?,
    val borderRight: Line?,
    val borderBottom: Line?,
)

data class RowView(val heightEmu: Long, val cells: List<CellView>)

data class TableView(val colWidths: List<Long>, val rows: List<RowView>)

data class PlaceholderInfo(val type: String, val idx: String?)

class ShapeView(
    /** Unique within a presentation: "part#cNvPr id". */
    val id: String,
    val part: String,
    val nvId: Int,
    val name: String,
    /** True for shapes that live on the slide itself (not inherited from layout/master). */
    val editable: Boolean,
    val kind: ShapeKind,
    val frame: Frame,
    val rotationDeg: Float,
    val flipH: Boolean,
    val flipV: Boolean,
    /** Preset geometry name (rect, ellipse, roundRect, line…) or null for custom geometry. */
    val geometry: String?,
    /** Custom geometry paths, when [geometry] is null. */
    val paths: List<GeoPath>?,
    val fill: Fill,
    val line: Line?,
    /** For pictures: media part name, read with [PptxDocument.partBytes]. */
    val imagePart: String?,
    val imageCrop: Crop?,
    val text: TextBodyView?,
    val table: TableView?,
    val placeholder: PlaceholderInfo?,
    /** A slide placeholder with no text yet: show a hint such as "Tap to add title". */
    val isEmptyPlaceholder: Boolean,
    val chartTitle: String?,
)

data class SlideView(
    val index: Int,
    val part: String,
    val background: Fill,
    val shapes: List<ShapeView>,
    val hidden: Boolean,
    val layoutName: String?,
)

data class LayoutInfo(val part: String, val name: String, val type: String?)
