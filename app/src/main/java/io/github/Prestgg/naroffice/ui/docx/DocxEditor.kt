// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.docx

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.docx.DocBlock
import io.github.Prestgg.naroffice.docx.DocxDocument
import io.github.Prestgg.naroffice.docx.DocxPara
import io.github.Prestgg.naroffice.docx.Numbering
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.text.Baseline
import io.github.Prestgg.naroffice.ui.NarRed
import io.github.Prestgg.naroffice.ui.common.ColorDialog
import io.github.Prestgg.naroffice.ui.common.EditorTopBar
import io.github.Prestgg.naroffice.ui.common.FormatBar
import io.github.Prestgg.naroffice.ui.common.Images
import io.github.Prestgg.naroffice.ui.common.MenuAction
import io.github.Prestgg.naroffice.ui.common.NarIcons
import io.github.Prestgg.naroffice.ui.common.SearchBar
import io.github.Prestgg.naroffice.ui.common.TextStyles
import io.github.Prestgg.naroffice.ui.common.ToolDivider
import io.github.Prestgg.naroffice.ui.common.ToolDropdown
import io.github.Prestgg.naroffice.ui.common.ToolIcon
import io.github.Prestgg.naroffice.ui.common.ToolText
import io.github.Prestgg.naroffice.xml.XElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PAPER = Color.White
private val DESK = Color(0xFFE9E4E3)

