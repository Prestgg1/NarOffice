// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.pdf

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.OfficeDoc
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.ui.common.EditorTopBar
import io.github.Prestgg.naroffice.ui.common.MenuAction
import io.github.Prestgg.naroffice.ui.common.SearchBar
import io.github.Prestgg.naroffice.ui.common.TextInputDialog
import io.github.Prestgg.naroffice.ui.export.Export
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

class PdfPasswordException : Exception()

/** A read-only PDF backed by the local copy of the file. */
class PdfDoc(val file: File) : OfficeDoc() {
    override val kind = DocKind.PDF
    override val isModified = false

    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: SecurityException) {
        fd.close()
        throw PdfPasswordException()
    }

    /** PdfRenderer allows one open page at a time, so all access goes through one thread. */
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val pageCount: Int = renderer.pageCount

    /** Page sizes in points, read once up front. */
    val sizes: List<Pair<Int, Int>> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { it.width to it.height }
    }

    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(page: Int, widthPx: Int): Bitmap? = cache.get("$page:$widthPx")

    /** Renders [page] at [widthPx] wide. Call on [dispatcher]. */
    fun render(page: Int, widthPx: Int): Bitmap {
        cache.get("$page:$widthPx")?.let { return it }
        val (w, h) = sizes[page]
        val bw = widthPx.coerceIn(64, 4096)
        val bh = (bw.toLong() * h / w).toInt().coerceIn(64, 8192)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AColor.WHITE)
        renderer.openPage(page).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        cache.put("$page:$widthPx", bmp)
        return bmp
    }

    /** Pages whose text contains [query]; needs Android 15 (API 35). Call on [dispatcher]. */
    fun search(query: String): List<Int>? {
        if (Build.VERSION.SDK_INT < 35) return null
        val out = ArrayList<Int>()
        for (i in 0 until pageCount) {
            renderer.openPage(i).use { p -> if (p.searchText(query).isNotEmpty()) out.add(i) }
        }
        return out
    }

    override fun save(out: OutputStream) {
        file.inputStream().use { it.copyTo(out) }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
        dispatcher.close()
        cache.evictAll()
    }
}

@Composable
fun PdfViewer(
    session: Session,
    doc: PdfDoc,
    onBack: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var goToDialog by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>?>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }
    val current by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex } }
    val noSearch = stringResource(R.string.pdf_search_unsupported)

    fun goTo(page: Int) {
        scope.launch { listState.scrollToItem(page.coerceIn(0, doc.pageCount - 1)) }
    }

    fun runSearch(q: String) {
        query = q
        matchIndex = 0
        if (q.length < 2) {
            matches = emptyList(); return
        }
        scope.launch {
            val r = withContext(doc.dispatcher) { runCatching { doc.search(q) }.getOrNull() }
            if (query != q) return@launch
            matches = r
            r?.firstOrNull()?.let { goTo(it) }
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                val m = matches
                SearchBar(
                    query, { runSearch(it) },
                    status = when {
                        m == null -> noSearch
                        query.length < 2 -> ""
                        m.isEmpty() -> stringResource(R.string.no_matches)
                        else -> stringResource(R.string.matches, matchIndex + 1, m.size)
                    },
                    onPrev = { m?.takeIf { it.isNotEmpty() }?.let { matchIndex = (matchIndex - 1 + it.size) % it.size; goTo(it[matchIndex]) } },
                    onNext = { m?.takeIf { it.isNotEmpty() }?.let { matchIndex = (matchIndex + 1) % it.size; goTo(it[matchIndex]) } },
                    onClose = { searching = false; query = ""; matches = emptyList() },
                )
            } else {
                EditorTopBar(
                    title = session.name,
                    kind = DocKind.PDF,
                    modified = false,
                    onBack = onBack,
                    actions = {
                        IconButton({ searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.find)) }
                    },
                    menu = listOf(
                        MenuAction(stringResource(R.string.go_to_page)) { goToDialog = true },
                        MenuAction(stringResource(R.string.share), onShare),
                        MenuAction(stringResource(R.string.save_as), onSaveAs),
                        MenuAction(stringResource(R.string.print_pdf)) {
                            (ctx as? Activity)?.let { act ->
                                Export.printPdf(act, session.name.substringBeforeLast('.'), doc.pageCount) { out ->
                                    doc.file.inputStream().use { it.copyTo(out) }
                                }
                            }
                        },
                        MenuAction(stringResource(R.string.zoom_in)) { zoom = (zoom * 1.25f).coerceAtMost(5f) },
                        MenuAction(stringResource(R.string.zoom_out)) { zoom = (zoom / 1.25f).coerceAtLeast(1f) },
                    ),
                )
            }
        },
    ) { pad ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(pad).background(Color(0xFF5F5F5F))
                // Pinch to zoom: preview with a layer scale, re-render when the fingers lift.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var multi = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                multi = true
                                gestureScale = (gestureScale * event.calculateZoom()).coerceIn(1f / zoom, 5f / zoom)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (multi) {
                            zoom = (zoom * gestureScale).coerceIn(1f, 5f)
                            gestureScale = 1f
                        }
                    }
                },
        ) {
            val density = LocalDensity.current
            val viewport = maxWidth
            val pageWidth = (viewport - 16.dp) * zoom
            val pageWidthPx = with(density) { pageWidth.roundToPx() }
            // Render in steps so that small zoom changes reuse the cached bitmaps.
            val renderPx = ((pageWidthPx + 255) / 256 * 256).coerceAtMost(4096)
            val hScroll = rememberScrollState()
            Box(
                Modifier.fillMaxSize()
                    .then(if (zoom > 1f) Modifier.horizontalScroll(hScroll) else Modifier)
                    .graphicsLayer {
                        scaleX = gestureScale; scaleY = gestureScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(pageWidth + 16.dp).fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(doc.pageCount, key = { it }) { i ->
                        val (w, h) = doc.sizes[i]
                        val bmp by produceState(doc.cached(i, renderPx), i, renderPx) {
                            if (value == null) value = withContext(doc.dispatcher) { runCatching { doc.render(i, renderPx) }.getOrNull() }
                        }
                        Box(
                            Modifier.width(pageWidth).aspectRatio(w.toFloat() / h).background(Color.White),
                        ) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        }
                    }
                }
            }
            // Page indicator.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xCC000000),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) {
                Text(
                    stringResource(R.string.page_of, current + 1, doc.pageCount),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (goToDialog) {
        TextInputDialog(stringResource(R.string.go_to_page), "${current + 1}", onDone = { v ->
            v.trim().toIntOrNull()?.let { goTo(it - 1) }
            goToDialog = false
        }, onDismiss = { goToDialog = false })
    }
}
