// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.pptx

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.OfficeDoc
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.pptx.Frame
import io.github.Prestgg.naroffice.pptx.PAtom
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.pptx.ShapeKind
import io.github.Prestgg.naroffice.pptx.ShapeView
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.SOFT_BREAK
import io.github.Prestgg.naroffice.ui.NarRed
import io.github.Prestgg.naroffice.ui.common.ColorDialog
import io.github.Prestgg.naroffice.ui.common.EditorTopBar
import io.github.Prestgg.naroffice.ui.common.FormatBar
import io.github.Prestgg.naroffice.ui.common.Images
import io.github.Prestgg.naroffice.ui.common.MenuAction
import io.github.Prestgg.naroffice.ui.common.NarIcons
import io.github.Prestgg.naroffice.ui.common.TextStyles
import io.github.Prestgg.naroffice.ui.common.ToolDivider
import io.github.Prestgg.naroffice.ui.common.ToolDropdown
import io.github.Prestgg.naroffice.ui.common.ToolIcon
import io.github.Prestgg.naroffice.ui.common.ToolText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.math.max

class SlidesDoc(val doc: PptxDocument) : OfficeDoc() {
    override val kind = DocKind.SLIDES
    override val isModified get() = doc.isModified
    override fun save(out: OutputStream) = doc.save(out)
    override fun markSaved() = doc.history.markSaved()
}

