// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

/** A0-based rectangular block of cells, inclusive on both ends. */
data class CellRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    val rows get() = r2 - r1 + 1
    val cols get() = c2 - c1 + 1
    operator fun contains(rc: Pair<Int, Int>) = rc.first in r1..r2 && rc.second in c1..c2
    fun contains(r: Int, c: Int) = r in r1..r2 && c in c1..c2

    override fun toString(): String =
        if (r1 == r2 && c1 == c2) CellRef.name(r1, c1) else CellRef.name(r1, c1) + ":" + CellRef.name(r2, c2)

    companion object {
        fun parse(s: String): CellRange? {
            val parts = s.trim().split(':')
            val a = CellRef.parse(parts[0]) ?: return null
            val b = if (parts.size > 1) CellRef.parse(parts[1]) ?: return null else a
            return CellRange(minOf(a.first, b.first), minOf(a.second, b.second),
                maxOf(a.first, b.first), maxOf(a.second, b.second))
        }
    }
}

object CellRef {
    /** "A" → 0, "AA" → 26. */
    fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters) {
            val u = ch.uppercaseChar()
            if (u !in 'A'..'Z') return -1
            n = n * 26 + (u - 'A' + 1)
        }
        return n - 1
    }

    fun colName(c: Int): String {
        var n = c + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun name(r: Int, c: Int) = colName(c) + (r + 1)

    /** Parses "B3" or "$B$3" into (row, col), 0-based. */
    fun parse(s: String): Pair<Int, Int>? {
        var i = 0
        val t = s.trim()
        if (i < t.length && t[i] == '$') i++
        val cs = i
        while (i < t.length && t[i].isLetter()) i++
        if (i == cs) return null
        val col = colIndex(t.substring(cs, i))
        if (i < t.length && t[i] == '$') i++
        val row = t.substring(i).toIntOrNull() ?: return null
        if (row < 1 || col < 0) return null
        return (row - 1) to col
    }

    fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()
    fun keyRow(k: Long): Int = (k shr 20).toInt()
    fun keyCol(k: Long): Int = (k and 0xFFFFF).toInt()
}
