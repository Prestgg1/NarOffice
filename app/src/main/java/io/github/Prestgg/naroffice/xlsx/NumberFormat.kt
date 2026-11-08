// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/** Formats numbers with Excel number-format codes (the common subset). */
object NumberFormat {

    val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        5 to "#,##0 ;(#,##0)", 6 to "#,##0 ;[Red](#,##0)", 7 to "#,##0.00;(#,##0.00)", 8 to "#,##0.00;[Red](#,##0.00)",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "dd.mm.yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd.mm.yyyy h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)",
        40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )

    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")
    private val DAYS = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** Splits a format code on unquoted ';'. */
    private fun sections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { q = !q; sb.append(c) }
                c == '\\' && i + 1 < code.length -> { sb.append(c).append(code[i + 1]); i++ }
                c == ';' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** Removes quoted text, escapes and bracket blocks, for classification. */
    private fun stripped(code: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when (c) {
                '"' -> { val e = code.indexOf('"', i + 1); i = if (e < 0) code.length else e }
                '\\', '_', '*' -> i++
                '[' -> { val e = code.indexOf(']', i + 1); i = if (e < 0) code.length else e }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun isDate(code: String): Boolean {
        if (code.equals("General", true)) return false
        val s = stripped(sections(code)[0]).lowercase()
        if (code.contains("[h]", true) || code.contains("[m]", true) || code.contains("[s]", true)) return true
        return s.any { it in "ymdhs" } && !s.contains("general")
    }

    fun format(v: Double, code0: String, date1904: Boolean = false): String {
        val code = code0.ifEmpty { "General" }
        if (code.equals("General", true)) return general(v)
        if (code == "@") return general(v)
        val secs = sections(code)
        // Conditional sections like [>100] are rare; treat them positionally.
        var sec: String
        var value = v
        when {
            v < 0 && secs.size >= 2 && secs[1].isNotEmpty() -> { sec = secs[1]; value = -v }
            v == 0.0 && secs.size >= 3 -> sec = secs[2]
            else -> sec = secs[0]
        }
        if (sec.equals("General", true)) return general(value)
        return if (isDate(sec)) formatDate(value, sec, date1904) else formatNumber(value, sec)
    }

    fun general(v: Double): String {
        if (v.isNaN()) return "#NUM!"
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) {
            val exp = floor(log10(a)).toInt()
            var mant = v / Math.pow(10.0, exp.toDouble())
            var e = exp
            var ms = BigDecimal.valueOf(mant).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            if (ms.trimStart('-').startsWith("10")) {
                mant /= 10; e += 1
                ms = BigDecimal.valueOf(mant).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            }
            return ms + "E" + (if (e < 0) "-" else "+") + String.format("%02d", abs(e))
        }
        // Ten significant digits, like a default-width Excel column.
        val intDigits = (floor(log10(a)).toInt() + 1).coerceAtLeast(1)
        val digits = (10 - intDigits).coerceIn(0, 9)
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun formatNumber(v: Double, sec: String): String {
        // Literal prefix/suffix and the numeric pattern in between.
        val sb = StringBuilder()
        var pattern = StringBuilder()
        var patternStart = -1
        val pieces = ArrayList<Pair<Boolean, String>>() // (isPattern, text)
        var i = 0
        fun flushPattern() {
            if (pattern.isNotEmpty()) { pieces.add(true to pattern.toString()); pattern = StringBuilder() }
        }
        while (i < sec.length) {
            val c = sec[i]
            when {
                c == '"' -> {
                    flushPattern()
                    val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }
                    pieces.add(false to sec.substring(i + 1, e)); i = e
                }
                c == '\\' && i + 1 < sec.length -> { flushPattern(); pieces.add(false to sec[i + 1].toString()); i++ }
                c == '_' && i + 1 < sec.length -> { flushPattern(); pieces.add(false to " "); i++ }
                c == '*' && i + 1 < sec.length -> { i++ }
                c == '[' -> {
                    val e = sec.indexOf(']', i + 1).let { if (it < 0) sec.length else it }
                    val inner = sec.substring(i + 1, e)
                    if (inner.startsWith("$")) {
                        flushPattern()
                        pieces.add(false to inner.removePrefix("$").substringBefore('-'))
                    }
                    i = e
                }
                c in "0#?.,%Ee+-" && (c != '-' && c != '+' || pattern.isNotEmpty() && pattern.last() in "Ee") -> {
                    if (patternStart < 0) patternStart = i
                    pattern.append(c)
                }
                c == '/' && pattern.isNotEmpty() -> pattern.append(c)
                c == ' ' && pattern.isNotEmpty() && i + 1 < sec.length && sec[i + 1] in "#0?" && sec.contains('/') -> pattern.append(c)
                else -> { flushPattern(); pieces.add(false to c.toString()) }
            }
            i++
        }
        flushPattern()
        val pat = pieces.filter { it.first }.joinToString("") { it.second }
        val percent = pat.count { it == '%' } + pieces.filter { !it.first }.sumOf { p -> p.second.count { it == '%' } }
        var x = v
        repeat(percent) { x *= 100 }
        val numStr = numberPattern(x, pat.replace("%", ""))
        var placed = false
        for ((isPat, text) in pieces) {
            if (isPat) {
                if (!placed) { sb.append(numStr); placed = true }
                if (text.endsWith("%")) sb.append("%".repeat(text.count { it == '%' }))
            } else sb.append(text)
        }
        if (!placed) sb.append(numStr)
        return sb.toString()
    }

    private fun numberPattern(v: Double, pat0: String): String {
        if (pat0.isEmpty()) return ""
        var pat = pat0
        // Trailing commas scale by thousands.
        while (pat.endsWith(",")) { pat = pat.dropLast(1) }
        val eIdx = pat.indexOfFirst { it == 'E' || it == 'e' }
        if (eIdx >= 0) {
            val mantPat = pat.substring(0, eIdx)
            val expDigits = pat.substring(eIdx + 2).count { it == '0' }.coerceAtLeast(1)
            val decimals = mantPat.substringAfter('.', "").count { it == '0' || it == '#' }
            if (v == 0.0) return "0" + (if (decimals > 0) "." + "0".repeat(decimals) else "") + "E+" + "0".repeat(expDigits)
            var exp = floor(log10(abs(v))).toInt()
            var mant = v / Math.pow(10.0, exp.toDouble())
            var ms = BigDecimal.valueOf(mant).setScale(decimals, RoundingMode.HALF_UP)
            if (ms.abs() >= BigDecimal.TEN) { exp++; mant /= 10; ms = BigDecimal.valueOf(mant).setScale(decimals, RoundingMode.HALF_UP) }
            return ms.toPlainString() + "E" + (if (exp < 0) "-" else "+") + abs(exp).toString().padStart(expDigits, '0')
        }
        if (pat.contains('/')) {
            // Fractions: approximate with up to 2-digit denominators.
            val whole = floor(v).toLong()
            val frac = v - whole
            var bestN = 0L; var bestD = 1L; var err = 1.0
            val maxD = if (pat.substringAfter('/').length >= 2) 99 else 9
            for (d in 1..maxD) {
                val n = Math.round(frac * d)
                val e = abs(frac - n.toDouble() / d)
                if (e < err - 1e-12) { err = e; bestN = n; bestD = d.toLong() }
            }
            return if (bestN == 0L) whole.toString() else if (whole == 0L) "$bestN/$bestD" else "$whole $bestN/$bestD"
        }
        val intPat = pat.substringBefore('.')
        val decPat = pat.substringAfter('.', "")
        val grouping = intPat.contains(',')
        val minInt = intPat.count { it == '0' }
        val minDec = decPat.count { it == '0' }
        val maxDec = decPat.count { it == '0' || it == '#' || it == '?' }
        val bd = BigDecimal.valueOf(abs(v)).setScale(maxDec, RoundingMode.HALF_UP)
        var s = bd.toPlainString()
        var intPart = s.substringBefore('.')
        var decPart = s.substringAfter('.', "")
        while (decPart.length > minDec && decPart.endsWith("0")) decPart = decPart.dropLast(1)
        if (intPart == "0" && minInt == 0) intPart = ""
        intPart = intPart.padStart(minInt, '0')
        if (grouping && intPart.length > 3) {
            val g = StringBuilder()
            for ((k, ch) in intPart.reversed().withIndex()) {
                if (k > 0 && k % 3 == 0) g.append(',')
                g.append(ch)
            }
            intPart = g.reverse().toString()
        }
        s = if (decPat.isNotEmpty() && (decPart.isNotEmpty() || pat.endsWith("."))) "$intPart.$decPart" else intPart
        if (s.isEmpty()) s = "0"
        val neg = v < 0 && bd.signum() != 0
        return if (neg) "-$s" else s
    }

    private fun formatDate(v: Double, sec: String, date1904: Boolean): String {
        val dt = Dates.fromSerialTime(v, date1904) ?: return "#".repeat(8)
        val sb = StringBuilder()
        val low = sec.lowercase()
        val ampm = low.contains("am/pm") || low.contains("a/p")
        // Tokenize, then decide whether each "m" is a month or minutes.
        data class Tk(val kind: Char, val len: Int, val lit: String = "")
        val toks = ArrayList<Tk>()
        var i = 0
        while (i < sec.length) {
            val c = sec[i]
            val lc = c.lowercaseChar()
            when {
                c == '"' -> {
                    val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }
                    toks.add(Tk('L', 0, sec.substring(i + 1, e))); i = e + 1
                }
                c == '\\' && i + 1 < sec.length -> { toks.add(Tk('L', 0, sec[i + 1].toString())); i += 2 }
                c == '[' -> {
                    val e = sec.indexOf(']', i + 1).let { if (it < 0) sec.length else it }
                    val inner = sec.substring(i + 1, e).lowercase()
                    if (inner.isNotEmpty() && inner.all { it == 'h' }) toks.add(Tk('H', inner.length))
                    else if (inner.isNotEmpty() && inner.all { it == 'm' }) toks.add(Tk('M', inner.length))
                    else if (inner.isNotEmpty() && inner.all { it == 's' }) toks.add(Tk('S', inner.length))
                    i = e + 1
                }
                low.startsWith("am/pm", i) -> { toks.add(Tk('A', 5)); i += 5 }
                low.startsWith("a/p", i) -> { toks.add(Tk('A', 3)); i += 3 }
                lc in "ymdhs" -> {
                    var j = i
                    while (j < sec.length && sec[j].lowercaseChar() == lc) j++
                    toks.add(Tk(lc, j - i)); i = j
                }
                c == '0' && toks.lastOrNull()?.kind == 'L' && toks.last().lit == "." -> {
                    var j = i
                    while (j < sec.length && sec[j] == '0') j++
                    toks.add(Tk('f', j - i)); i = j
                }
                c == '_' || c == '*' -> i += 2
                else -> { toks.add(Tk('L', 0, c.toString())); i++ }
            }
        }
        // m after h or before s means minutes.
        val kinds = toks.map { it.kind }.toMutableList()
        for ((k, t) in toks.withIndex()) {
            if (t.kind != 'm') continue
            val prev = (k - 1 downTo 0).firstOrNull { toks[it].kind != 'L' }?.let { toks[it].kind }
            val next = (k + 1 until toks.size).firstOrNull { toks[it].kind != 'L' }?.let { toks[it].kind }
            if (prev == 'h' || prev == 'H' || next == 's' || next == 'S') kinds[k] = 'n'
        }
        val totalSecs = Math.round(v * 86400.0)
        for ((k, t) in toks.withIndex()) {
            when (kinds[k]) {
                'L' -> sb.append(t.lit)
                'y' -> sb.append(if (t.len <= 2) String.format("%02d", dt.year % 100) else dt.year.toString())
                'm' -> sb.append(
                    when (t.len) {
                        1 -> dt.monthValue.toString()
                        2 -> String.format("%02d", dt.monthValue)
                        3 -> MONTHS[dt.monthValue - 1].take(3)
                        5 -> MONTHS[dt.monthValue - 1].take(1)
                        else -> MONTHS[dt.monthValue - 1]
                    },
                )
                'd' -> sb.append(
                    when (t.len) {
                        1 -> dt.dayOfMonth.toString()
                        2 -> String.format("%02d", dt.dayOfMonth)
                        3 -> DAYS[dt.dayOfWeek.value - 1].take(3)
                        else -> DAYS[dt.dayOfWeek.value - 1]
                    },
                )
                'h' -> {
                    var h = dt.hour
                    if (ampm) { h %= 12; if (h == 0) h = 12 }
                    sb.append(if (t.len >= 2) String.format("%02d", h) else h.toString())
                }
                'n' -> sb.append(if (t.len >= 2) String.format("%02d", dt.minute) else dt.minute.toString())
                's' -> sb.append(if (t.len >= 2) String.format("%02d", dt.second) else dt.second.toString())
                'H' -> sb.append((totalSecs / 3600).toString().padStart(t.len, '0'))
                'M' -> sb.append((totalSecs / 60).toString().padStart(t.len, '0'))
                'S' -> sb.append(totalSecs.toString().padStart(t.len, '0'))
                'A' -> sb.append(if (t.len == 5) (if (dt.hour < 12) "AM" else "PM") else (if (dt.hour < 12) "A" else "P"))
                'f' -> {
                    val frac = (v * 86400.0) - floor(v * 86400.0)
                    sb.append(String.format("%0${t.len}d", Math.round(frac * Math.pow(10.0, t.len.toDouble())).coerceAtMost(Math.pow(10.0, t.len.toDouble()).toLong() - 1)))
                }
            }
        }
        return sb.toString()
    }
}
