// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipInputStream

enum class DocKind(val ext: String, val mime: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    SHEET("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    SLIDES("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    CSV("csv", "text/csv"),
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

    fun read(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open $uri")

    /** Detects the document type from the content, falling back to the file name. */
    fun detect(bytes: ByteArray, name: String): DocKind {
        val lower = name.lowercase()
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            runCatching {
                ZipInputStream(bytes.inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        when {
                            e.name.startsWith("word/") -> return DocKind.WORD
                            e.name.startsWith("xl/") -> return DocKind.SHEET
                            e.name.startsWith("ppt/") -> return DocKind.SLIDES
                        }
                    }
                }
            }
        }
        // Legacy binary Office files (OLE compound documents).
        if (bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
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

    fun write(ctx: Context, uri: Uri, data: ByteArray) {
        val out = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(uri, "w")
            ?: error("Cannot write $uri")
        out.use { it.write(data) }
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
