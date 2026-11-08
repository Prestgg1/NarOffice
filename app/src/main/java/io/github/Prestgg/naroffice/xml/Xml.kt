// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.xml

/**
 * A small, fast XML tree made for lossless round-trips of Office files.
 *
 * Names keep their prefix exactly as written ("w:p"), attributes keep their order, and every
 * node we do not understand is written back untouched. That is what lets us edit one paragraph
 * of a document without disturbing anything else in it.
 */
sealed class XNode

class XText(var text: String, val cdata: Boolean = false) : XNode()

/** Comments, processing instructions and doctypes: kept verbatim. */
class XRaw(val raw: String) : XNode()

class XElement(
    var name: String,
    val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: MutableList<XNode> = ArrayList(),
) : XNode() {

    val localName: String get() = name.substringAfter(':')

    operator fun get(attr: String): String? = attrs[attr]

    operator fun set(attr: String, value: String?) {
        if (value == null) attrs.remove(attr) else attrs[attr] = value
    }

    /** Attribute by local name, ignoring its prefix. */
    fun attrLocal(local: String): String? {
        attrs[local]?.let { return it }
        for ((k, v) in attrs) if (k.substringAfter(':') == local) return v
        return null
    }

    val elements: Sequence<XElement> get() = children.asSequence().filterIsInstance<XElement>()

    fun child(name: String): XElement? {
        for (c in children) if (c is XElement && c.name == name) return c
        return null
    }

    fun childLocal(local: String): XElement? {
        for (c in children) if (c is XElement && c.localName == local) return c
        return null
    }

    fun children(name: String): List<XElement> =
        children.filterIsInstance<XElement>().filter { it.name == name }

    fun path(vararg names: String): XElement? {
        var e: XElement? = this
        for (n in names) e = e?.child(n) ?: return null
        return e
    }

    fun getOrCreate(name: String, atStart: Boolean = false): XElement {
        child(name)?.let { return it }
        val e = XElement(name)
        if (atStart) children.add(0, e) else children.add(e)
        return e
    }

    fun removeChild(name: String) {
        children.removeAll { it is XElement && it.name == name }
    }

    /** All descendant elements, depth first. */
    fun descendants(): Sequence<XElement> = sequence {
        val stack = ArrayDeque<XElement>()
        stack.addLast(this@XElement)
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            if (e !== this@XElement) yield(e)
            for (i in e.children.indices.reversed()) {
                val c = e.children[i]
                if (c is XElement) stack.addLast(c)
            }
        }
    }

    /** Concatenated text of all descendants. */
    val textContent: String
        get() {
            val sb = StringBuilder()
            fun walk(n: XNode) {
                when (n) {
                    is XText -> sb.append(n.text)
                    is XElement -> n.children.forEach(::walk)
                    is XRaw -> {}
                }
            }
            walk(this)
            return sb.toString()
        }

    fun setText(text: String) {
        children.clear()
        children.add(XText(text))
    }

    fun deepCopy(): XElement {
        val copy = XElement(name, LinkedHashMap(attrs))
        for (c in children) {
            copy.children.add(
                when (c) {
                    is XElement -> c.deepCopy()
                    is XText -> XText(c.text, c.cdata)
                    is XRaw -> c
                }
            )
        }
        return copy
    }

    override fun toString(): String = Xml.write(this, declaration = false)
}

class XDocument(val prolog: MutableList<XNode>, var root: XElement) {
    fun toBytes(): ByteArray = Xml.writeDocument(this).toByteArray(Charsets.UTF_8)
}

class XmlException(message: String) : Exception(message)

object Xml {

    fun parse(bytes: ByteArray): XDocument = parse(decode(bytes))

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    fun parse(s: String): XDocument = Parser(s).parseDocument()

    fun parseElement(s: String): XElement = parse(s).root

