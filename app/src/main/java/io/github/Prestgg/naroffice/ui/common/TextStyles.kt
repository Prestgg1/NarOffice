// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.text.TextProps

object TextStyles {

    fun family(font: String?): FontFamily {
        val f = font?.lowercase() ?: return FontFamily.Default
        return when {
            listOf("mono", "courier", "consolas", "menlo", "code").any { it in f } -> FontFamily.Monospace
            listOf("times", "serif", "cambria", "georgia", "garamond", "book", "roman", "palatino", "baskerville")
                .any { it in f } && "sans" !in f -> FontFamily.Serif
            listOf("script", "brush", "hand", "comic").any { it in f } -> FontFamily.Cursive
            else -> FontFamily.SansSerif
        }
    }

    /** Converts resolved props to a Compose span style. [scale] maps points to sp. */
    fun span(p: TextProps, scale: Float, defaultColor: Color = Color(0xFF1B1B1B), link: Boolean = false): SpanStyle {
        val deco = buildList {
            if (p.underline) add(TextDecoration.Underline)
            if (p.strike) add(TextDecoration.LineThrough)
        }
        val shifted = p.baseline != Baseline.NORMAL
        return SpanStyle(
            color = p.color?.let { Color(it) } ?: defaultColor,
            fontSize = (p.sizePt * scale * if (shifted) 0.65f else 1f).coerceAtLeast(1f).sp,
            fontWeight = if (p.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (p.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = family(p.font),
            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
            background = p.highlight?.let { Color(it) } ?: Color.Unspecified,
            baselineShift = when (p.baseline) {
                Baseline.SUPER -> BaselineShift.Superscript
                Baseline.SUB -> BaselineShift.Subscript
                Baseline.NORMAL -> null
            },
        )
    }

    fun align(a: Align): TextAlign = when (a) {
        Align.START -> TextAlign.Start
        Align.CENTER -> TextAlign.Center
        Align.END -> TextAlign.End
        Align.JUSTIFY -> TextAlign.Justify
    }
}