@Composable
fun DocxEditor(
    session: Session,
    doc: DocxDocument,
    saveTick: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    val ctl = remember(doc) { DocxController(doc) }
    val ctx = LocalContext.current
    session.flush = { ctl.commit() }
    var editing by rememberSaveable { mutableStateOf(session.uri == null) }
    var zoom by rememberSaveable { mutableFloatStateOf(1.35f) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var replaceDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val tick = ctl.tick
    val blocks = remember(tick) { doc.blocks() }
    val labels = remember(tick) { doc.listLabels() }
    val matches = remember(tick, query) { if (query.length >= 1) doc.search(query) else emptyList() }
    val modified = remember(tick, saveTick, ctl.activeTick) { doc.isModified || ctl.active?.dirty == true }

    // Save typing bursts into the document after a pause.
    LaunchedEffect(ctl.activeTick) {
        delay(1500)
        ctl.commit()
    }

    fun goToMatch(i: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((i % matches.size) + matches.size) % matches.size
        val idx = ctl.blockIndexOf(blocks, matches[matchIndex])
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
    }

    fun stopEditing() {
        ctl.deactivate()
        focusManager.clearFocus()
        keyboard?.hide()
        editing = false
    }

    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(
                    query, { query = it; matchIndex = 0 },
                    status = if (query.isEmpty()) "" else if (matches.isEmpty()) stringResource(R.string.no_matches)
                    else stringResource(R.string.matches, matchIndex + 1, matches.size),
                    onPrev = { goToMatch(matchIndex - 1) },
                    onNext = { goToMatch(matchIndex + 1) },
                    onClose = { searching = false; query = "" },
                )
            } else {
                EditorTopBar(
                    title = session.name,
                    kind = DocKind.WORD,
                    modified = modified,
                    onBack = onBack,
                    actions = {
                        if (editing) {
                            IconButton({ ctl.undo() }, enabled = doc.history.canUndo || ctl.active?.dirty == true) {
                                Icon(NarIcons.Undo, stringResource(R.string.undo))
                            }
                            IconButton({ ctl.redo() }, enabled = doc.history.canRedo) {
                                Icon(NarIcons.Redo, stringResource(R.string.redo))
                            }
                            IconButton({ stopEditing() }) { Icon(Icons.Default.Check, stringResource(R.string.done)) }
                        } else {
                            IconButton({ searching = true }) { Icon(Icons.Default.Search, stringResource(R.string.find)) }
                            if (modified) IconButton(onSave) { Icon(NarIcons.Save, stringResource(R.string.save)) }
                        }
                    },
                    menu = listOf(
                        MenuAction(stringResource(R.string.save), onSave),
                        MenuAction(stringResource(R.string.save_as), onSaveAs),
                        MenuAction(stringResource(R.string.share), onShare),
                        MenuAction(stringResource(R.string.print_pdf)) {
                            ctl.commit()
                            (ctx as? android.app.Activity)?.let {
                                io.github.Prestgg.naroffice.ui.export.Export.printHtml(
                                    it, io.github.Prestgg.naroffice.ui.export.Export.docxToHtml(doc), session.name.substringBeforeLast('.'))
                            }
                        },
                        MenuAction(stringResource(R.string.find)) { searching = true },
                        MenuAction(stringResource(R.string.find_replace)) { replaceDialog = true },
                        MenuAction(stringResource(R.string.zoom_in)) { zoom = (zoom * 1.2f).coerceAtMost(4f) },
                        MenuAction(stringResource(R.string.zoom_out)) { zoom = (zoom / 1.2f).coerceAtLeast(0.5f) },
                    ),
                )
            }
        },
        bottomBar = { if (editing) DocxToolbar(ctl) },
        floatingActionButton = {
            if (!editing) {
                FloatingActionButton({ editing = true }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Edit, stringResource(R.string.edit), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
    ) { pad ->
        // Pinch to zoom: preview with a layer scale, apply when the fingers lift.
        var gestureScale by remember { mutableFloatStateOf(1f) }
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(pad).background(DESK)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var multi = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                multi = true
                                gestureScale = (gestureScale * event.calculateZoom()).coerceIn(0.4f / zoom, 4f / zoom)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (multi) {
                            zoom = (zoom * gestureScale).coerceIn(0.4f, 4f)
                            gestureScale = 1f
                        }
                    }
                },
        ) {
            val pageWidth = minOf(maxWidth, 840.dp)
            val sidePad = 16.dp
            val maxWidthPt = (pageWidth.value - sidePad.value * 2) / zoom
            val renderer = remember(doc, zoom, maxWidthPt, tick) { DocxRenderer(doc, zoom, maxWidthPt) }
            val list = @Composable {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = gestureScale; scaleY = gestureScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(blocks, key = { ctl.keyOf(it.el) }) { b ->
                        Box(Modifier.width(pageWidth).background(PAPER).padding(horizontal = sidePad)) {
                            BlockView(b, ctl, renderer, labels, editing, if (searching) query else null, tick)
                        }
                    }
                    item {
                        // Tapping below the last paragraph continues the document.
                        Box(
                            Modifier.width(pageWidth).height(160.dp).background(PAPER)
                                .pointerInput(editing) {
                                    detectTapGestures {
                                        if (!editing) return@detectTapGestures
                                        val last = doc.blocks().lastOrNull()
                                        if (last is DocBlock.Para && last.container === doc.body) {
                                            ctl.activate(last.container, last.el, Int.MAX_VALUE)
                                        } else {
                                            ctl.commit()
                                            doc.insertAfter(doc.body, last?.el, listOf(XElement("w:p")))
                                            val p = doc.blocks().last()
                                            ctl.activate(p.container, p.el, 0)
                                        }
                                    }
                                },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            if (editing) list() else SelectionContainer { list() }
        }
    }

    if (replaceDialog) {
        ReplaceDialog(
            onReplace = { q, r -> ctl.replaceAll(q, r) },
            onDismiss = { replaceDialog = false },
        )
    }
}

@Composable
private fun BlockView(
    b: DocBlock,
    ctl: DocxController,
    r: DocxRenderer,
    labels: Map<XElement, String>,
    editing: Boolean,
    query: String?,
    tick: Int,
) {
    when (b) {
        is DocBlock.Para -> ParagraphView(b.container, b.el, ctl, r, labels[b.el], editing, query)
        is DocBlock.Table -> TableView(b, ctl, r, labels, editing, query, tick)
    }
}

@Composable
private fun ParagraphView(
    container: XElement,
    p: XElement,
    ctl: DocxController,
    r: DocxRenderer,
    label: String?,
    editing: Boolean,
    query: String?,
) {
    val doc = r.doc
    val pp = remember(p, r) { doc.styles.resolvePara(p.child("w:pPr")) }
    val active = ctl.active
    val isActive = active != null && active.key == ctl.keyOf(p) && editing
    val scale = r.scale
    val markSize = remember(p, r) {
        doc.styles.resolveRun(doc.styles.paraStyleId(p.child("w:pPr")), p.path("w:pPr", "w:rPr")).sizePt
    }
    val base = TextStyle(
        fontSize = (markSize * scale).sp,
        color = Color(0xFF1B1B1B),
        textAlign = TextStyles.align(pp.align),
        lineHeight = (1.2f * pp.lineSpacing).em,
        textIndent = if (label == null && pp.indentFirstPt != 0f) {
            TextIndent(firstLine = (pp.indentFirstPt.coerceAtLeast(0f) * scale).sp)
        } else TextIndent.None,
    )
    val listIndent = if (label != null) {
        pp.numId?.let { doc.numbering.level(it, pp.numLevel) }?.let { maxOf(it.indentPt - it.hangingPt, 0f) } ?: (pp.numLevel * 36f)
    } else null
    val leftPt = listIndent ?: (pp.indentLeftPt + minOf(pp.indentFirstPt, 0f)).coerceAtLeast(0f)
    Column(
        Modifier.fillMaxWidth()
            .padding(top = (pp.spaceBeforePt * scale * 0.8f).dp, bottom = (pp.spaceAfterPt * scale * 0.8f).dp)
            .then(if (pp.shading != null) Modifier.background(Color(pp.shading)) else Modifier),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = (leftPt * scale).coerceAtMost(200f).dp)) {
            if (label != null) {
                val labelWidth = maxOf(18f, (pp.numId?.let { doc.numbering.level(it, pp.numLevel)?.hangingPt } ?: 18f)) * scale
                val firstRun = remember(p, r) {
                    DocxPara.read(p).spans.firstOrNull { it.atom == null }?.let {
                        r.props(doc.styles.paraStyleId(p.child("w:pPr")), it)
                    }
                }
                BasicText(
                    label,
                    style = base.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        fontSize = ((firstRun?.sizePt ?: markSize) * scale).sp,
                        color = firstRun?.color?.let { Color(it) } ?: Color(0xFF1B1B1B)),
                    modifier = Modifier.widthIn(min = labelWidth.dp).padding(end = 4.dp),
                )
            }
            if (isActive) {
                ParagraphField(ctl, r, base, Modifier.weight(1f))
            } else {
                val rich = remember(p) { DocxPara.read(p) }
                val view = remember(p, r, query, editing) { r.viewText(p, rich, query, links = !editing) }
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val inline = remember(view) { r.inlineContent(view) { ref -> DocImage(r.doc, ref) } }
                androidx.compose.foundation.text.BasicText(
                    view.text,
                    style = base,
                    inlineContent = inline,
                    onTextLayout = { layout = it },
                    modifier = Modifier.weight(1f).then(
                        if (editing) Modifier.pointerInput(p) {
                            detectTapGestures { pos ->
                                val off = layout?.getOffsetForPosition(pos) ?: 0
                                ctl.activate(container, p, view.toRich.getOrElse(off) { rich.length })
                            }
                        } else Modifier,
                    ),
                )
            }
        }
        // Text boxes anchored in this paragraph, shown below it (read-only).
        val boxes = remember(p) { textBoxes(p) }
        for (box in boxes) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).border(0.5.dp, Color(0xFFB0B0B0)).padding(6.dp),
            ) {
                for (b in doc.blocksOf(box)) {
                    androidx.compose.runtime.key(ctl.keyOf(b.el)) {
                        BlockView(b, ctl, r, emptyMap(), editing = false, query = query, tick = 0)
                    }
                }
            }
        }
        if (pp.borderBottom) HorizontalDivider(color = Color(0xFF555555))
        val hasBreak = remember(p) { p.descendants().any { it.name == "w:br" && it["w:type"] == "page" } }
        if (hasBreak) PageBreakMark()
    }
}

