// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ooxml

import io.github.Prestgg.naroffice.xml.XDocument
import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.Xml
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Relationship(val id: String, val type: String, val target: String, val external: Boolean)

/**
 * An Open Packaging Conventions container (the ZIP inside .docx, .xlsx and .pptx).
 *
 * Parts are kept as raw bytes and parsed lazily. Parsed parts that were changed are written
 * back from their XML tree; every other part is copied byte for byte.
 */
class OpcPackage private constructor(
    private val order: MutableList<String>,
    private val raw: MutableMap<String, ByteArray>,
    /** For packages opened from a file: parts are read from the ZIP only when needed. */
    private val zip: ZipFile? = null,
    private val zipEntries: MutableMap<String, ZipEntry> = HashMap(),
) : java.io.Closeable {
    private val parsed = HashMap<String, XDocument>()
    private val dirty = HashSet<String>()
    private val relsCache = HashMap<String, List<Relationship>>()

    val partNames: List<String> get() = order.toList()

    private fun has(p: String) = raw.containsKey(p) || parsed.containsKey(p) || zipEntries.containsKey(p)

    fun exists(part: String): Boolean = has(norm(part))

    /** Size of a part in bytes, without reading it (for unchanged parts of a file-backed package). */
    fun size(part: String): Long {
        val p = norm(part)
        raw[p]?.let { return it.size.toLong() }
        return zipEntries[p]?.size ?: -1L
    }

    fun bytes(part: String): ByteArray? {
        val p = norm(part)
        if (p in dirty) parsed[p]?.let { return it.toBytes() }
        raw[p]?.let { return it }
        val e = zipEntries[p] ?: return null
        // Not cached: large media stay on disk and are decoded on demand.
        return zip!!.getInputStream(e).use { it.readBytes() }
    }

    fun xml(part: String): XDocument? {
        val p = norm(part)
        parsed[p]?.let { return it }
        val b = raw[p] ?: zipEntries[p]?.let { e -> zip!!.getInputStream(e).use { it.readBytes() } } ?: return null
        val doc = Xml.parse(b)
        parsed[p] = doc
        return doc
    }

    /** Marks a parsed part as changed so that it is serialized on save. */
    fun markDirty(part: String) {
        dirty.add(norm(part))
    }

    fun putXml(part: String, doc: XDocument, contentType: String? = null) {
        val p = norm(part)
        if (!has(p)) order.add(p)
        zipEntries.remove(p)
        parsed[p] = doc
        dirty.add(p)
        raw.remove(p)
        relsCache.remove(p)
        if (contentType != null) setContentType(p, contentType)
    }

    fun putBytes(part: String, data: ByteArray, contentType: String? = null) {
        val p = norm(part)
        if (!has(p)) order.add(p)
        zipEntries.remove(p)
        raw[p] = data
        parsed.remove(p)
        dirty.remove(p)
        relsCache.remove(p)
        if (contentType != null) setContentType(p, contentType)
    }

    fun remove(part: String) {
        val p = norm(part)
        raw.remove(p); parsed.remove(p); dirty.remove(p); order.remove(p); zipEntries.remove(p)
        relsCache.remove(p)
        val ct = xml("[Content_Types].xml") ?: return
        ct.root.children.removeAll { it is XElement && it.localName == "Override" && it["PartName"] == "/$p" }
        markDirty("[Content_Types].xml")
    }

    private fun setContentType(part: String, contentType: String) {
        val ct = xml("[Content_Types].xml") ?: return
        val partName = "/$part"
        val existing = ct.root.elements.firstOrNull { it.localName == "Override" && it["PartName"] == partName }
        if (existing != null) {
            existing["ContentType"] = contentType
        } else {
            val ext = part.substringAfterLast('.', "").lowercase()
            val def = ct.root.elements.firstOrNull {
                it.localName == "Default" && it["Extension"]?.lowercase() == ext
            }
            if (def?.get("ContentType") == contentType) return
            if (def == null && ext in MEDIA_TYPES && MEDIA_TYPES[ext] == contentType) {
                val d = XElement("Default")
                d["Extension"] = ext
                d["ContentType"] = contentType
                ct.root.children.add(0, d)
            } else {
                val o = XElement("Override")
                o["PartName"] = partName
                o["ContentType"] = contentType
                ct.root.children.add(o)
            }
        }
        markDirty("[Content_Types].xml")
    }

    fun contentType(part: String): String? {
        val ct = xml("[Content_Types].xml") ?: return null
        val partName = "/" + norm(part)
        ct.root.elements.firstOrNull { it.localName == "Override" && it["PartName"].equals(partName, true) }
            ?.let { return it["ContentType"] }
        val ext = part.substringAfterLast('.', "").lowercase()
        return ct.root.elements.firstOrNull { it.localName == "Default" && it["Extension"]?.lowercase() == ext }
            ?.get("ContentType")
    }

    // ---- relationships ----

    fun relsPartOf(part: String): String {
        val p = norm(part)
        val dir = p.substringBeforeLast('/', "")
        val file = p.substringAfterLast('/')
        return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    /** Relationships of [part]; pass "" for the package-level relationships. */
    fun rels(part: String): List<Relationship> {
        val relsPart = relsPartOf(part)
        relsCache[relsPart]?.let { return it }
        val doc = xml(relsPart)
        val list = doc?.root?.elements?.filter { it.localName == "Relationship" }?.map {
            Relationship(
                it["Id"] ?: "",
                it["Type"] ?: "",
                it["Target"] ?: "",
                it["TargetMode"] == "External",
            )
        }?.toList() ?: emptyList()
        relsCache[relsPart] = list
        return list
    }

    fun rel(part: String, id: String): Relationship? = rels(part).firstOrNull { it.id == id }

    /** Resolves a relationship target to a part name, relative to the source part. */
    fun resolve(sourcePart: String, target: String): String {
        if (target.startsWith("/")) return norm(target)
        val base = norm(sourcePart).substringBeforeLast('/', "")
        val segs = ArrayList<String>()
        if (base.isNotEmpty()) segs.addAll(base.split('/'))
        for (s in target.split('/')) {
            when (s) {
                "", "." -> {}
                ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
                else -> segs.add(s)
            }
        }
        return segs.joinToString("/")
    }

    fun target(sourcePart: String, relId: String): String? {
        val r = rel(sourcePart, relId) ?: return null
        if (r.external) return null
        return resolve(sourcePart, r.target)
    }

    fun targetsOfType(sourcePart: String, typeSuffix: String): List<String> =
        rels(sourcePart).filter { !it.external && it.type.endsWith(typeSuffix) }
            .map { resolve(sourcePart, it.target) }

    /** Adds a relationship from [sourcePart] and returns its id ([id] or a new one). */
    fun addRel(sourcePart: String, type: String, target: String, id: String? = null): String {
        val relsPart = relsPartOf(sourcePart)
        val doc = xml(relsPart) ?: XDocument(
            ArrayList(),
            XElement("Relationships").also {
                it["xmlns"] = "http://schemas.openxmlformats.org/package/2006/relationships"
            },
        ).also { putXml(relsPart, it, "application/vnd.openxmlformats-package.relationships+xml") }
        val ids = doc.root.elements.mapNotNull { it["Id"] }.toSet()
        var k = ids.size + 1
        while ("rId$k" in ids) k++
        val newId = id ?: "rId$k"
        val r = XElement("Relationship")
        r["Id"] = newId
        r["Type"] = type
        r["Target"] = target
        doc.root.children.add(r)
        markDirty(relsPart)
        relsCache.remove(relsPart)
        return newId
    }

    fun removeRel(sourcePart: String, id: String) {
        val relsPart = relsPartOf(sourcePart)
        val doc = xml(relsPart) ?: return
        doc.root.children.removeAll { it is XElement && it["Id"] == id }
        markDirty(relsPart)
        relsCache.remove(relsPart)
    }

    /** The main part (word/document.xml, xl/workbook.xml, ppt/presentation.xml). */
    fun mainPart(): String? =
        rels("").firstOrNull { it.type.endsWith("/officeDocument") }?.let { resolve("", it.target) }

    fun write(out: OutputStream) {
        val zos = ZipOutputStream(out)
        // [Content_Types].xml must come first for some readers.
        val names = order.sortedBy { if (it == "[Content_Types].xml") 0 else 1 }
        for (name in names) {
            val source = if (name !in dirty && name !in raw) zipEntries[name] else null
            if (source != null) {
                // Unchanged part of a file-backed package: stream it across without buffering it all.
                val entry = ZipEntry(name)
                if (isPrecompressed(name) && source.crc != -1L && source.size >= 0) {
                    entry.method = ZipEntry.STORED
                    entry.size = source.size
                    entry.compressedSize = source.size
                    entry.crc = source.crc
                }
                zos.putNextEntry(entry)
                zip!!.getInputStream(source).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
                continue
            }
            val data = bytes(name) ?: continue
            val entry = ZipEntry(name)
            if (isPrecompressed(name)) {
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = CRC32().also { it.update(data) }.value
            }
            zos.putNextEntry(entry)
            zos.write(data)
            zos.closeEntry()
        }
        zos.finish()
        zos.flush()
    }

    fun toBytes(): ByteArray = ByteArrayOutputStream().also { write(it) }.toByteArray()

    override fun close() {
        runCatching { zip?.close() }
    }

    private fun isPrecompressed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "mp4", "mp3", "m4a", "zip")
    }

    companion object {
        val MEDIA_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
            "webp" to "image/webp",
        )

        fun norm(part: String): String = part.trimStart('/')

        fun read(input: InputStream): OpcPackage {
            val order = ArrayList<String>()
            val raw = HashMap<String, ByteArray>()
            ZipInputStream(input).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = norm(e.name)
                    if (name.contains("..")) continue
                    raw[name] = zis.readBytes()
                    order.add(name)
                }
            }
            if (!raw.containsKey("[Content_Types].xml")) throw OfficeFormatException("Not an Office Open XML file")
            return OpcPackage(order, raw)
        }

        fun read(bytes: ByteArray): OpcPackage = read(bytes.inputStream())

        /** Opens a package from a file, reading parts lazily. The file must stay until [close]. */
        fun open(file: java.io.File): OpcPackage {
            val zf = try {
                ZipFile(file)
            } catch (e: java.util.zip.ZipException) {
                throw OfficeFormatException("Not an Office Open XML file")
            }
            val order = ArrayList<String>()
            val entries = HashMap<String, ZipEntry>()
            for (e in zf.entries()) {
                if (e.isDirectory) continue
                val name = norm(e.name)
                if (name.contains("..") || name in entries) continue
                entries[name] = e
                order.add(name)
            }
            if (!entries.containsKey("[Content_Types].xml")) {
                zf.close()
                throw OfficeFormatException("Not an Office Open XML file")
            }
            return OpcPackage(order, HashMap(), zf, entries)
        }

        fun fromParts(parts: Map<String, String>): OpcPackage {
            val order = parts.keys.toMutableList()
            val raw = parts.mapValues { it.value.toByteArray(Charsets.UTF_8) }.toMutableMap()
            return OpcPackage(order, raw)
        }
    }
}

class OfficeFormatException(message: String) : Exception(message)
