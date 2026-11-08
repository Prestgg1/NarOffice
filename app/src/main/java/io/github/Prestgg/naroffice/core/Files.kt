// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

enum class DocKind(val ext: String, val mime: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    SHEET("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    SLIDES("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    CSV("csv", "text/csv"),
    PDF("pdf", "application/pdf"),
}

class UnsupportedFormatException(val legacy: Boolean) : Exception()

object Files {

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Folder for local copies of opened files. */
    private fun workDir(ctx: Context): File {
        for (base in listOfNotNull(ctx.cacheDir, ctx.externalCacheDir, ctx.filesDir)) {
            val d = File(base, "open")
            if ((d.isDirectory || d.mkdirs()) && d.canWrite()) return d
        }
        error("No writable cache directory")
    }

    /** Removes local copies left from earlier runs or closed documents. */
    fun cleanWorkDir(ctx: Context, olderThan: Long) {
        runCatching { workDir(ctx).listFiles()?.forEach { if (it.lastModified() < olderThan) it.delete() } }
    }

    /**
     * Copies a document to local storage in a streaming fashion, so that large files (with videos
     * or many pictures) never have to fit in memory at once.
     */
    fun copyToLocal(ctx: Context, uri: Uri): File {
        val f = File(workDir(ctx), "doc-${System.nanoTime()}")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        return f
    }

    /** Detects the document type from the content, falling back to the file name. */
    fun detect(file: File, name: String): DocKind {
        val lower = name.lowercase()
        val head = ByteArray(1024)
        val n = file.inputStream().use { input ->
            var total = 0
            while (total < head.size) {
                val r = input.read(head, total, head.size - total)
                if (r < 0) break
                total += r
            }
            total
        }
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            runCatching {
                ZipFile(file).use { z ->
                    for (e in z.entries()) {
                        when {
                            e.name.startsWith("word/") -> return DocKind.WORD
                            e.name.startsWith("xl/") -> return DocKind.SHEET
                            e.name.startsWith("ppt/") -> return DocKind.SLIDES
                        }
                    }
                }
            }
        }
        val bytes = head
        // The PDF header may follow some leading bytes (the spec allows it within the first 1 KB).
        if (String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1).contains("%PDF-") || lower.endsWith(".pdf")) {
            return DocKind.PDF
        }
        // Legacy binary Office files (OLE compound documents).
        if (n >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
            throw UnsupportedFormatException(legacy = true)
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) return DocKind.CSV
        throw UnsupportedFormatException(legacy = false)
    }

    fun canWrite(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } ||
            ctx.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Writes a document by streaming it into [uri]. */
    fun write(ctx: Context, uri: Uri, writer: (java.io.OutputStream) -> Unit) {
        val out = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(uri, "w")
            ?: error("Cannot write $uri")
        out.buffered(256 * 1024).use { writer(it) }
    }

    fun takePermission(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}

data class RecentFile(val uri: String, val name: String, val kind: DocKind, val time: Long, val pinned: Boolean = false)

/** Recently opened files, stored in shared preferences. */
class Recents(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(): List<RecentFile> {
        val arr = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                RecentFile(o.getString("uri"), o.getString("name"), DocKind.valueOf(o.getString("kind")),
                    o.getLong("time"), o.optBoolean("pinned"))
            }.getOrNull()
        }.sortedWith(compareByDescending<RecentFile> { it.pinned }.thenByDescending { it.time })
    }

    private fun store(items: List<RecentFile>) {
        val arr = JSONArray()
        items.take(60).forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("kind", it.kind.name)
                .put("time", it.time).put("pinned", it.pinned))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun add(uri: Uri, name: String, kind: DocKind) {
        val old = list()
        val pinned = old.firstOrNull { it.uri == uri.toString() }?.pinned ?: false
        store(listOf(RecentFile(uri.toString(), name, kind, System.currentTimeMillis(), pinned)) +
            old.filter { it.uri != uri.toString() })
    }

    fun remove(uri: String) = store(list().filter { it.uri != uri })

    fun togglePin(uri: String) = store(list().map { if (it.uri == uri) it.copy(pinned = !it.pinned) else it })

    fun clear() = store(list().filter { it.pinned })
}
