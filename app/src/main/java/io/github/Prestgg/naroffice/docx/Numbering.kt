// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.docx

import io.github.Prestgg.naroffice.xml.XElement

/** List numbering from numbering.xml, with running counters while laying out a document. */
class Numbering(private val root: XElement?) {

    class Level(val format: String, val text: String, val start: Int, val indentPt: Float, val hangingPt: Float)

    private val abstractByNum = HashMap<String, String>()
    private val levels = HashMap<String, HashMap<Int, Level>>()
    private val overrides = HashMap<String, HashMap<Int, Int>>()

    init {
        root?.children("w:abstractNum")?.forEach { a ->
            val id = a["w:abstractNumId"] ?: return@forEach
            val map = HashMap<Int, Level>()
            a.children("w:lvl").forEach { l -> parseLevel(l)?.let { map[l["w:ilvl"]?.toIntOrNull() ?: 0] = it } }
            levels[id] = map
        }
        root?.children("w:num")?.forEach { n ->
            val id = n["w:numId"] ?: return@forEach
            abstractByNum[id] = n.child("w:abstractNumId")?.get("w:val") ?: return@forEach
            n.children("w:lvlOverride").forEach { o ->
                val lvl = o["w:ilvl"]?.toIntOrNull() ?: 0
                o.path("w:startOverride")?.get("w:val")?.toIntOrNull()?.let {
                    overrides.getOrPut(id) { HashMap() }[lvl] = it
                }
            }
        }
    }

    private fun parseLevel(l: XElement): Level? {
        val fmt = l.child("w:numFmt")?.get("w:val") ?: "decimal"
        val text = l.child("w:lvlText")?.get("w:val") ?: ""
        val start = l.child("w:start")?.get("w:val")?.toIntOrNull() ?: 1
        val ind = l.path("w:pPr", "w:ind")
        val left = (ind?.get("w:left") ?: ind?.get("w:start"))?.toFloatOrNull()?.div(20f) ?: 0f
        val hanging = ind?.get("w:hanging")?.toFloatOrNull()?.div(20f) ?: 0f
        return Level(fmt, text, start, left, hanging)
    }

    fun level(numId: String, ilvl: Int): Level? = abstractByNum[numId]?.let { levels[it]?.get(ilvl) }

    fun isBullet(numId: String): Boolean = level(numId, 0)?.format == "bullet"

    /** Ids of existing bullet and decimal lists, so that new list paragraphs can reuse them. */
    fun findNumId(bullet: Boolean): String? = abstractByNum.keys.sortedBy { it.toIntOrNull() ?: 0 }.firstOrNull { id ->
        val f = level(id, 0)?.format
        if (bullet) f == "bullet" else f == "decimal"
    }

    /** Counts list items in document order. Create one per layout pass. */
    inner class Counter {
        private val counts = HashMap<String, IntArray>()

        fun label(numId: String, ilvl: Int): String? {
            val lvl = level(numId, ilvl) ?: return null
            val abs = abstractByNum[numId] ?: numId
            val arr = counts.getOrPut(abs) { IntArray(9) { Int.MIN_VALUE } }
            val i = ilvl.coerceIn(0, 8)
            arr[i] = if (arr[i] == Int.MIN_VALUE) (overrides[numId]?.get(i) ?: lvl.start) else arr[i] + 1
            for (k in i + 1 until 9) arr[k] = Int.MIN_VALUE
            if (lvl.format == "bullet") return bulletGlyph(lvl.text)
            if (lvl.format == "none") return ""
            var out = lvl.text
            for (k in 0..i) {
                val l = level(numId, k) ?: continue
                val v = if (arr[k] == Int.MIN_VALUE) l.start else arr[k]
                out = out.replace("%${k + 1}", format(v, l.format))
            }
            return out
        }
    }

    companion object {
        fun bulletGlyph(t: String): String = when {
            t.isEmpty() -> "•"
            t[0] == '' || t[0] == '' || t[0] == '' -> "•"
            t[0] == 'o' -> "◦"
            t[0] == '' -> "▪"
            t[0] == '' || t[0] == '' -> "➢"
            t[0].code in 0xF000..0xF0FF -> "•"
            else -> t
        }

        fun format(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "decimalZero" -> if (n < 10) "0$n" else "$n"
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            val c = ('A' + (n - 1) % 26).toString()
            return c.repeat((n - 1) / 26 + 1)
        }

        fun roman(n0: Int): String {
            if (n0 <= 0 || n0 > 3999) return n0.toString()
            var n = n0
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            for (i in vals.indices) while (n >= vals[i]) {
                sb.append(syms[i]); n -= vals[i]
            }
            return sb.toString()
        }
    }
}