/** w:txbxContent elements of a paragraph, skipping the VML fallback copies. */
private fun textBoxes(p: XElement): List<XElement> {
    val out = ArrayList<XElement>()
    fun walk(e: XElement) {
        for (c in e.children) {
            if (c !is XElement) continue
            when (c.localName) {
                "Fallback" -> {}
                "txbxContent" -> out.add(c)
                else -> walk(c)
            }
        }
    }
    walk(p)
    return out
}

@Composable
private fun PageBreakMark() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFBBBBBB))
        Text(stringResource(R.string.page_break), fontSize = 10.sp, color = Color(0xFF999999), modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(Modifier.weight(1f), color = Color(0xFFBBBBBB))
    }
}

@Composable
private fun ParagraphField(ctl: DocxController, r: DocxRenderer, base: TextStyle, modifier: Modifier) {
    val a = ctl.active ?: return
    @Suppress("UNUSED_VARIABLE") val t = ctl.activeTick
    val focus = remember { FocusRequester() }
    val visual = r.editVisual(a.p, a.rich)
    BasicTextField(
        value = a.value,
        onValueChange = { ctl.onValueChange(a, it) },
        textStyle = base,
        cursorBrush = SolidColor(NarRed),
        visualTransformation = { text ->
            val shown = if (visual.length == text.length) visual else androidx.compose.ui.text.AnnotatedString(text.text)
            TransformedText(shown, OffsetMapping.Identity)
        },
        modifier = modifier.focusRequester(focus),
    )
    LaunchedEffect(a.key) {
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun DocImage(doc: DocxDocument, ref: ImageRef) {
    val key = "${System.identityHashCode(doc)}:${ref.relId}"
    val bmp by produceState(Images.cached(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { Images.decode(key, doc.imageBytes(ref.relId)) }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE)))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableView(
    t: DocBlock.Table,
    ctl: DocxController,
    r: DocxRenderer,
    labels: Map<XElement, String>,
    editing: Boolean,
    query: String?,
    tick: Int,
) {
    val doc = r.doc
    val grid = remember(t.el, tick) {
        t.el.child("w:tblGrid")?.children("w:gridCol")?.map { (it["w:w"]?.toFloatOrNull() ?: 1440f) / 20f } ?: emptyList()
    }
    val bordered = remember(t.el, tick) {
        val b = t.el.path("w:tblPr", "w:tblBorders")
        val styled = t.el.path("w:tblPr", "w:tblStyle") != null
        styled || (b != null && b.elements.any { it["w:val"] != "nil" && it["w:val"] != "none" })
    }
    // Tables wider than the page are scaled down to fit, so that they wrap instead of scrolling.
    val totalPt = grid.sum().takeIf { it > 0f } ?: r.maxWidthPt
    val scale = r.scale * minOf(1f, r.maxWidthPt / totalPt)
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(scroll)) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            for ((ri, row) in t.rows.withIndex()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    var col = 0
                    for (cell in doc.cells(row)) {
                        val tcPr = cell.child("w:tcPr")
                        val span = tcPr?.child("w:gridSpan")?.get("w:val")?.toIntOrNull() ?: 1
                        val vMerge = tcPr?.child("w:vMerge")
                        val continued = vMerge != null && vMerge["w:val"] != "restart"
                        val wPt = (col until col + span).sumOf { (grid.getOrNull(it) ?: 72f).toDouble() }.toFloat()
                        val fill = tcPr?.child("w:shd")?.get("w:fill")?.let { io.github.Prestgg.naroffice.ooxml.Colors.hex(it) }
                        val colIndex = col
                        var menu by remember { mutableStateOf(false) }
                        Box(
                            Modifier.width((wPt * scale).coerceIn(24f, 600f).dp).fillMaxHeight()
                                .then(if (fill != null) Modifier.background(Color(fill)) else Modifier)
                                .then(if (bordered) Modifier.border(0.5.dp, Color(0xFF7A7A7A)) else Modifier.border(0.5.dp, Color(0x22000000)))
                                .then(if (editing) Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    onClick = {
                                        val first = doc.blocksOf(cell).firstOrNull()
                                        if (first is DocBlock.Para) ctl.activate(cell, first.el, Int.MAX_VALUE)
                                    },
                                    onLongClick = { menu = true },
                                ) else Modifier)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            if (!continued) {
                                Column {
                                    for (b in doc.blocksOf(cell)) {
                                        androidx.compose.runtime.key(ctl.keyOf(b.el)) {
                                            BlockView(b, ctl, r, labels, editing, query, tick)
                                        }
                                    }
                                }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text(stringResource(R.string.add_row)) }, {
                                    menu = false; ctl.tableOp { doc.addRow(t.el, row) }
                                })
                                DropdownMenuItem({ Text(stringResource(R.string.delete_row)) }, {
                                    menu = false; ctl.tableOp { doc.deleteRow(t.el, row) }
                                })
                                DropdownMenuItem({ Text(stringResource(R.string.add_col)) }, {
                                    menu = false; ctl.tableOp { doc.addColumn(t.el, colIndex) }
                                })
                                DropdownMenuItem({ Text(stringResource(R.string.delete_col)) }, {
                                    menu = false; ctl.tableOp { doc.deleteColumn(t.el, colIndex) }
                                })
                                DropdownMenuItem({ Text(stringResource(R.string.delete_table)) }, {
                                    menu = false; ctl.deleteBlock(t.container, t.el)
                                })
                            }
                        }
                        col += span
                    }
                }
            }
        }
    }
}