    fun writeDocument(doc: XDocument): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n")
        for (n in doc.prolog) if (n is XRaw && !n.raw.startsWith("<?xml ")) {
            sb.append(n.raw)
        }
        writeNode(doc.root, sb)
        return sb.toString()
    }

    fun write(e: XElement, declaration: Boolean = false): String {
        val sb = StringBuilder()
        if (declaration) sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n")
        writeNode(e, sb)
        return sb.toString()
    }

    private fun writeNode(n: XNode, sb: StringBuilder) {
        when (n) {
            is XText -> if (n.cdata) sb.append("<![CDATA[").append(n.text).append("]]>")
            else escape(n.text, sb, attr = false)
            is XRaw -> sb.append(n.raw)
            is XElement -> {
                sb.append('<').append(n.name)
                for ((k, v) in n.attrs) {
                    sb.append(' ').append(k).append("=\"")
                    escape(v, sb, attr = true)
                    sb.append('"')
                }
                if (n.children.isEmpty()) {
                    sb.append("/>")
                } else {
                    sb.append('>')
                    for (c in n.children) writeNode(c, sb)
                    sb.append("</").append(n.name).append('>')
                }
            }
        }
    }

    fun escape(s: String, sb: StringBuilder, attr: Boolean) {
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> if (attr) sb.append("&quot;") else sb.append(ch)
                '\n' -> if (attr) sb.append("&#10;") else sb.append(ch)
                '\r' -> sb.append("&#13;")
                '\t' -> if (attr) sb.append("&#9;") else sb.append(ch)
                else -> if (ch.code < 0x20) {
                    // Control characters are not allowed in XML 1.0; drop them.
                } else sb.append(ch)
            }
        }
    }

    private class Parser(val s: String) {
        var i = 0
        val n = s.length

        fun parseDocument(): XDocument {
            val prolog = ArrayList<XNode>()
            while (true) {
                skipWs()
                if (i >= n) throw XmlException("No root element")
                if (s.startsWith("<?", i)) {
                    val end = s.indexOf("?>", i).takeIf { it >= 0 } ?: throw XmlException("Bad PI")
                    prolog.add(XRaw(s.substring(i, end + 2)))
                    i = end + 2
                } else if (s.startsWith("<!--", i)) {
                    val end = s.indexOf("-->", i).takeIf { it >= 0 } ?: throw XmlException("Bad comment")
                    prolog.add(XRaw(s.substring(i, end + 3)))
                    i = end + 3
                } else if (s.startsWith("<!DOCTYPE", i)) {
                    var depth = 0
                    val start = i
                    while (i < n) {
                        val c = s[i]
                        if (c == '[') depth++ else if (c == ']') depth--
                        else if (c == '>' && depth == 0) break
                        i++
                    }
                    i++
                    prolog.add(XRaw(s.substring(start, i)))
                } else if (s[i] == '<') {
                    break
                } else {
                    throw XmlException("Unexpected content before root at $i")
                }
            }
            val root = parseElement()
            return XDocument(prolog, root)
        }

        private fun skipWs() {
            while (i < n && s[i].isWhitespace()) i++
        }

        private fun readName(): String {
            val start = i
            while (i < n) {
                val c = s[i]
                if (c.isWhitespace() || c == '>' || c == '/' || c == '=') break
                i++
            }
            if (start == i) throw XmlException("Expected name at $i")
            return s.substring(start, i)
        }

        fun parseElement(): XElement {
            // Iterative, so that deeply nested documents cannot overflow the stack.
            val stack = ArrayList<XElement>()
            var root: XElement? = null
            while (true) {
                if (i >= n) throw XmlException("Unexpected end of document")
                val c = s[i]
                if (c == '<') {
                    if (s.startsWith("</", i)) {
                        i += 2
                        val name = readName()
                        skipWs()
                        if (i >= n || s[i] != '>') throw XmlException("Bad end tag at $i")
                        i++
                        val top = stack.removeAt(stack.size - 1)
                        if (top.name != name) throw XmlException("Mismatched </$name>, expected </${top.name}>")
                        if (stack.isEmpty()) return top
                    } else if (s.startsWith("<!--", i)) {
                        val end = s.indexOf("-->", i).takeIf { it >= 0 } ?: throw XmlException("Bad comment")
                        stack.lastOrNull()?.children?.add(XRaw(s.substring(i, end + 3)))
                        i = end + 3
                    } else if (s.startsWith("<![CDATA[", i)) {
                        val end = s.indexOf("]]>", i).takeIf { it >= 0 } ?: throw XmlException("Bad CDATA")
                        stack.lastOrNull()?.children?.add(XText(s.substring(i + 9, end), cdata = true))
                        i = end + 3
                    } else if (s.startsWith("<?", i)) {
                        val end = s.indexOf("?>", i).takeIf { it >= 0 } ?: throw XmlException("Bad PI")
                        stack.lastOrNull()?.children?.add(XRaw(s.substring(i, end + 2)))
                        i = end + 2
                    } else {
                        i++
                        val e = XElement(readName())
                        var selfClosing = false
                        while (true) {
                            skipWs()
                            if (i >= n) throw XmlException("Unterminated tag")
                            val ch = s[i]
                            if (ch == '/') {
                                if (i + 1 < n && s[i + 1] == '>') {
                                    selfClosing = true; i += 2; break
                                }
                                throw XmlException("Bad tag at $i")
                            }
                            if (ch == '>') {
                                i++; break
                            }
                            val an = readName()
                            skipWs()
                            if (i >= n || s[i] != '=') throw XmlException("Expected = at $i")
                            i++
                            skipWs()
                            val q = s[i]
                            if (q != '"' && q != '\'') throw XmlException("Expected quote at $i")
                            val end = s.indexOf(q, i + 1)
                            if (end < 0) throw XmlException("Unterminated attribute")
                            e.attrs[an] = unescape(s, i + 1, end, attr = true)
                            i = end + 1
                        }
                        if (stack.isEmpty()) {
                            if (root != null) throw XmlException("Multiple roots")
                            root = e
                        } else {
                            stack[stack.size - 1].children.add(e)
                        }
                        if (selfClosing) {
                            if (stack.isEmpty()) return e
                        } else {
                            stack.add(e)
                        }
                    }
                } else {
                    var end = s.indexOf('<', i)
                    if (end < 0) end = n
                    if (stack.isNotEmpty()) stack[stack.size - 1].children.add(XText(unescape(s, i, end, attr = false)))
                    i = end
                }
            }
        }

        private fun unescape(s: String, from: Int, to: Int, attr: Boolean): String {
            val amp = s.indexOf('&', from)
            if (amp < 0 || amp >= to) {
                val raw = s.substring(from, to)
                return if (attr && raw.any { it == '\n' || it == '\t' || it == '\r' })
                    raw.replace('\n', ' ').replace('\t', ' ').replace("\r", "") else raw.replace("\r\n", "\n")
            }
            val sb = StringBuilder(to - from)
            var k = from
            while (k < to) {
                val c = s[k]
                if (c == '&') {
                    val semi = s.indexOf(';', k)
                    if (semi < 0 || semi > to) {
                        sb.append(c); k++; continue
                    }
                    val ent = s.substring(k + 1, semi)
                    when {
                        ent == "amp" -> sb.append('&')
                        ent == "lt" -> sb.append('<')
                        ent == "gt" -> sb.append('>')
                        ent == "quot" -> sb.append('"')
                        ent == "apos" -> sb.append('\'')
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { sb.appendCodePoint(it) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { sb.appendCodePoint(it) }
                        else -> sb.append('&').append(ent).append(';')
                    }
                    k = semi + 1
                } else if (c == '\r') {
                    k++
                } else {
                    sb.append(if (attr && (c == '\n' || c == '\t')) ' ' else c)
                    k++
                }
            }
            return sb.toString()
        }
    }
}

private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp in 0..0xFFFF) append(cp.toChar()) else append(String(Character.toChars(cp)))
    return this
}