private val DESK = Color(0xFFE9E4E3)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PptxEditor(
    session: Session,
    doc: PptxDocument,
    saveTick: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(session.uri == null) }
    var presenting by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var layoutDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var textEdit by remember { mutableStateOf<ShapeTextEdit?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val count = remember(tick, doc.version) { doc.slideCount }
    if (current >= count) current = (count - 1).coerceAtLeast(0)

    fun refresh() {
        tick++
    }

    fun commitText() {
        val te = textEdit ?: return
        if (te.commit()) refresh()
    }

    fun closeText() {
        commitText()
        textEdit = null
    }

    session.flush = { commitText() }

    fun openText(shapeId: String) {
        commitText()
        textEdit = runCatching { ShapeTextEdit(doc, current, shapeId) }.getOrNull()
        selected = shapeId
    }

    fun goTo(i: Int) {
        closeText()
        selected = null
        current = i.coerceIn(0, max(0, count - 1))
    }

    val modified = remember(tick, saveTick, doc.version, textEdit?.tick) { doc.isModified || textEdit?.dirty == true }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            } ?: return@launch
            val size = Images.size(data) ?: return@launch
            val type = ctx.contentResolver.getType(uri) ?: "image/png"
            val ext = when {
                "jpeg" in type || "jpg" in type -> "jpg"
                "gif" in type -> "gif"
                else -> "png"
            }
            closeText()
            selected = runCatching { doc.addPicture(current, data, ext, size.first, size.second) }.getOrNull()
            refresh()
        }
    }

    // Commit text edits after a pause in typing.
    val te = textEdit
    LaunchedEffect(te?.tick) {
        if (te == null) return@LaunchedEffect
        delay(1200)
        if (te.commit()) refresh()
    }

    if (presenting) {
        PresentationMode(doc, current, tick) { last -> presenting = false; current = last }
        return
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = session.name,
                kind = DocKind.SLIDES,
                modified = modified,
                onBack = onBack,
                actions = {
                    if (editing) {
                        IconButton({
                            closeText(); selected = null
                            if (doc.undo()) refresh()
                        }, enabled = doc.history.canUndo || textEdit?.dirty == true) {
                            Icon(NarIcons.Undo, stringResource(R.string.undo))
                        }
                        IconButton({
                            closeText(); selected = null
                            if (doc.redo()) refresh()
                        }, enabled = doc.history.canRedo) {
                            Icon(NarIcons.Redo, stringResource(R.string.redo))
                        }
                        IconButton({ closeText(); selected = null; editing = false }) {
                            Icon(Icons.Default.Check, stringResource(R.string.done))
                        }
                    } else {
                        IconButton({ presenting = true }) { Icon(NarIcons.Slideshow, stringResource(R.string.present)) }
                        if (modified) IconButton(onSave) { Icon(NarIcons.Save, stringResource(R.string.save)) }
                    }
                },
                menu = listOf(
                    MenuAction(stringResource(R.string.save), onSave),
                    MenuAction(stringResource(R.string.save_as), onSaveAs),
                    MenuAction(stringResource(R.string.share), onShare),
                    MenuAction(stringResource(R.string.present)) { closeText(); presenting = true },
                    MenuAction(stringResource(R.string.notes)) { showNotes = !showNotes },
                    MenuAction(stringResource(R.string.add_slide)) { closeText(); layoutDialog = true },
                ),
            )
        },
        floatingActionButton = {
            if (!editing) {
                FloatingActionButton({ editing = true }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Edit, stringResource(R.string.edit), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(DESK)) {
            // Current slide.
            val pager = rememberPagerState(initialPage = current) { count }
            LaunchedEffect(current, count) { if (pager.currentPage != current && current < count) pager.scrollToPage(current) }
            LaunchedEffect(pager.settledPage) { if (pager.settledPage != current) goTo(pager.settledPage) }
            HorizontalPager(
                state = pager,
                userScrollEnabled = !editing,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(0.dp),
                key = { it },
            ) { page ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    val (sw, sh) = doc.slideSize
                    val aspect = sw.toFloat() / sh
                    val w = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
                    val view = remember(page, tick, doc.version) { runCatching { doc.slide(page) }.getOrNull() }
                    if (view != null) {
                        if (editing && page == current) {
                            EditableSlide(
                                doc, view, Modifier.width(w).aspectRatio(aspect),
                                selected = selected,
                                onSelect = { id -> if (textEdit?.shapeId != id) closeText(); selected = id },
                                onEditText = { id -> openText(id) },
                                onMoved = { id, f ->
                                    closeText()
                                    runCatching { doc.setFrame(current, id, f) }
                                    refresh()
                                },
                            )
                        } else {
                            SlideImage(doc, view, Modifier.width(w).aspectRatio(aspect).border(0.5.dp, Color(0x33000000)))
                        }
                    }
                }
            }

            if (showNotes && textEdit == null) {
                val notes = remember(current, tick) { runCatching { doc.notesText(current) }.getOrDefault("") }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(stringResource(R.string.notes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(notes.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            val edit = textEdit
            if (edit != null) {
                TextPanel(edit, onDone = { closeText() })
            } else {
                Thumbnails(
                    doc, count, current, tick,
                    onSelect = { goTo(it) },
                    onDuplicate = { i -> closeText(); runCatching { goTo(doc.duplicateSlide(i)) }; refresh() },
                    onDelete = { i ->
                        closeText()
                        if (count > 1) {
                            doc.deleteSlide(i); refresh(); goTo(minOf(i, count - 2))
                        }
                    },
                    onMove = { i, d ->
                        val to = i + d
                        if (to in 0 until count) {
                            closeText(); doc.moveSlide(i, to); refresh(); goTo(to)
                        }
                    },
                    onToggleHidden = { i -> doc.setHidden(i, !doc.slide(i).hidden); refresh() },
                    onAdd = { closeText(); layoutDialog = true },
                )
                if (editing) {
                    Box(Modifier.navigationBarsPadding()) {
                        FormatBar {
                            ToolIcon(Icons.Default.Add, stringResource(R.string.add_slide)) { layoutDialog = true }
                            ToolIcon(NarIcons.TextFields, stringResource(R.string.add_text_box)) {
                                val (sw, sh) = doc.slideSize
                                val f = Frame(sw / 5, sh * 2 / 5, sw * 3 / 5, sh / 8)
                                val id = runCatching { doc.addTextBox(current, f) }.getOrNull()
                                refresh()
                                if (id != null) openText(id)
                            }
                            ToolIcon(NarIcons.Image, stringResource(R.string.insert_image)) { pickImage.launch("image/*") }
                            ToolDivider()
                            ToolIcon(Icons.Default.Edit, stringResource(R.string.edit_text), enabled = selected != null) {
                                selected?.let { openText(it) }
                            }
                            ToolIcon(Icons.Default.Delete, stringResource(R.string.delete_shape), enabled = selected != null) {
                                selected?.let { id ->
                                    closeText()
                                    runCatching { doc.deleteShape(current, id) }
                                    selected = null
                                    refresh()
                                }
                            }
                            ToolDivider()
                            ToolIcon(NarIcons.Duplicate, stringResource(R.string.duplicate_slide)) {
                                runCatching { goTo(doc.duplicateSlide(current)) }; refresh()
                            }
                            ToolIcon(NarIcons.Notes, stringResource(R.string.notes), checked = showNotes) { showNotes = !showNotes }
                        }
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    if (layoutDialog) {
        val layouts = remember(tick) { doc.layouts() }
        AlertDialog(
            onDismissRequest = { layoutDialog = false },
            title = { Text(stringResource(R.string.choose_layout)) },
            text = {
                LazyColumn {
                    itemsIndexed(layouts) { _, l ->
                        Text(
                            l.name,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = {
                                layoutDialog = false
                                closeText()
                                val idx = runCatching { doc.addSlide(l.part, current + 1) }.getOrNull()
                                refresh()
                                if (idx != null) goTo(idx)
                            }).padding(horizontal = 8.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton({ layoutDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The current slide in edit mode: tap selects, double tap edits text, drag moves or resizes. */
@Composable
private fun EditableSlide(
    doc: PptxDocument,
    view: io.github.Prestgg.naroffice.pptx.SlideView,
    modifier: Modifier,
    selected: String?,
    onSelect: (String?) -> Unit,
    onEditText: (String) -> Unit,
    onMoved: (String, Frame) -> Unit,
) {
    var layout by remember { mutableStateOf<SlideLayout?>(null) }
    var drag by remember { mutableStateOf<Pair<String, Frame>?>(null) }
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    val overrides = drag?.let { mapOf(it) } ?: emptyMap()
    val sel = selected
    val selShape = view.shapes.firstOrNull { it.id == sel }

    Box(
        modifier
            .border(1.dp, Color(0x55000000))
            .pointerInput(view) {
                detectTapGestures(
                    onTap = { p -> onSelect(layout?.let { hitShape(it, p) }?.id) },
                    onDoubleTap = { p ->
                        val s = layout?.let { hitShape(it, p) }
                        if (s != null && canHoldText(s)) onEditText(s.id)
                    },
                )
            }
            .pointerInput(view, sel) {
                var mode = 0 // 1 = move, 2 = resize
                var id: String? = null
                var start: Frame? = null
                var acc = Offset.Zero
                detectDragGestures(
                    onDragStart = { p ->
                        val l = layout ?: return@detectDragGestures
                        val selected0 = l.shapes.firstOrNull { it.shape.id == sel }
                        val hit = hitShape(l, p)
                        when {
                            selected0 != null && (p - selected0.rect.bottomRight).getDistance() < handle -> {
                                mode = 2; id = selected0.shape.id; start = selected0.shape.frame
                            }
                            selected0 != null && selected0.rect.contains(p) -> {
                                mode = 1; id = selected0.shape.id; start = selected0.shape.frame
                            }
                            hit != null -> {
                                onSelect(hit.id); mode = 1; id = hit.id; start = hit.frame
                            }
                            else -> mode = 0
                        }
                        acc = Offset.Zero
                    },
                    onDrag = { ch, d ->
                        val l = layout ?: return@detectDragGestures
                        val s = start ?: return@detectDragGestures
                        val sid = id ?: return@detectDragGestures
                        if (mode == 0) return@detectDragGestures
                        ch.consume()
                        acc += d
                        val dx = (acc.x / l.pxPerEmu).toLong()
                        val dy = (acc.y / l.pxPerEmu).toLong()
                        val min = (12f / l.pxPerEmu).toLong()
                        drag = sid to if (mode == 1) s.copy(x = s.x + dx, y = s.y + dy)
                        else s.copy(w = (s.w + dx).coerceAtLeast(min), h = (s.h + dy).coerceAtLeast(min))
                    },
                    onDragEnd = {
                        drag?.let { (i, f) -> onMoved(i, f) }
                        drag = null; mode = 0
                    },
                    onDragCancel = { drag = null; mode = 0 },
                )
            },
    ) {
        SlideImage(doc, view, Modifier.fillMaxSize(), edit = true, overrides = overrides, onLayout = { layout = it })
        // Selection frame and handles.
        if (selShape != null) {
            Spacer(
                Modifier.fillMaxSize().drawWithContent {
                    val l = layout ?: return@drawWithContent
                    val f = (drag?.takeIf { it.first == selShape.id }?.second) ?: selShape.frame
                    val x = f.x * l.pxPerEmu
                    val y = f.y * l.pxPerEmu
                    val w = f.w * l.pxPerEmu
                    val h = f.h * l.pxPerEmu
                    drawRect(NarRed, Offset(x, y), Size(w, h), style = Stroke(2.dp.toPx()))
                    val hs = 8.dp.toPx()
                    for ((cx, cy) in listOf(x to y, x + w to y, x to y + h)) {
                        drawCircle(Color.White, hs / 2 + 1, Offset(cx, cy))
                        drawCircle(NarRed, hs / 2, Offset(cx, cy), style = Stroke(2f))
                    }
                    drawCircle(NarRed, hs * 0.75f, Offset(x + w, y + h))
                    drawCircle(Color.White, hs * 0.35f, Offset(x + w, y + h))
                },
            )
        }
    }
}

private fun canHoldText(s: ShapeView) = s.kind != ShapeKind.PICTURE && s.kind != ShapeKind.TABLE && s.kind != ShapeKind.CHART

private val SIZES = listOf(10f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 40f, 44f, 54f, 60f, 72f, 96f)

/** Bottom panel editing the text of one shape. */
@Composable
private fun TextPanel(te: ShapeTextEdit, onDone: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val t = te.tick
    val focus = remember { FocusRequester() }
    var colorDialog by remember { mutableStateOf(false) }
    val props = te.currentProps()
    val visual = remember(te.tick) { editVisual(te) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding().imePadding()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FormatBar {
                ToolDropdown(
                    props?.sizePt?.let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) } ?: "–",
                    width = 40,
                    items = SIZES.map { s -> s.toInt().toString() to { te.format { f -> f.size(s) } } },
                )
                ToolText("B", stringResource(R.string.bold), props?.bold == true) { val on = props?.bold != true; te.format { it.bold(on) } }
                ToolText("I", stringResource(R.string.italic), props?.italic == true, italic = true) { val on = props?.italic != true; te.format { it.italic(on) } }
                ToolText("U", stringResource(R.string.underline), props?.underline == true, underline = true) { val on = props?.underline != true; te.format { it.underline(on) } }
                ToolText("S", stringResource(R.string.strike), props?.strike == true, strike = true) { val on = props?.strike != true; te.format { it.strike(on) } }
                IconButton({ colorDialog = true }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("A", style = MaterialTheme.typography.titleMedium)
                        Box(Modifier.width(20.dp).height(4.dp).background(props?.color?.let { Color(it) } ?: Color.Black))
                    }
                }
                ToolDivider()
                val align = te.currentAlign()
                ToolIcon(NarIcons.AlignLeft, stringResource(R.string.align_left), align == Align.START) { te.setAlign(Align.START) }
                ToolIcon(NarIcons.AlignCenter, stringResource(R.string.align_center), align == Align.CENTER) { te.setAlign(Align.CENTER) }
                ToolIcon(NarIcons.AlignRight, stringResource(R.string.align_right), align == Align.END) { te.setAlign(Align.END) }
                ToolIcon(NarIcons.AlignJustify, stringResource(R.string.align_justify), align == Align.JUSTIFY) { te.setAlign(Align.JUSTIFY) }
                ToolIcon(NarIcons.IndentLess, stringResource(R.string.indent_less)) { te.changeLevel(-1) }
                ToolIcon(NarIcons.IndentMore, stringResource(R.string.indent_more)) { te.changeLevel(1) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    BasicTextField(
                        value = te.value,
                        onValueChange = { te.onValueChange(it) },
                        textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF1B1B1B)),
                        cursorBrush = SolidColor(NarRed),
                        visualTransformation = { text ->
                            TransformedText(if (visual.length == text.length) visual else AnnotatedString(text.text), OffsetMapping.Identity)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 200.dp).focusRequester(focus),
                    )
                }
                IconButton(onDone) { Icon(Icons.Default.Check, stringResource(R.string.done), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    LaunchedEffect(te) { runCatching { focus.requestFocus() } }
    if (colorDialog) {
        ColorDialog(stringResource(R.string.text_color), stringResource(R.string.automatic),
            onPick = { c -> te.format { it.color(c) }; colorDialog = false }, onDismiss = { colorDialog = false })
    }
}

/** Styled text for the edit field; same length as the field text. */
private fun editVisual(te: ShapeTextEdit): AnnotatedString = buildAnnotatedString {
    var para = 0
    for (s in te.combined.spans) {
        val level = te.levelAt(para)
        val p = te.props(level, s.fmt)
        // Sizes are compressed so that titles stay readable in a small field.
        val size = (12f + (p.sizePt - 12f) * 0.25f).coerceIn(12f, 24f)
        val color = p.color?.takeIf { luminance(it) < 0.8f }
        val style = TextStyles.span(p.copy(sizePt = size, color = color, highlight = null), 1f)
        val atom = s.atom as PAtom?
        if (atom != null) {
            withStyle(style.copy(color = Color(0xFF8E8E8E))) { append(atom.display.firstOrNull() ?: '#') }
        } else {
            withStyle(style) { append(s.text.replace(SOFT_BREAK, '\n')) }
            para += s.text.count { it == '\n' }
        }
    }
}

private fun luminance(argb: Int): Float {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnails(
    doc: PptxDocument,
    count: Int,
    current: Int,
    tick: Int,
    onSelect: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleHidden: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current < count) state.animateScrollToItem(current) }
    val (sw, sh) = doc.slideSize
    val aspect = sw.toFloat() / sh
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count, key = { "$it-$tick" }) { i ->
                var menu by remember { mutableStateOf(false) }
                val view = remember(i, tick, doc.version) { runCatching { doc.slide(i) }.getOrNull() }
                Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        Box(
                            Modifier.width(96.dp).aspectRatio(aspect)
                                .border(if (i == current) 2.5.dp else 0.5.dp, if (i == current) NarRed else Color(0x44000000))
                                .combinedClickable(onClick = { onSelect(i) }, onLongClick = { menu = true }),
                        ) {
                            if (view != null) SlideImage(doc, view, Modifier.fillMaxSize())
                            if (view?.hidden == true) Box(Modifier.fillMaxSize().background(Color(0x88FFFFFF)))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.duplicate_slide)) }, { menu = false; onDuplicate(i) })
                            DropdownMenuItem({ Text(stringResource(R.string.move_up)) }, { menu = false; onMove(i, -1) }, enabled = i > 0)
                            DropdownMenuItem({ Text(stringResource(R.string.move_down)) }, { menu = false; onMove(i, 1) }, enabled = i < count - 1)
                            DropdownMenuItem(
                                { Text(stringResource(if (view?.hidden == true) R.string.show_slide else R.string.hide_slide)) },
                                { menu = false; onToggleHidden(i) },
                            )
                            DropdownMenuItem({ Text(stringResource(R.string.delete_slide)) }, { menu = false; onDelete(i) }, enabled = count > 1)
                        }
                    }
                    Text("${i + 1}", fontSize = 11.sp, fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Box(
                    Modifier.padding(horizontal = 4.dp).width(56.dp).height((96f / aspect).dp)
                        .clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, stringResource(R.string.add_slide)) }
            }
        }
    }
}

/** Full-screen slide show: tap or swipe to move, back to leave. */
@Composable
private fun PresentationMode(doc: PptxDocument, start: Int, tick: Int, onExit: (Int) -> Unit) {
    val visible = remember(tick) { (0 until doc.slideCount).filter { runCatching { !doc.slide(it).hidden }.getOrDefault(true) } }
    val startPage = visible.indexOf(start).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = startPage) { visible.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

    fun exit() = onExit(visible.getOrElse(pager.currentPage) { start })

    BackHandler { exit() }
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { exit() } })
            return@Box
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { visible[it] }) { page ->
            BoxWithConstraints(
                Modifier.fillMaxSize().pointerInput(page) {
                    detectTapGestures { p ->
                        scope.launch {
                            if (p.x > size.width / 3f) {
                                if (page < visible.size - 1) pager.animateScrollToPage(page + 1) else exit()
                            } else if (page > 0) pager.animateScrollToPage(page - 1)
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                val (sw, sh) = doc.slideSize
                val aspect = sw.toFloat() / sh
                val w = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
                val v = remember(page, tick) { runCatching { doc.slide(visible[page]) }.getOrNull() }
                if (v != null) SlideImage(doc, v, Modifier.width(w).aspectRatio(aspect))
            }
        }
        Text(
            "${pager.currentPage + 1} / ${visible.size}",
            color = Color(0x88FFFFFF), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        )
        IconButton({ exit() }, Modifier.align(Alignment.TopEnd).padding(4.dp).size(40.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color(0x55FFFFFF))
        }
    }
}
