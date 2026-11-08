// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xlsx

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** A spreadsheet value. */
sealed class XValue {
    object Empty : XValue() {
        override fun toString() = ""
    }

    data class Num(val v: Double) : XValue()
    data class Str(val s: String) : XValue()
    data class Bool(val b: Boolean) : XValue()
    data class Err(val code: String) : XValue()

    companion object {
        val DIV0 = Err("#DIV/0!")
        val VALUE = Err("#VALUE!")
        val REF = Err("#REF!")
        val NAME = Err("#NAME?")
        val NA = Err("#N/A")
        val NUM = Err("#NUM!")
        val ERRORS = setOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!", "#GETTING_DATA")
    }
}

/** One end of a reference; null row/col means a whole column/row reference. */
data class RefPart(val row: Int?, val col: Int?, val absRow: Boolean, val absCol: Boolean) {
    override fun toString(): String {
        val sb = StringBuilder()
        if (col != null) {
            if (absCol) sb.append('$'); sb.append(CellRef.colName(col))
        }
        if (row != null) {
            if (absRow) sb.append('$'); sb.append(row + 1)
        }
        return sb.toString()
    }
}

data class RefTok(val sheet: String?, val a: RefPart, val b: RefPart?) {
    override fun toString(): String {
        val sb = StringBuilder()
        if (sheet != null) sb.append(Formula.quoteSheet(sheet)).append('!')
        sb.append(a)
        if (b != null) sb.append(':').append(b)
        return sb.toString()
    }
}

/** Evaluation environment provided by the workbook. */
interface EvalContext {
    /** Sheet index for a name (null = the formula's own sheet), or -1. */
    fun sheetIndex(name: String?): Int
    fun value(sheet: Int, row: Int, col: Int): XValue
    /** Used extent (maxRow, maxCol), 0-based, of a sheet. */
    fun extent(sheet: Int): Pair<Int, Int>
    val date1904: Boolean
}

object Formula {

    // ------------------------------------------------------------ tokens

    enum class T { NUM, STR, BOOL, ERR, REF, FUNC, NAME, OP, LP, RP, SEP, WS, UNKNOWN }

    class Tok(val type: T, val text: String, val ref: RefTok? = null, val str: String? = null)

    private const val MAX_COL = 16384
    private const val MAX_ROW = 1048576

    fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> {
                    val st = i
                    while (i < n && s[i].isWhitespace()) i++
                    out.add(Tok(T.WS, s.substring(st, i)))
                }
                c == '"' -> {
                    val st = i
                    i++
                    val sb = StringBuilder()
                    while (i < n) {
                        if (s[i] == '"') {
                            if (i + 1 < n && s[i + 1] == '"') {
                                sb.append('"'); i += 2; continue
                            }
                            i++; break
                        }
                        sb.append(s[i]); i++
                    }
                    out.add(Tok(T.STR, s.substring(st, i), null, sb.toString()))
                }
                c == '#' -> {
                    val code = XValue.ERRORS.firstOrNull { s.startsWith(it, i, ignoreCase = true) }
                    if (code != null) {
                        out.add(Tok(T.ERR, code)); i += code.length
                    } else {
                        out.add(Tok(T.UNKNOWN, c.toString())); i++
                    }
                }
                c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit()) -> {
                    // Row range like 1:3 ?
                    val rr = tryRef(s, i, null)
                    if (rr != null && rr.second.b != null && rr.second.a.col == null) {
                        out.add(Tok(T.REF, s.substring(i, rr.first), rr.second)); i = rr.first
                    } else {
                        val st = i
                        while (i < n && (s[i].isDigit() || s[i] == '.')) i++
                        if (i < n && (s[i] == 'e' || s[i] == 'E') && i + 1 < n &&
                            (s[i + 1].isDigit() || ((s[i + 1] == '+' || s[i + 1] == '-') && i + 2 < n && s[i + 2].isDigit()))
                        ) {
                            i += 2
                            while (i < n && s[i].isDigit()) i++
                        }
                        out.add(Tok(T.NUM, s.substring(st, i)))
                    }
                }
                c == '\'' -> {
                    // Quoted sheet name.
                    var j = i + 1
                    val sb = StringBuilder()
                    while (j < n) {
                        if (s[j] == '\'') {
                            if (j + 1 < n && s[j + 1] == '\'') {
                                sb.append('\''); j += 2; continue
                            }
                            break
                        }
                        sb.append(s[j]); j++
                    }
                    if (j + 1 < n && s[j] == '\'' && s[j + 1] == '!') {
                        val r = tryRef(s, j + 2, sb.toString())
                        if (r != null) {
                            out.add(Tok(T.REF, s.substring(i, r.first), r.second)); i = r.first
                            continue
                        }
                    }
                    out.add(Tok(T.UNKNOWN, c.toString())); i++
                }
                c == '$' || c.isLetter() || c == '_' || c == '\\' -> {
                    // Identifier: function, boolean, sheet prefix, reference or name.
                    var j = i
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.' || s[j] == '$' || s[j] == '\\')) j++
                    val word = s.substring(i, j)
                    if (j < n && s[j] == '!' && !word.contains('$')) {
                        val r = tryRef(s, j + 1, word)
                        if (r != null) {
                            out.add(Tok(T.REF, s.substring(i, r.first), r.second)); i = r.first
                            continue
                        }
                    }
                    if (j < n && s[j] == '(' && !word.contains('$')) {
                        out.add(Tok(T.FUNC, word)); i = j + 1
                        continue
                    }
                    val r = tryRef(s, i, null)
                    if (r != null) {
                        out.add(Tok(T.REF, s.substring(i, r.first), r.second)); i = r.first
                        continue
                    }
                    when (word.uppercase()) {
                        "TRUE", "FALSE" -> out.add(Tok(T.BOOL, word))
                        else -> out.add(Tok(T.NAME, word))
                    }
                    i = j
                }
                c == '(' -> { out.add(Tok(T.LP, "(")); i++ }
                c == ')' -> { out.add(Tok(T.RP, ")")); i++ }
                c == ',' || c == ';' -> { out.add(Tok(T.SEP, c.toString())); i++ }
                c == '<' && i + 1 < n && (s[i + 1] == '=' || s[i + 1] == '>') -> {
                    out.add(Tok(T.OP, s.substring(i, i + 2))); i += 2
                }
                c == '>' && i + 1 < n && s[i + 1] == '=' -> { out.add(Tok(T.OP, ">=")); i += 2 }
                c in "+-*/^&=<>%" -> { out.add(Tok(T.OP, c.toString())); i++ }
                else -> { out.add(Tok(T.UNKNOWN, c.toString())); i++ }
            }
        }
        return out
    }

    private fun strValue(t: Tok): String = t.str ?: t.text.removeSurrounding("\"")

    /** Parses one reference part at i: returns end index and part. */
    private fun part(s: String, start: Int): Pair<Int, RefPart>? {
        var i = start
        val n = s.length
        var absCol = false
        var absRow = false
        if (i < n && s[i] == '$') { absCol = true; i++ }
        val cs = i
        while (i < n && s[i].isLetter() && s[i].code < 128) i++
        val letters = s.substring(cs, i)
        if (letters.length > 3) return null
        val col = if (letters.isEmpty()) null else CellRef.colIndex(letters)
        if (col != null && (col < 0 || col >= MAX_COL)) return null
        if (letters.isEmpty() && absCol) {
            // "$5" — a row part with absolute marker
            absRow = true; absCol = false
        } else if (i < n && s[i] == '$') { absRow = true; i++ }
        val ds = i
        while (i < n && s[i].isDigit()) i++
        val row = if (i > ds) s.substring(ds, i).toIntOrNull()?.minus(1) else null
        if (row != null && (row < 0 || row >= MAX_ROW)) return null
        if (col == null && row == null) return null
        if (absRow && row == null) return null
        return i to RefPart(row, col, absRow, absCol)
    }

    private fun tryRef(s: String, start: Int, sheet: String?): Pair<Int, RefTok>? {
        val a = part(s, start) ?: return null
        var end = a.first
        var b: RefPart? = null
        if (end < s.length && s[end] == ':') {
            val bp = part(s, end + 1)
            if (bp != null && (bp.second.row == null) == (a.second.row == null) &&
                (bp.second.col == null) == (a.second.col == null)
            ) {
                b = bp.second; end = bp.first
            }
        }
        // A lone column ("A") or lone row ("3") is not a reference.
        if (b == null && (a.second.row == null || a.second.col == null)) return null
        // The reference must not run into more identifier characters ("A1B").
        if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_' || s[end] == '(')) return null
        return end to RefTok(sheet, a.second, b)
    }

    fun quoteSheet(name: String): String {
        val plain = name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') &&
            name.all { it.isLetterOrDigit() || it == '_' || it == '.' } &&
            CellRef.parse(name) == null && !name.equals("TRUE", true) && !name.equals("FALSE", true)
        return if (plain) name else "'" + name.replace("'", "''") + "'"
    }

    /**
     * Rewrites every reference in [text]; [map] returns the new reference, or null for #REF!.
     * Everything else is kept exactly as written.
     */
    fun rewriteRefs(text: String, map: (RefTok) -> RefTok?): String {
        val sb = StringBuilder(text.length + 8)
        for (t in tokenize(text)) {
            if (t.type == T.REF) {
                val r = map(t.ref!!)
                if (r == null) sb.append("#REF!")
                else if (r == t.ref) sb.append(t.text)
                else sb.append(r)
            } else if (t.type == T.FUNC) {
                sb.append(t.text).append('(')
            } else sb.append(t.text)
        }
        return sb.toString()
    }

    /** Moves relative references by (dr, dc), as when copying or expanding a shared formula. */
    fun translate(text: String, dr: Int, dc: Int): String = rewriteRefs(text) { r ->
        fun mv(p: RefPart): RefPart? {
            val row = if (p.row != null && !p.absRow) p.row + dr else p.row
            val col = if (p.col != null && !p.absCol) p.col + dc else p.col
            if ((row != null && row < 0) || (col != null && col < 0)) return null
            return p.copy(row = row, col = col)
        }
        val a = mv(r.a) ?: return@rewriteRefs null
        val b = r.b?.let { mv(it) ?: return@rewriteRefs null }
        r.copy(a = a, b = b)
    }

    /**
     * Shifts references after rows (or columns) were inserted (count > 0) or deleted (count < 0)
     * at [at]. [affects] decides whether a reference's sheet is the changed sheet.
     */
    fun shift(text: String, rows: Boolean, at: Int, count: Int, affects: (String?) -> Boolean): String =
        rewriteRefs(text) { r ->
            if (!affects(r.sheet)) return@rewriteRefs r
            fun get(p: RefPart) = if (rows) p.row else p.col
            fun set(p: RefPart, v: Int) = if (rows) p.copy(row = v) else p.copy(col = v)
            val a = r.a
            val b = r.b
            if (get(a) == null) return@rewriteRefs r // whole column when shifting rows, etc.
            if (count > 0) {
                fun mv(p: RefPart) = get(p)!!.let { if (it >= at) set(p, it + count) else p }
                r.copy(a = mv(a), b = b?.let(::mv))
            } else {
                val k = -count
                val delEnd = at + k - 1
                if (b == null) {
                    val v = get(a)!!
                    when {
                        v in at..delEnd -> null
                        v > delEnd -> r.copy(a = set(a, v - k))
                        else -> r
                    }
                } else {
                    var v1 = get(a)!!
                    var v2 = get(b)!!
                    if (v1 > v2) { val t = v1; v1 = v2; v2 = t }
                    if (v1 >= at && v2 <= delEnd) return@rewriteRefs null
                    val n1 = when {
                        v1 > delEnd -> v1 - k
                        v1 >= at -> at
                        else -> v1
                    }
                    val n2 = when {
                        v2 > delEnd -> v2 - k
                        v2 >= at -> at - 1
                        else -> v2
                    }
                    r.copy(a = set(a, n1), b = set(b, n2))
                }
            }
        }

    /** Renames sheet prefixes. */
    fun renameSheet(text: String, old: String, new: String): String = rewriteRefs(text) { r ->
        if (r.sheet != null && r.sheet.equals(old, true)) r.copy(sheet = new) else r
    }

    /** Normalizes formula input typed by a user: ';' separators → ',', upper-case names. */
    fun normalizeInput(text: String): String {
        val sb = StringBuilder()
        for (t in tokenize(text)) {
            when (t.type) {
                T.SEP -> sb.append(',')
                T.FUNC -> sb.append(t.text.uppercase()).append('(')
                T.BOOL -> sb.append(t.text.uppercase())
                T.REF -> sb.append(t.ref.toString())
                else -> sb.append(t.text)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ AST

    sealed class Expr
    class Lit(val v: XValue) : Expr()
    class Ref(val sheet: String?, val row: Int, val col: Int) : Expr()
    class Area(val sheet: String?, val r1: Int?, val c1: Int?, val r2: Int?, val c2: Int?) : Expr()
    class Fn(val name: String, val args: List<Expr>) : Expr()
    class Un(val op: String, val e: Expr) : Expr()
    class Bin(val op: String, val l: Expr, val r: Expr) : Expr()
    class Pct(val e: Expr) : Expr()
    class Name(val name: String) : Expr()

    class Parsed(val expr: Expr?, val supported: Boolean)

    class ParseException(msg: String) : Exception(msg)

    private val cache = object : LinkedHashMap<String, Parsed>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?) = size > 4096
    }

    fun parse(text: String): Parsed {
        synchronized(cache) { cache[text]?.let { return it } }
        val p = try {
            val toks = tokenize(text).filter { it.type != T.WS }
            if (toks.any { it.type == T.UNKNOWN }) Parsed(null, false)
            else {
                val parser = Parser(toks)
                val e = parser.parseExpr()
                if (parser.pos != toks.size) throw ParseException("Trailing tokens")
                Parsed(e, isSupported(e))
            }
        } catch (e: ParseException) {
            Parsed(null, false)
        }
        synchronized(cache) { cache[text] = p }
        return p
    }

    private fun isSupported(e: Expr): Boolean = when (e) {
        is Fn -> fnName(e.name) in FUNCTIONS && e.args.all(::isSupported)
        is Un -> isSupported(e.e)
        is Bin -> isSupported(e.l) && isSupported(e.r)
        is Pct -> isSupported(e.e)
        is Name -> false
        else -> true
    }

    private fun fnName(n: String) = n.uppercase().removePrefix("_XLFN.").removePrefix("_XLWS.")

    private class Parser(val t: List<Tok>) {
        var pos = 0
        fun peek() = t.getOrNull(pos)
        fun isOp(vararg ops: String) = peek()?.let { it.type == T.OP && it.text in ops } == true

        fun parseExpr(): Expr = comparison()

        fun comparison(): Expr {
            var l = concat()
            while (isOp("=", "<>", "<", ">", "<=", ">=")) {
                val op = t[pos++].text
                l = Bin(op, l, concat())
            }
            return l
        }

        fun concat(): Expr {
            var l = additive()
            while (isOp("&")) { pos++; l = Bin("&", l, additive()) }
            return l
        }

        fun additive(): Expr {
            var l = mult()
            while (isOp("+", "-")) {
                val op = t[pos++].text
                l = Bin(op, l, mult())
            }
            return l
        }

        fun mult(): Expr {
            var l = power()
            while (isOp("*", "/")) {
                val op = t[pos++].text
                l = Bin(op, l, power())
            }
            return l
        }

        fun power(): Expr {
            var l = unary()
            while (isOp("^")) { pos++; l = Bin("^", l, unary()) }
            return l
        }

        fun unary(): Expr {
            if (isOp("-", "+")) {
                val op = t[pos++].text
                val e = unary()
                return if (op == "-") Un("-", e) else e
            }
            var e = primary()
            while (isOp("%")) { pos++; e = Pct(e) }
            return e
        }

        fun primary(): Expr {
            val tok = t.getOrNull(pos++) ?: throw ParseException("Unexpected end")
            return when (tok.type) {
                T.NUM -> Lit(XValue.Num(tok.text.toDoubleOrNull() ?: throw ParseException("Bad number")))
                T.STR -> Lit(XValue.Str(strValue(tok)))
                T.BOOL -> Lit(XValue.Bool(tok.text.equals("TRUE", true)))
                T.ERR -> Lit(XValue.Err(tok.text.uppercase()))
                T.REF -> {
                    val r = tok.ref!!
                    if (r.b == null) Ref(r.sheet, r.a.row!!, r.a.col!!)
                    else Area(r.sheet, r.a.row, r.a.col, r.b.row, r.b.col)
                }
                T.NAME -> Name(tok.text)
                T.FUNC -> {
                    val args = ArrayList<Expr>()
                    if (peek()?.type == T.RP) { pos++; return Fn(tok.text, args) }
                    while (true) {
                        if (peek()?.type == T.SEP || peek()?.type == T.RP) args.add(Lit(XValue.Empty))
                        else args.add(parseExpr())
                        val nx = t.getOrNull(pos++) ?: throw ParseException("Unclosed call")
                        if (nx.type == T.RP) break
                        if (nx.type != T.SEP) throw ParseException("Expected separator")
                    }
                    Fn(tok.text, args)
                }
                T.LP -> {
                    val e = parseExpr()
                    if (t.getOrNull(pos++)?.type != T.RP) throw ParseException("Expected )")
                    e
                }
                else -> throw ParseException("Unexpected ${tok.text}")
            }
        }
    }

    // ------------------------------------------------------------ evaluation

    private class EvalError(val v: XValue.Err) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun fail(v: XValue.Err): Nothing = throw EvalError(v)

    /** Evaluates [text] (without '=') on [sheet]. */
    fun evaluate(text: String, sheet: Int, ctx: EvalContext): XValue {
        val p = parse(text)
        val e = p.expr ?: return XValue.NAME
        return evaluate(e, sheet, ctx)
    }

    fun evaluate(e: Expr, sheet: Int, ctx: EvalContext): XValue = try {
        when (val v = Evaluator(sheet, ctx).scalar(e)) {
            XValue.Empty -> XValue.Num(0.0)
            is XValue.Num -> if (v.v.isNaN() || v.v.isInfinite()) XValue.NUM else v
            else -> v
        }
    } catch (ex: EvalError) {
        ex.v
    } catch (ex: StackOverflowError) {
        XValue.REF
    }

    /** A rectangular block resolved to a sheet index. */
    class Block(val sheet: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
        val rows get() = r2 - r1 + 1
        val cols get() = c2 - c1 + 1
    }

    val FUNCTIONS = setOf(
        "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "COUNTBLANK", "IF", "IFERROR", "AND", "OR",
        "NOT", "ROUND", "ROUNDUP", "ROUNDDOWN", "INT", "ABS", "SQRT", "POWER", "MOD", "PRODUCT", "LEN",
        "LEFT", "RIGHT", "MID", "UPPER", "LOWER", "TRIM", "CONCATENATE", "CONCAT", "TEXT", "VALUE",
        "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY", "SUMIF", "COUNTIF", "AVERAGEIF", "VLOOKUP",
        "HLOOKUP", "INDEX", "MATCH", "PI", "RAND", "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "IFNA",
        "TRUE", "FALSE", "SIGN", "AVERAGEA", "MEDIAN", "HOUR", "MINUTE", "SECOND", "WEEKDAY", "EXACT",
        "REPT", "SUBSTITUTE", "FIND", "SEARCH", "PROPER", "CHAR", "CODE", "NA", "ISNA", "CEILING", "FLOOR",
        "EXP", "LN", "LOG10", "LOG", "TIME", "DAYS", "SUMPRODUCT", "XOR",
    )

    private class Evaluator(val sheet: Int, val ctx: EvalContext) {

        fun sheetOf(name: String?): Int {
            if (name == null) return sheet
            val s = ctx.sheetIndex(name)
            if (s < 0) fail(XValue.REF)
            return s
        }

        fun block(a: Area): Block {
            val s = sheetOf(a.sheet)
            val (mr, mc) = ctx.extent(s)
            val r1 = a.r1 ?: 0
            val r2 = a.r2 ?: mr
            val c1 = a.c1 ?: 0
            val c2 = a.c2 ?: mc
            return Block(s, minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
        }

        fun blockOf(e: Expr): Block? = when (e) {
            is Area -> block(e)
            is Ref -> Block(sheetOf(e.sheet), e.row, e.col, e.row, e.col)
            else -> null
        }

        fun cell(s: Int, r: Int, c: Int): XValue {
            val v = ctx.value(s, r, c)
            return v
        }

        /** Evaluates to a single value; a multi-cell area becomes #VALUE!. */
        fun scalar(e: Expr): XValue = when (e) {
            is Lit -> e.v
            is Ref -> cell(sheetOf(e.sheet), e.row, e.col)
            is Area -> {
                val b = block(e)
                if (b.rows == 1 && b.cols == 1) cell(b.sheet, b.r1, b.c1) else XValue.VALUE
            }
            is Name -> XValue.NAME
            is Pct -> XValue.Num(num(scalar(e.e)) / 100.0)
            is Un -> XValue.Num(-num(scalar(e.e)))
            is Bin -> binary(e)
            is Fn -> call(fnName(e.name), e.args)
        }

        fun value(e: Expr): XValue {
            val v = scalar(e)
            if (v is XValue.Err) fail(v)
            return v
        }

        fun num(v: XValue): Double = when (v) {
            is XValue.Num -> v.v
            XValue.Empty -> 0.0
            is XValue.Bool -> if (v.b) 1.0 else 0.0
            is XValue.Str -> parseNumber(v.s) ?: fail(XValue.VALUE)
            is XValue.Err -> fail(v)
        }

        fun num(e: Expr): Double = num(scalar(e))

        fun text(v: XValue): String = when (v) {
            is XValue.Num -> numText(v.v)
            XValue.Empty -> ""
            is XValue.Bool -> if (v.b) "TRUE" else "FALSE"
            is XValue.Str -> v.s
            is XValue.Err -> fail(v)
        }

        fun text(e: Expr) = text(scalar(e))

        fun bool(v: XValue): Boolean = when (v) {
            is XValue.Bool -> v.b
            is XValue.Num -> v.v != 0.0
            XValue.Empty -> false
            is XValue.Str -> when {
                v.s.equals("TRUE", true) -> true
                v.s.equals("FALSE", true) -> false
                else -> fail(XValue.VALUE)
            }
            is XValue.Err -> fail(v)
        }

        fun binary(e: Bin): XValue {
            val l = scalar(e.l)
            if (l is XValue.Err) return l
            val r = scalar(e.r)
            if (r is XValue.Err) return r
            return when (e.op) {
                "+" -> XValue.Num(num(l) + num(r))
                "-" -> XValue.Num(num(l) - num(r))
                "*" -> XValue.Num(num(l) * num(r))
                "/" -> {
                    val d = num(r)
                    if (d == 0.0) XValue.DIV0 else XValue.Num(num(l) / d)
                }
                "^" -> XValue.Num(num(l).pow(num(r)))
                "&" -> XValue.Str(text(l) + text(r))
                else -> {
                    val c = compare(l, r)
                    XValue.Bool(
                        when (e.op) {
                            "=" -> c == 0
                            "<>" -> c != 0
                            "<" -> c < 0
                            ">" -> c > 0
                            "<=" -> c <= 0
                            else -> c >= 0
                        },
                    )
                }
            }
        }

        /** Iterates every value of an argument (cells of areas, or the scalar itself). */
        inline fun forEachValue(e: Expr, f: (XValue, Boolean) -> Unit) {
            val b = blockOf(e)
            if (b != null) {
                for (r in b.r1..b.r2) for (c in b.c1..b.c2) f(cell(b.sheet, r, c), true)
            } else {
                f(scalar(e), false)
            }
        }

        /** Numbers for SUM-like functions: text and booleans in ranges are skipped. */
        fun numbers(args: List<Expr>): List<Double> {
            val out = ArrayList<Double>()
            for (a in args) forEachValue(a) { v, inRange ->
                when (v) {
                    is XValue.Num -> out.add(v.v)
                    is XValue.Err -> fail(v)
                    else -> if (!inRange && v != XValue.Empty) out.add(num(v))
                }
            }
            return out
        }

        fun arg(args: List<Expr>, i: Int): Expr = args.getOrNull(i) ?: fail(XValue.VALUE)

        fun optNum(args: List<Expr>, i: Int, def: Double): Double {
            val a = args.getOrNull(i) ?: return def
            if (a is Lit && a.v == XValue.Empty) return def
            return num(a)
        }

        fun call(name: String, a: List<Expr>): XValue {
            return when (name) {
                "SUM" -> XValue.Num(numbers(a).sum())
                "PRODUCT" -> XValue.Num(numbers(a).fold(1.0) { x, y -> x * y })
                "AVERAGE" -> numbers(a).let { if (it.isEmpty()) XValue.DIV0 else XValue.Num(it.average()) }
                "AVERAGEA" -> {
                    var s = 0.0; var n = 0
                    for (x in a) forEachValue(x) { v, _ ->
                        when (v) {
                            is XValue.Err -> fail(v)
                            XValue.Empty -> {}
                            is XValue.Num -> { s += v.v; n++ }
                            is XValue.Bool -> { s += if (v.b) 1 else 0; n++ }
                            is XValue.Str -> n++
                        }
                    }
                    if (n == 0) XValue.DIV0 else XValue.Num(s / n)
                }
                "MEDIAN" -> numbers(a).sorted().let {
                    if (it.isEmpty()) XValue.NUM
                    else XValue.Num(if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2)
                }
                "MIN" -> XValue.Num(numbers(a).minOrNull() ?: 0.0)
                "MAX" -> XValue.Num(numbers(a).maxOrNull() ?: 0.0)
                "COUNT" -> {
                    var n = 0
                    for (x in a) forEachValue(x) { v, inRange ->
                        if (v is XValue.Num) n++
                        else if (!inRange && v is XValue.Str && parseNumber(v.s) != null) n++
                    }
                    XValue.Num(n.toDouble())
                }
                "COUNTA" -> {
                    var n = 0
                    for (x in a) forEachValue(x) { v, _ -> if (v != XValue.Empty) n++ }
                    XValue.Num(n.toDouble())
                }
                "COUNTBLANK" -> {
                    var n = 0
                    for (x in a) forEachValue(x) { v, _ -> if (v == XValue.Empty || v == XValue.Str("")) n++ }
                    XValue.Num(n.toDouble())
                }
                "IF" -> {
                    val c = bool(value(arg(a, 0)))
                    if (c) a.getOrNull(1)?.let { scalar(it) } ?: XValue.Bool(true)
                    else a.getOrNull(2)?.let { scalar(it) } ?: XValue.Bool(false)
                }
                "IFERROR" -> {
                    val v = try { scalar(arg(a, 0)) } catch (e: EvalError) { e.v }
                    if (v is XValue.Err) scalar(arg(a, 1)) else v
                }
                "IFNA" -> {
                    val v = try { scalar(arg(a, 0)) } catch (e: EvalError) { e.v }
                    if (v == XValue.NA) scalar(arg(a, 1)) else v
                }
                "AND", "OR", "XOR" -> {
                    val bs = ArrayList<Boolean>()
                    for (x in a) forEachValue(x) { v, inRange ->
                        when (v) {
                            is XValue.Err -> fail(v)
                            is XValue.Bool -> bs.add(v.b)
                            is XValue.Num -> bs.add(v.v != 0.0)
                            else -> if (!inRange && v != XValue.Empty) bs.add(bool(v))
                        }
                    }
                    if (bs.isEmpty()) XValue.VALUE
                    else XValue.Bool(
                        when (name) {
                            "AND" -> bs.all { it }
                            "OR" -> bs.any { it }
                            else -> bs.count { it } % 2 == 1
                        },
                    )
                }
                "NOT" -> XValue.Bool(!bool(value(arg(a, 0))))
                "TRUE" -> XValue.Bool(true)
                "FALSE" -> XValue.Bool(false)
                "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                    val x = num(arg(a, 0))
                    val d = optNum(a, 1, 0.0).toInt()
                    XValue.Num(round(x, d, name))
                }
                "INT" -> XValue.Num(floor(num(arg(a, 0))))
                "ABS" -> XValue.Num(abs(num(arg(a, 0))))
                "SIGN" -> XValue.Num(Math.signum(num(arg(a, 0))))
                "SQRT" -> num(arg(a, 0)).let { if (it < 0) XValue.NUM else XValue.Num(sqrt(it)) }
                "EXP" -> XValue.Num(kotlin.math.exp(num(arg(a, 0))))
                "LN" -> num(arg(a, 0)).let { if (it <= 0) XValue.NUM else XValue.Num(kotlin.math.ln(it)) }
                "LOG10" -> num(arg(a, 0)).let { if (it <= 0) XValue.NUM else XValue.Num(kotlin.math.log10(it)) }
                "LOG" -> {
                    val x = num(arg(a, 0)); val b = optNum(a, 1, 10.0)
                    if (x <= 0 || b <= 0 || b == 1.0) XValue.NUM else XValue.Num(kotlin.math.ln(x) / kotlin.math.ln(b))
                }
                "POWER" -> XValue.Num(num(arg(a, 0)).pow(num(arg(a, 1))))
                "MOD" -> {
                    val x = num(arg(a, 0)); val d = num(arg(a, 1))
                    if (d == 0.0) XValue.DIV0 else XValue.Num(x - d * floor(x / d))
                }
                "CEILING", "FLOOR" -> {
                    val x = num(arg(a, 0)); val s = optNum(a, 1, 1.0)
                    if (s == 0.0) XValue.Num(0.0)
                    else XValue.Num(if (name == "CEILING") kotlin.math.ceil(x / s) * s else floor(x / s) * s)
                }
                "PI" -> XValue.Num(Math.PI)
                "RAND" -> XValue.Num(Math.random())
                "LEN" -> XValue.Num(text(arg(a, 0)).length.toDouble())
                "LEFT" -> {
                    val s = text(arg(a, 0)); val n = optNum(a, 1, 1.0).toInt()
                    if (n < 0) XValue.VALUE else XValue.Str(s.take(n))
                }
                "RIGHT" -> {
                    val s = text(arg(a, 0)); val n = optNum(a, 1, 1.0).toInt()
                    if (n < 0) XValue.VALUE else XValue.Str(s.takeLast(n))
                }
                "MID" -> {
                    val s = text(arg(a, 0)); val st = num(arg(a, 1)).toInt(); val n = num(arg(a, 2)).toInt()
                    if (st < 1 || n < 0) XValue.VALUE
                    else XValue.Str(if (st > s.length) "" else s.substring(st - 1, minOf(s.length, st - 1 + n)))
                }
                "UPPER" -> XValue.Str(text(arg(a, 0)).uppercase())
                "LOWER" -> XValue.Str(text(arg(a, 0)).lowercase())
                "PROPER" -> XValue.Str(text(arg(a, 0)).lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
                "TRIM" -> XValue.Str(text(arg(a, 0)).trim().replace(Regex(" +"), " "))
                "EXACT" -> XValue.Bool(text(arg(a, 0)) == text(arg(a, 1)))
                "REPT" -> XValue.Str(text(arg(a, 0)).repeat(num(arg(a, 1)).toInt().coerceIn(0, 32767)))
                "CHAR" -> XValue.Str(num(arg(a, 0)).toInt().toChar().toString())
                "CODE" -> text(arg(a, 0)).let { if (it.isEmpty()) XValue.VALUE else XValue.Num(it[0].code.toDouble()) }
                "SUBSTITUTE" -> {
                    val s = text(arg(a, 0)); val old = text(arg(a, 1)); val new = text(arg(a, 2))
                    if (old.isEmpty()) XValue.Str(s)
                    else if (a.size > 3) {
                        val nth = num(a[3]).toInt()
                        var idx = -1
                        repeat(nth) { idx = s.indexOf(old, idx + 1); if (idx < 0) return XValue.Str(s) }
                        XValue.Str(s.substring(0, idx) + new + s.substring(idx + old.length))
                    } else XValue.Str(s.replace(old, new))
                }
                "FIND", "SEARCH" -> {
                    val f = text(arg(a, 0)); val s = text(arg(a, 1)); val st = optNum(a, 2, 1.0).toInt()
                    if (st < 1 || st > s.length + 1) XValue.VALUE
                    else {
                        val i = s.indexOf(f, st - 1, ignoreCase = name == "SEARCH")
                        if (i < 0) XValue.VALUE else XValue.Num((i + 1).toDouble())
                    }
                }
                "CONCATENATE" -> XValue.Str(a.joinToString("") { text(it) })
                "CONCAT" -> {
                    val sb = StringBuilder()
                    for (x in a) forEachValue(x) { v, _ -> sb.append(text(v)) }
                    XValue.Str(sb.toString())
                }
                "TEXT" -> {
                    val v = value(arg(a, 0))
                    val fmt = text(arg(a, 1))
                    val d = if (v is XValue.Num) v.v else parseNumber(text(v))
                    if (d == null) XValue.Str(text(v))
                    else XValue.Str(NumberFormat.format(d, fmt, ctx.date1904))
                }
                "VALUE" -> {
                    val v = value(arg(a, 0))
                    if (v is XValue.Num) v else XValue.Num(parseNumber(text(v)) ?: fail(XValue.VALUE))
                }
                "ISBLANK" -> XValue.Bool(scalar(arg(a, 0)) == XValue.Empty)
                "ISNUMBER" -> XValue.Bool(safe(arg(a, 0)) is XValue.Num)
                "ISTEXT" -> XValue.Bool(safe(arg(a, 0)) is XValue.Str)
                "ISERROR" -> XValue.Bool(safe(arg(a, 0)) is XValue.Err)
                "ISNA" -> XValue.Bool(safe(arg(a, 0)) == XValue.NA)
                "NA" -> XValue.NA
                "TODAY" -> XValue.Num(Dates.toSerial(LocalDate.now(), ctx.date1904))
                "NOW" -> XValue.Num(Dates.toSerial(LocalDateTime.now(), ctx.date1904))
                "DATE" -> {
                    var y = num(arg(a, 0)).toInt(); val m = num(arg(a, 1)).toInt(); val d = num(arg(a, 2)).toInt()
                    if (y in 0..1899) y += 1900
                    val date = LocalDate.of(y, 1, 1).plusMonths((m - 1).toLong()).plusDays((d - 1).toLong())
                    XValue.Num(Dates.toSerial(date, ctx.date1904))
                }
                "TIME" -> {
                    val h = num(arg(a, 0)); val m = num(arg(a, 1)); val s = num(arg(a, 2))
                    XValue.Num(((h * 3600 + m * 60 + s) / 86400.0).let { it - floor(it) })
                }
                "YEAR", "MONTH", "DAY", "WEEKDAY" -> {
                    val dt = Dates.fromSerial(num(arg(a, 0)), ctx.date1904) ?: fail(XValue.NUM)
                    XValue.Num(
                        when (name) {
                            "YEAR" -> dt.year
                            "MONTH" -> dt.monthValue
                            "DAY" -> dt.dayOfMonth
                            else -> {
                                val dow = dt.dayOfWeek.value % 7 + 1 // Sunday = 1
                                when (optNum(a, 1, 1.0).toInt()) {
                                    2 -> (dow + 5) % 7 + 1
                                    3 -> (dow + 5) % 7
                                    else -> dow
                                }
                            }
                        }.toDouble(),
                    )
                }
                "HOUR", "MINUTE", "SECOND" -> {
                    val x = num(arg(a, 0))
                    val secs = Math.round((x - floor(x)) * 86400).toInt()
                    XValue.Num(
                        when (name) {
                            "HOUR" -> secs / 3600 % 24
                            "MINUTE" -> secs / 60 % 60
                            else -> secs % 60
                        }.toDouble(),
                    )
                }
                "DAYS" -> XValue.Num(floor(num(arg(a, 0))) - floor(num(arg(a, 1))))
                "SUMIF", "COUNTIF", "AVERAGEIF" -> {
                    val range = blockOf(arg(a, 0)) ?: fail(XValue.VALUE)
                    val crit = Criteria(value(arg(a, 1)))
                    val sumBlock = if (name != "COUNTIF" && a.size > 2) blockOf(a[2]) ?: fail(XValue.VALUE) else range
                    var sum = 0.0; var n = 0
                    for (r in 0 until range.rows) for (c in 0 until range.cols) {
                        val v = cell(range.sheet, range.r1 + r, range.c1 + c)
                        if (crit.matches(v)) {
                            if (name == "COUNTIF") n++
                            else {
                                val sv = cell(sumBlock.sheet, sumBlock.r1 + r, sumBlock.c1 + c)
                                if (sv is XValue.Num) { sum += sv.v; n++ }
                            }
                        }
                    }
                    when (name) {
                        "COUNTIF" -> XValue.Num(n.toDouble())
                        "SUMIF" -> XValue.Num(sum)
                        else -> if (n == 0) XValue.DIV0 else XValue.Num(sum / n)
                    }
                }
                "SUMPRODUCT" -> {
                    val blocks = a.map { blockOf(it) ?: fail(XValue.VALUE) }
                    val b0 = blocks.first()
                    if (blocks.any { it.rows != b0.rows || it.cols != b0.cols }) fail(XValue.VALUE)
                    var s = 0.0
                    for (r in 0 until b0.rows) for (c in 0 until b0.cols) {
                        var p = 1.0
                        for (b in blocks) {
                            val v = cell(b.sheet, b.r1 + r, b.c1 + c)
                            if (v is XValue.Err) fail(v)
                            p *= if (v is XValue.Num) v.v else 0.0
                        }
                        s += p
                    }
                    XValue.Num(s)
                }
                "VLOOKUP", "HLOOKUP" -> {
                    val key = value(arg(a, 0))
                    val t = blockOf(arg(a, 1)) ?: fail(XValue.VALUE)
                    val idx = num(arg(a, 2)).toInt()
                    val approx = a.getOrNull(3)?.let { bool(value(it)) } ?: true
                    val vertical = name == "VLOOKUP"
                    val len = if (vertical) t.rows else t.cols
                    if (idx < 1 || idx > (if (vertical) t.cols else t.rows)) fail(XValue.REF)
                    fun keyAt(i: Int) = if (vertical) cell(t.sheet, t.r1 + i, t.c1) else cell(t.sheet, t.r1, t.c1 + i)
                    val pos = lookup(key, len, approx, ::keyAt)
                    if (pos < 0) XValue.NA
                    else if (vertical) cell(t.sheet, t.r1 + pos, t.c1 + idx - 1)
                    else cell(t.sheet, t.r1 + idx - 1, t.c1 + pos)
                }
                "MATCH" -> {
                    val key = value(arg(a, 0))
                    val t = blockOf(arg(a, 1)) ?: fail(XValue.VALUE)
                    val type = optNum(a, 2, 1.0).toInt()
                    val vertical = t.cols == 1
                    val len = if (vertical) t.rows else t.cols
                    fun keyAt(i: Int) = if (vertical) cell(t.sheet, t.r1 + i, t.c1) else cell(t.sheet, t.r1, t.c1 + i)
                    val pos = when {
                        type == 0 -> lookup(key, len, false, ::keyAt)
                        type > 0 -> lookup(key, len, true, ::keyAt)
                        else -> {
                            var best = -1
                            for (i in 0 until len) {
                                val v = keyAt(i)
                                if (v != XValue.Empty && compare(v, key) >= 0) best = i else break
                            }
                            best
                        }
                    }
                    if (pos < 0) XValue.NA else XValue.Num((pos + 1).toDouble())
                }
                "INDEX" -> {
                    val t = blockOf(arg(a, 0)) ?: fail(XValue.VALUE)
                    var r = optNum(a, 1, 0.0).toInt()
                    var c = optNum(a, 2, 0.0).toInt()
                    if (t.rows == 1 && a.size == 2) { c = r; r = 1 }
                    if (r == 0 && t.rows == 1) r = 1
                    if (c == 0 && t.cols == 1) c = 1
                    if (r < 1 || c < 1 || r > t.rows || c > t.cols) fail(XValue.REF)
                    cell(t.sheet, t.r1 + r - 1, t.c1 + c - 1)
                }
                else -> XValue.NAME
            }
        }

        fun safe(e: Expr): XValue = try { scalar(e) } catch (x: EvalError) { x.v }

        fun lookup(key: XValue, len: Int, approx: Boolean, at: (Int) -> XValue): Int {
            if (!approx) {
                val crit = if (key is XValue.Str && (key.s.contains('*') || key.s.contains('?'))) Criteria(key) else null
                for (i in 0 until len) {
                    val v = at(i)
                    if (crit != null) { if (crit.matches(v)) return i } else if (v != XValue.Empty && compare(v, key) == 0) return i
                }
                return -1
            }
            // Binary-search semantics on sorted data; a linear scan gives the same answer.
            var best = -1
            for (i in 0 until len) {
                val v = at(i)
                if (v == XValue.Empty) continue
                if (sameKind(v, key) && compare(v, key) <= 0) best = i
                else if (sameKind(v, key)) break
            }
            return best
        }

        fun sameKind(a: XValue, b: XValue) = (a is XValue.Num && b is XValue.Num) ||
            (a is XValue.Str && b is XValue.Str) || (a is XValue.Bool && b is XValue.Bool)

        fun round(x: Double, d: Int, mode: String): Double {
            val f = 10.0.pow(d)
            val y = x * f
            // Nudge to absorb binary representation error (2.675 → 2.68).
            val eps = 1e-9 * Math.signum(y)
            val r = when (mode) {
                "ROUNDUP" -> if (y >= 0) kotlin.math.ceil(y - 1e-9) else -kotlin.math.ceil(-y - 1e-9)
                "ROUNDDOWN" -> if (y >= 0) floor(y + 1e-9) else -floor(-y + 1e-9)
                else -> if (y >= 0) floor(y + 0.5 + eps) else -floor(-y + 0.5 - eps)
            }
            return r / f
        }
    }

    /** Excel ordering: numbers < text < booleans; text compares case-insensitively. */
    fun compare(a0: XValue, b0: XValue): Int {
        val a = if (a0 == XValue.Empty) (if (b0 is XValue.Str) XValue.Str("") else if (b0 is XValue.Bool) XValue.Bool(false) else XValue.Num(0.0)) else a0
        val b = if (b0 == XValue.Empty) (if (a is XValue.Str) XValue.Str("") else if (a is XValue.Bool) XValue.Bool(false) else XValue.Num(0.0)) else b0
        fun rank(v: XValue) = when (v) {
            is XValue.Num -> 0
            is XValue.Str -> 1
            is XValue.Bool -> 2
            else -> 3
        }
        val ra = rank(a); val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when (a) {
            is XValue.Num -> a.v.compareTo((b as XValue.Num).v)
            is XValue.Str -> a.s.compareTo((b as XValue.Str).s, ignoreCase = true)
            is XValue.Bool -> a.b.compareTo((b as XValue.Bool).b)
            else -> 0
        }
    }

    /** SUMIF/COUNTIF criteria: ">5", "<>x", "a*", 3, TRUE… */
    class Criteria(c: XValue) {
        private val op: String
        private val operand: XValue
        private val regex: Regex?

        init {
            if (c is XValue.Str) {
                val s = c.s
                val o = listOf("<>", ">=", "<=", "=", ">", "<").firstOrNull { s.startsWith(it) }
                op = o ?: "="
                val rest = if (o != null) s.substring(o.length) else s
                operand = parseNumber(rest)?.let { XValue.Num(it) }
                    ?: when {
                        rest.equals("TRUE", true) -> XValue.Bool(true)
                        rest.equals("FALSE", true) -> XValue.Bool(false)
                        else -> XValue.Str(rest)
                    }
                regex = if (operand is XValue.Str && (op == "=" || op == "<>") && (rest.contains('*') || rest.contains('?'))) {
                    val sb = StringBuilder()
                    var i = 0
                    while (i < rest.length) {
                        val ch = rest[i]
                        when {
                            ch == '~' && i + 1 < rest.length -> { sb.append(Regex.escape(rest[i + 1].toString())); i++ }
                            ch == '*' -> sb.append(".*")
                            ch == '?' -> sb.append('.')
                            else -> sb.append(Regex.escape(ch.toString()))
                        }
                        i++
                    }
                    Regex(sb.toString(), RegexOption.IGNORE_CASE)
                } else null
            } else {
                op = "="; operand = c; regex = null
            }
        }

        fun matches(v: XValue): Boolean {
            if (regex != null) {
                val m = v is XValue.Str && regex.matches(v.s)
                return if (op == "=") m else !m
            }
            if (op == "=" && operand is XValue.Str && operand.s.isEmpty()) return v == XValue.Empty || v == XValue.Str("")
            if (op == "<>" && operand is XValue.Str && operand.s.isEmpty()) return !(v == XValue.Empty || v == XValue.Str(""))
            val vv = if (operand is XValue.Num && v is XValue.Str) parseNumber(v.s)?.let { XValue.Num(it) } ?: v else v
            if (v == XValue.Empty) return op == "<>"
            val comparable = (vv is XValue.Num && operand is XValue.Num) || (vv is XValue.Str && operand is XValue.Str) ||
                (vv is XValue.Bool && operand is XValue.Bool)
            if (!comparable) return op == "<>"
            val c = compare(vv, operand)
            return when (op) {
                "=" -> c == 0
                "<>" -> c != 0
                ">" -> c > 0
                "<" -> c < 0
                ">=" -> c >= 0
                else -> c <= 0
            }
        }
    }

    /** Parses user/number text: "1.5", "1,5", "1e3", "15%", "-2". */
    fun parseNumber(s0: String): Double? {
        var s = s0.trim()
        if (s.isEmpty()) return null
        var pct = false
        if (s.endsWith("%")) { pct = true; s = s.dropLast(1).trim() }
        if (s.count { it == ',' } == 1 && !s.contains('.')) s = s.replace(',', '.')
        if (!s.all { it.isDigit() || it in ".-+eE" }) return null
        if (!s.any { it.isDigit() }) return null
        val d = s.toDoubleOrNull() ?: return null
        return if (pct) d / 100.0 else d
    }

    /** Number as text in formulas and General format (up to 15 significant digits). */
    fun numText(d: Double): String {
        if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
        val bd = java.math.BigDecimal.valueOf(d).round(java.math.MathContext(15)).stripTrailingZeros()
        return if (abs(d) >= 1e15 || abs(d) < 1e-9) bd.toString() else bd.toPlainString()
    }
}

object Dates {
    private val BASE = LocalDate.of(1899, 12, 30)
    private val BASE1904 = LocalDate.of(1904, 1, 1)

    fun toSerial(d: LocalDate, d1904: Boolean): Double {
        if (d1904) return ChronoUnit.DAYS.between(BASE1904, d).toDouble()
        val days = ChronoUnit.DAYS.between(BASE, d)
        return (if (days < 61) days - 1 else days).toDouble()
    }

    fun toSerial(dt: LocalDateTime, d1904: Boolean): Double =
        toSerial(dt.toLocalDate(), d1904) + dt.toLocalTime().toSecondOfDay() / 86400.0

    fun fromSerial(s: Double, d1904: Boolean): LocalDate? {
        if (s < 0 || s > 2958465) return null
        val days = floor(s).toLong()
        if (d1904) return BASE1904.plusDays(days)
        return if (days < 61) BASE.plusDays(days + 1) else BASE.plusDays(days)
    }

    fun fromSerialTime(s: Double, d1904: Boolean): LocalDateTime? {
        val d = fromSerial(s, d1904) ?: return null
        val secs = Math.round((s - floor(s)) * 86400).coerceIn(0, 86399)
        return d.atStartOfDay().plusSeconds(secs)
    }
}
