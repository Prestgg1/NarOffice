// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.Files
import io.github.Prestgg.naroffice.core.Recents
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.core.UnsupportedFormatException
import io.github.Prestgg.naroffice.ui.EditorHost
import io.github.Prestgg.naroffice.ui.HomeScreen
import io.github.Prestgg.naroffice.ui.NarTheme
import io.github.Prestgg.naroffice.ui.Openers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    /** A file handed to us by another app, waiting to be opened. */
    private var pendingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            pendingUri = intentUri(intent)
            // Local copies from an earlier run are no longer needed.
            val started = System.currentTimeMillis()
            Thread { Files.cleanWorkDir(this, started - 1000) }.start()
        }
        setContent {
            NarTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentUri(intent)?.let { pendingUri = it }
    }

    private fun intentUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }

    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val recents = remember { Recents(ctx) }
        var recentTick by remember { mutableStateOf(0) }
        var session by remember { mutableStateOf<Session?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        // Something to run once the current document was saved (or discarded).
        var confirmClose by remember { mutableStateOf<(() -> Unit)?>(null) }
        var afterSaveAs by remember { mutableStateOf<(() -> Unit)?>(null) }
        var saveTick by remember { mutableStateOf(0) }

        val errLegacy = stringResource(R.string.error_legacy)
        val errFormat = stringResource(R.string.error_format)
        val errOpen = stringResource(R.string.error_open)
        val savedMsg = stringResource(R.string.saved)
        val errSave = stringResource(R.string.error_save)

        fun setSession(s: Session?) {
            val old = session
            session = s
            if (old != null && old !== s) {
                // Close after the editor has left the screen; image loads may still be finishing.
                scope.launch {
                    kotlinx.coroutines.delay(5000)
                    withContext(Dispatchers.IO) { old.close() }
                }
            }
        }

        fun open(uri: Uri, persist: Boolean) {
            loading = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (persist) Files.takePermission(ctx, uri)
                        val name = Files.displayName(ctx, uri)
                        val file = Files.copyToLocal(ctx, uri)
                        try {
                            val kind = Files.detect(file, name)
                            Session(uri, name, Openers.open(kind, file), file)
                        } catch (t: Throwable) {
                            file.delete()
                            throw t
                        }
                    }
                }
                loading = false
                result.onSuccess {
                    setSession(it)
                    recents.add(uri, it.name, it.kind)
                    recentTick++
                }.onFailure { e ->
                    error = when (e) {
                        is UnsupportedFormatException -> if (e.legacy) errLegacy else errFormat
                        is io.github.Prestgg.naroffice.ui.pdf.PdfPasswordException -> ctx.getString(R.string.pdf_password)
                        is SecurityException, is java.io.FileNotFoundException -> ctx.getString(R.string.file_missing)
                        else -> errOpen.format(e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }

        val saveAsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            val s = session ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                afterSaveAs = null; return@rememberLauncherForActivityResult
            }
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        Files.takePermission(ctx, uri)
                        Files.write(ctx, uri) { out -> s.doc.save(out) }
                        s.uri = uri
                        s.name = Files.displayName(ctx, uri)
                    }
                }
                r.onSuccess {
                    s.doc.markSaved()
                    recents.add(uri, s.name, s.kind); recentTick++; saveTick++
                    Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show()
                    afterSaveAs?.invoke()
                }.onFailure { Toast.makeText(ctx, errSave.format(it.message), Toast.LENGTH_LONG).show() }
                afterSaveAs = null
            }
        }

        fun saveAs(then: (() -> Unit)? = null) {
            val s = session ?: return
            s.flush()
            afterSaveAs = then
            val base = s.name.substringBeforeLast('.')
            val ext = if (s.kind == DocKind.CSV) "csv" else s.kind.ext
            saveAsLauncher.launch("$base.$ext")
        }

        fun save(then: (() -> Unit)? = null) {
            val s = session ?: return
            val uri = s.uri
            s.flush()
            if (uri == null) {
                saveAs(then); return
            }
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Files.write(ctx, uri) { out -> s.doc.save(out) } } }
                r.onSuccess {
                    s.doc.markSaved(); saveTick++
                    Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show()
                    then?.invoke()
                }.onFailure {
                    // Files from other apps are often read-only: save a copy instead.
                    Toast.makeText(ctx, ctx.getString(R.string.read_only_saveas), Toast.LENGTH_LONG).show()
                    saveAs(then)
                }
            }
        }

        fun share() {
            val s = session ?: return
            s.flush()
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val ext = if (s.kind == DocKind.CSV) "csv" else s.kind.ext
                    val name = s.name.substringBeforeLast('.').ifBlank { "document" } + "." + ext
                    File(dir, name).also { f -> f.outputStream().buffered().use { s.doc.save(it) } }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = s.kind.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, null))
            }
        }

        fun close(then: () -> Unit = {}) {
            val s = session
            s?.flush()
            if (s != null && s.doc.isModified) confirmClose = then else {
                setSession(null); then()
            }
        }

        val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) open(uri, persist = true)
        }

        LaunchedEffect(pendingUri) {
            val u = pendingUri ?: return@LaunchedEffect
            pendingUri = null
            close { open(u, persist = false) }
        }

        val current = session
        if (current == null) {
            HomeScreen(
                recents = remember(recentTick) { recents.list() },
                onOpen = {
                    openLauncher.launch(arrayOf(
                        DocKind.WORD.mime, DocKind.SHEET.mime, DocKind.SLIDES.mime,
                        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                        "application/vnd.ms-word.document.macroEnabled.12",
                        "application/vnd.ms-excel.sheet.macroEnabled.12",
                        "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
                        "text/csv", "text/comma-separated-values", "application/pdf", "application/octet-stream",
                        "application/zip",
                    ))
                },
                onOpenRecent = { open(Uri.parse(it.uri), persist = false) },
                onNew = { kind ->
                    val name = ctx.getString(
                        when (kind) {
                            DocKind.WORD, DocKind.PDF -> R.string.untitled_doc
                            DocKind.SHEET, DocKind.CSV -> R.string.untitled_sheet
                            DocKind.SLIDES -> R.string.untitled_slides
                        },
                    ) + "." + kind.ext
                    runCatching { Openers.blank(kind) }
                        .onSuccess { setSession(Session(null, name, it)) }
                        .onFailure { error = errFormat }
                },
                onRemoveRecent = { recents.remove(it.uri); recentTick++ },
                onPinRecent = { recents.togglePin(it.uri); recentTick++ },
                onClearRecent = { recents.clear(); recentTick++ },
            )
        } else {
            BackHandler { close() }
            EditorHost(
                session = current,
                saveTick = saveTick,
                onBack = { close() },
                onSave = { save() },
                onSaveAs = { saveAs() },
                onShare = { share() },
            )
        }

        if (loading) {
            // Block touches on the screen behind while a file opens.
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000))
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }

        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.ok)) } },
                text = { Text(msg) },
            )
        }

        confirmClose?.let { then ->
            AlertDialog(
                onDismissRequest = { confirmClose = null },
                title = { Text(stringResource(R.string.unsaved_title)) },
                text = { Text(stringResource(R.string.unsaved_text, current?.name ?: "")) },
                confirmButton = {
                    TextButton({
                        confirmClose = null
                        save { setSession(null); then() }
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton({
                        confirmClose = null
                        setSession(null)
                        then()
                    }) { Text(stringResource(R.string.discard)) }
                },
            )
        }
    }
}