private val SIZES = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 48f, 72f)

private val HIGHLIGHTS = listOf(
    "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
    "red" to 0xFFFF0000, "blue" to 0xFF0000FF, "lightGray" to 0xFFC0C0C0, "darkYellow" to 0xFF808000,
    "darkGreen" to 0xFF008000, "darkCyan" to 0xFF008080,
)

@Composable
private fun DocxToolbar(ctl: DocxController) {
    @Suppress("UNUSED_VARIABLE") val t = ctl.activeTick + ctl.tick
    val props = ctl.currentProps()
    val enabled = ctl.active != null
    var colorDialog by remember { mutableStateOf(false) }
    var highlightDialog by remember { mutableStateOf(false) }
    var tableDialog by remember { mutableStateOf(false) }
    var insertMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
            ctl.insertImage(data, ext, size.first, size.second)
        }
    }

    Box(Modifier.navigationBarsPadding().imePadding()) {
        FormatBar {
            val heading = ctl.currentHeading()
            val styleNames = listOf(
                stringResource(R.string.style_normal), stringResource(R.string.style_title),
                stringResource(R.string.style_h1), stringResource(R.string.style_h2), stringResource(R.string.style_h3),
            )
            ToolDropdown(
                when (heading) { null -> styleNames[0]; 0 -> styleNames[1]; else -> styleNames.getOrElse(heading + 1) { styleNames[0] } },
                width = 104,
                items = listOf(
                    styleNames[0] to { ctl.setHeading(null) },
                    styleNames[1] to { ctl.setHeading(0) },
                    styleNames[2] to { ctl.setHeading(1) },
                    styleNames[3] to { ctl.setHeading(2) },
                    styleNames[4] to { ctl.setHeading(3) },
                ),
            )
            ToolDropdown(
                props?.sizePt?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "–",
                width = 40,
                items = SIZES.map { s -> (if (s % 1f == 0f) s.toInt().toString() else s.toString()) to { ctl.setSize(s) } },
            )
            ToolText("B", stringResource(R.string.bold), props?.bold == true) { ctl.setBold(props?.bold != true) }
            ToolText("I", stringResource(R.string.italic), props?.italic == true, italic = true) { ctl.setItalic(props?.italic != true) }
            ToolText("U", stringResource(R.string.underline), props?.underline == true, underline = true) { ctl.setUnderline(props?.underline != true) }
            ToolText("S", stringResource(R.string.strike), props?.strike == true, strike = true) { ctl.setStrike(props?.strike != true) }
            ToolText("x²", stringResource(R.string.superscript), props?.baseline == Baseline.SUPER, weight = androidx.compose.ui.text.font.FontWeight.Normal) {
                ctl.setBaseline(if (props?.baseline == Baseline.SUPER) Baseline.NORMAL else Baseline.SUPER)
            }
            ToolText("x₂", stringResource(R.string.subscript), props?.baseline == Baseline.SUB, weight = androidx.compose.ui.text.font.FontWeight.Normal) {
                ctl.setBaseline(if (props?.baseline == Baseline.SUB) Baseline.NORMAL else Baseline.SUB)
            }
            ColorTool(props?.color?.let { Color(it) } ?: Color.Black) { colorDialog = true }
            ToolIcon(NarIcons.Highlight, stringResource(R.string.highlight), props?.highlight != null) { highlightDialog = true }
            ToolDivider()
            val align = ctl.currentAlign()
            ToolIcon(NarIcons.AlignLeft, stringResource(R.string.align_left), align == Align.START, enabled) { ctl.setAlign(Align.START) }
            ToolIcon(NarIcons.AlignCenter, stringResource(R.string.align_center), align == Align.CENTER, enabled) { ctl.setAlign(Align.CENTER) }
            ToolIcon(NarIcons.AlignRight, stringResource(R.string.align_right), align == Align.END, enabled) { ctl.setAlign(Align.END) }
            ToolIcon(NarIcons.AlignJustify, stringResource(R.string.align_justify), align == Align.JUSTIFY, enabled) { ctl.setAlign(Align.JUSTIFY) }
            ToolDivider()
            val list = ctl.currentList()
            ToolIcon(NarIcons.Bullets, stringResource(R.string.bullets), list == true, enabled) { ctl.toggleList(true) }
            ToolIcon(NarIcons.Numbered, stringResource(R.string.numbering), list == false, enabled) { ctl.toggleList(false) }
            ToolIcon(NarIcons.IndentLess, stringResource(R.string.indent_less), false, enabled) { ctl.indent(-1) }
            ToolIcon(NarIcons.IndentMore, stringResource(R.string.indent_more), false, enabled) { ctl.indent(1) }
            ToolDivider()
            Box {
                ToolIcon(Icons.Default.Add, stringResource(R.string.insert)) { insertMenu = true }
                DropdownMenu(insertMenu, { insertMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.insert_table)) }, { insertMenu = false; tableDialog = true },
                        leadingIcon = { Icon(NarIcons.Table, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.insert_image)) }, { insertMenu = false; pickImage.launch("image/*") },
                        leadingIcon = { Icon(NarIcons.Image, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.insert_page_break)) }, { insertMenu = false; ctl.insertPageBreak() },
                        leadingIcon = { Icon(NarIcons.Remove, null) })
                }
            }
            ToolIcon(Icons.Default.Delete, stringResource(R.string.delete_paragraph), false, enabled) {
                ctl.active?.let { ctl.deleteBlock(it.container, it.p) }
            }
        }
    }

    if (colorDialog) {
        ColorDialog(stringResource(R.string.text_color), stringResource(R.string.automatic),
            onPick = { ctl.setColor(it); colorDialog = false }, onDismiss = { colorDialog = false })
    }
    if (highlightDialog) {
        ColorDialog(stringResource(R.string.highlight), stringResource(R.string.no_color),
            colors = HIGHLIGHTS.map { it.second.toInt() },
            onPick = { c -> ctl.setHighlight(HIGHLIGHTS.firstOrNull { it.second.toInt() == c }?.first); highlightDialog = false },
            onDismiss = { highlightDialog = false })
    }
    if (tableDialog) {
        TableSizeDialog(onDone = { r, c -> tableDialog = false; ctl.insertTable(r, c) }, onDismiss = { tableDialog = false })
    }
}

@Composable
private fun ColorTool(color: Color, onClick: () -> Unit) {
    IconButton(onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("A", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.width(20.dp).height(4.dp).background(color))
        }
    }
}

@Composable
private fun TableSizeDialog(onDone: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    @Composable
    fun Stepper(label: String, v: Int, set: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            IconButton({ set((v - 1).coerceAtLeast(1)) }) { Icon(NarIcons.Remove, null) }
            Text("$v", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton({ set((v + 1).coerceAtMost(30)) }) { Icon(Icons.Default.Add, null) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_table)) },
        text = {
            Column {
                Stepper(stringResource(R.string.table_rows), rows) { rows = it }
                Stepper(stringResource(R.string.table_cols), cols) { cols = it }
            }
        },
        confirmButton = { TextButton({ onDone(rows, cols) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ReplaceDialog(onReplace: (String, String) -> Int, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var r by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val fmt = stringResource(R.string.replaced_n)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.find_replace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.find)) }, singleLine = true)
                OutlinedTextField(r, { r = it }, label = { Text(stringResource(R.string.replace_with)) }, singleLine = true)
                result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton({ result = fmt.format(onReplace(q, r)) }, enabled = q.isNotEmpty()) { Text(stringResource(R.string.replace_all)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
