// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ooxml

import io.github.Prestgg.naroffice.xml.XElement
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Colors {

    fun hex(s: String?): Int? {
        if (s == null || s.equals("auto", true)) return null
        val h = s.trim().removePrefix("#")
        return when (h.length) {
            6 -> h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            8 -> h.toLongOrNull(16)?.let { v ->
                // Spreadsheet colours are AARRGGBB.
                val a = (v shr 24) and 0xFF
                (((if (a == 0L) 0xFFL else a) shl 24) or (v and 0xFFFFFF)).toInt()
            }
            else -> null
        }
    }

    fun toHex(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

    fun highlight(name: String): Int? = when (name) {
        "yellow" -> 0xFFFFFF00.toInt()
        "green" -> 0xFF00FF00.toInt()
        "cyan" -> 0xFF00FFFF.toInt()
        "magenta" -> 0xFFFF00FF.toInt()
        "blue" -> 0xFF0000FF.toInt()
        "red" -> 0xFFFF0000.toInt()
        "darkBlue" -> 0xFF000080.toInt()
        "darkCyan" -> 0xFF008080.toInt()
        "darkGreen" -> 0xFF008000.toInt()
        "darkMagenta" -> 0xFF800080.toInt()
        "darkRed" -> 0xFF800000.toInt()
        "darkYellow" -> 0xFF808000.toInt()
        "darkGray" -> 0xFF808080.toInt()
        "lightGray" -> 0xFFC0C0C0.toInt()
        "black" -> 0xFF000000.toInt()
        "white" -> 0xFFFFFFFF.toInt()
        else -> null
    }

    private val PRESET = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000,
        "blue" to 0x0000FF, "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080,
        "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3, "orange" to 0xFFA500, "purple" to 0x800080,
        "navy" to 0x000080, "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400,
        "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "silver" to 0xC0C0C0, "maroon" to 0x800000,
    )

    class Theme(root: XElement?) {
        private val colors = HashMap<String, Int>()
        val majorFont: String?
        val minorFont: String?

        init {
            val scheme = root?.path("a:themeElements", "a:clrScheme")
            scheme?.elements?.forEach { c ->
                val v = c.elements.firstOrNull()
                val rgb = when (v?.localName) {
                    "srgbClr" -> hex(v["val"])
                    "sysClr" -> hex(v["lastClr"]) ?: if (v["val"] == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    else -> null
                }
                if (rgb != null) colors[c.localName] = rgb
            }
            val fonts = root?.path("a:themeElements", "a:fontScheme")
            majorFont = fonts?.path("a:majorFont", "a:latin")?.get("typeface")
            minorFont = fonts?.path("a:minorFont", "a:latin")?.get("typeface")
        }

        /** Scheme slot (dk1, lt1, accent1…) or a WordprocessingML/SpreadsheetML alias. */
        fun color(name: String): Int? {
            val key = when (name) {
                "text1", "tx1", "dark1" -> "dk1"
                "background1", "bg1", "light1" -> "lt1"
                "text2", "tx2", "dark2" -> "dk2"
                "background2", "bg2", "light2" -> "lt2"
                "hyperlink" -> "hlink"
                "followedHyperlink" -> "folHlink"
                else -> name
            }
            return colors[key]
        }

        /** Spreadsheet theme colour index (0 = lt1, 1 = dk1, 2 = lt2, 3 = dk2, 4.. = accents). */
        fun indexed(i: Int): Int? {
            val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4",
                "accent5", "accent6", "hlink", "folHlink")
            return order.getOrNull(i)?.let { colors[it] }
        }

        fun font(themeName: String): String? =
            if (themeName.startsWith("major")) majorFont else minorFont
    }

    /**
     * Reads a DrawingML colour from an element containing srgbClr/schemeClr/sysClr/prstClr,
     * applying lumMod/lumOff/tint/shade/alpha. [clrMap] maps bg1/tx1… to scheme slots.
     */
    fun drawing(parent: XElement?, theme: Theme?, clrMap: Map<String, String>? = null, phClr: Int? = null): Int? {
        val c = parent?.elements?.firstOrNull {
            it.localName in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
        } ?: return null
        var argb: Int = when (c.localName) {
            "srgbClr" -> hex(c["val"])
            "sysClr" -> hex(c["lastClr"])
            "prstClr" -> PRESET[c["val"]]?.let { it or 0xFF000000.toInt() }
            "schemeClr" -> {
                val v = c["val"] ?: ""
                if (v == "phClr") phClr else {
                    val mapped = clrMap?.get(v) ?: v
                    theme?.color(mapped)
                }
            }
            "scrgbClr" -> {
                fun p(a: String) = ((c[a]?.toIntOrNull() ?: 0) / 100000f * 255).roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (p("r") shl 16) or (p("g") shl 8) or p("b")
            }
            else -> null
        } ?: return null
        for (m in c.elements) {
            val v = (m["val"]?.toIntOrNull() ?: continue) / 100000f
            argb = when (m.localName) {
                "lumMod" -> modLum(argb) { l -> l * v }
                "lumOff" -> modLum(argb) { l -> l + v }
                "tint" -> mix(argb, 0xFFFFFFFF.toInt(), 1 - v)
                "shade" -> mix(argb, 0xFF000000.toInt(), 1 - v)
                "alpha" -> (argb and 0xFFFFFF) or ((v * 255).roundToInt().coerceIn(0, 255) shl 24)
                else -> argb
            }
        }
        return argb
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).roundToInt().coerceIn(0, 255)
        return (a and 0xFF000000.toInt()) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Spreadsheet tint: negative darkens, positive lightens. */
    fun tint(argb: Int, tint: Float): Int =
        if (tint < 0) modLum(argb) { it * (1 + tint) } else modLum(argb) { it * (1 - tint) + tint }

    private inline fun modLum(argb: Int, f: (Float) -> Float): Int {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        var h: Float
        val s: Float
        var l = (mx + mn) / 2
        if (mx == mn) {
            h = 0f; s = 0f
        } else {
            val d = mx - mn
            s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h /= 6
        }
        l = f(l).coerceIn(0f, 1f)
        val (nr, ng, nb) = if (s == 0f) Triple(l, l, l) else {
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            Triple(hue(p, q, h + 1f / 3), hue(p, q, h), hue(p, q, h - 1f / 3))
        }
        return (argb and 0xFF000000.toInt()) or ((nr * 255).roundToInt() shl 16) or
            ((ng * 255).roundToInt() shl 8) or (nb * 255).roundToInt()
    }

    private fun hue(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }
}
