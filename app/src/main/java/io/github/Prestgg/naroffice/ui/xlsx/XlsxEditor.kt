// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.xlsx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.OfficeDoc
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.ui.SheetGreen
import io.github.Prestgg.naroffice.ui.common.ColorDialog
import io.github.Prestgg.naroffice.ui.common.EditorTopBar
import io.github.Prestgg.naroffice.ui.common.MenuAction
import io.github.Prestgg.naroffice.ui.common.NarIcons
import io.github.Prestgg.naroffice.ui.common.TextInputDialog
import io.github.Prestgg.naroffice.ui.common.ToolDivider
import io.github.Prestgg.naroffice.ui.common.ToolDropdown
import io.github.Prestgg.naroffice.ui.common.ToolIcon
import io.github.Prestgg.naroffice.ui.common.ToolText
import io.github.Prestgg.naroffice.xlsx.CellRange
import io.github.Prestgg.naroffice.xlsx.CellRef
import io.github.Prestgg.naroffice.xlsx.HAlign
import io.github.Prestgg.naroffice.xlsx.NumFmtPreset
import io.github.Prestgg.naroffice.xlsx.StyleChange
import io.github.Prestgg.naroffice.xlsx.XValue
import io.github.Prestgg.naroffice.xlsx.XlsxDocument
import java.io.OutputStream

/** A workbook, saved either as .xlsx or (for files opened as CSV) as CSV text. */
class SheetDoc(val doc: XlsxDocument, val csv: Boolean) : OfficeDoc() {
    override val kind = if (csv) DocKind.CSV else DocKind.SHEET
    override val isModified get() = doc.isModified
    override fun save(out: OutputStream) {
        if (csv) out.write(doc.sheetToCsv(0).toByteArray(Charsets.UTF_8)) else doc.save(out)
        doc.history.markSaved()
    }
    override fun markSaved() = doc.history.markSaved()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun XlsxEditor(
    session: Session,
    sd: SheetDoc,
    saveTick: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    val doc = sd.doc
    var tick by remember { mutableIntStateOf(0) }
    var sheetIndex by remember { mutableIntStateOf(doc.activeSheet.coerceIn(0, doc.sheetCount - 1)) }
    val grid = remember(sheetIndex) { GridState() }
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    var renameDialog by remember { mutableStateOf<Int?>(null) }
    var fillDialog by remember { mutableStateOf(false) }
    var colorDialog by remember { mutableStateOf(false) }
    var functionsDialog by remember { mutableStateOf(false) }
    var cellMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }

    val sheet = remember(sheetIndex, tick) { doc.sheet(sheetIndex.coerceIn(0, doc.sheetCount - 1)) }
    val sel = grid.selection
    val (cr, cc) = grid.anchor
    val modified = remember(tick, saveTick) { doc.isModified }

    fun refresh() {
        tick++
    }

    // The input line shows the selected cell's content whenever we are not typing.
    LaunchedEffect(cr, cc, sheetIndex, tick, editing) {
        if (!editing) input = TextFieldValue(sheet.inputText(cr, cc))
    }

    fun commit(moveDown: Boolean = true) {
        if (!editing) return
        val text = input.text
        if (text != sheet.inputText(cr, cc)) doc.setCellInput(sheetIndex, cr, cc, text)
        editing = false
        if (moveDown) {
            val m = sheet.mergeAt(cr, cc)
            grid.select((m?.r2 ?: cr) + 1, cc)
            grid.reveal = grid.anchor
        }
        refresh()
    }

    fun startEditing(initial: String? = null) {
        editing = true
        val t = initial ?: sheet.inputText(cr, cc)
        input = TextFieldValue(t, TextRange(t.length))
    }

    fun cancel() {
        editing = false
        focusManager.clearFocus()
        input = TextFieldValue(sheet.inputText(cr, cc))
    }

    session.flush = { if (editing) commit(moveDown = false) }

    fun style(change: StyleChange) {
        if (editing) commit(false)
        doc.applyStyle(sheetIndex, sel, change)
        refresh()
    }

    val current = sheet.cell(cr, cc)?.style

    val matches = remember(sheet, tick, query) {
        if (query.isEmpty()) emptyList() else buildList {
            for (r in 0..sheet.maxRow) for (c in 0..sheet.maxCol) {
                val cell = sheet.cell(r, c) ?: continue
                if (cell.display.contains(query, true) || (cell.formula?.contains(query, true) == true)) add(r to c)
            }
        }
    }

    fun goToMatch(i: Int) {
        if (matches.isEmpty()) return
        matchIndex = ((i % matches.size) + matches.size) % matches.size
        val (r, c) = matches[matchIndex]
        grid.select(r, c)
        grid.reveal = r to c
    }

    fun copy(cut: Boolean) {
        val sb = StringBuilder()
        for (r in sel.r1..sel.r2) {
            sb.append((sel.c1..sel.c2).joinToString("\t") { c -> sheet.cell(r, c)?.display ?: "" })
            if (r < sel.r2) sb.append('\n')
        }
        clipboard.setText(AnnotatedString(sb.toString()))
        if (cut) {
            doc.clearRange(sheetIndex, sel); refresh()
        }
    }

    fun paste() {
        val text = clipboard.getText()?.text ?: return
        val rows = text.trimEnd('\n', '\r').split(Regex("\r?\n"))
        for ((i, line) in rows.withIndex()) {
            for ((j, v) in line.split('\t').withIndex()) doc.setCellInput(sheetIndex, cr + i, cc + j, v)
        }
        refresh()
    }

    fun autoSum() {
        if (sel.rows > 1 || sel.cols > 1) {
            val target = if (sel.cols == 1 || sel.rows > 1) (sel.r2 + 1) to sel.c1 else sel.r1 to (sel.c2 + 1)
            doc.setCellInput(sheetIndex, target.first, target.second, "=SUM($sel)")
            grid.select(target.first, target.second)
            refresh()
        } else {
            // Sum the numbers directly above the cell.
            var r = cr - 1
            while (r >= 0 && sheet.value(r, cc) is XValue.Num) r--
            val range = if (r + 1 <= cr - 1) CellRef.name(r + 1, cc) + ":" + CellRef.name(cr - 1, cc) else ""
            startEditing("=SUM($range)")
            input = TextFieldValue(input.text, TextRange(input.text.length - 1))
        }
    }

    Scaffold(
        topBar = {
            if (searching) io.github.Prestgg.naroffice.ui.common.SearchBar(
                query, { query = it; matchIndex = 0 },
                status = if (query.isEmpty()) "" else if (matches.isEmpty()) stringResource(R.string.no_matches)
                else stringResource(R.string.matches, matchIndex + 1, matches.size),
                onPrev = { goToMatch(matchIndex - 1) },
                onNext = { goToMatch(matchIndex + 1) },
                onClose = { searching = false; query = "" },
            ) else EditorTopBar(
                title = session.name,
                kind = sd.kind,
                modified = modified,
                onBack = onBack,
                actions = {
                    IconButton({ if (editing) cancel(); doc.undo(); refresh() }, enabled = doc.history.canUndo) {
                        Icon(NarIcons.Undo, stringResource(R.string.undo))
                    }
                    IconButton({ doc.redo(); refresh() }, enabled = doc.history.canRedo) {
                        Icon(NarIcons.Redo, stringResource(R.string.redo))
                    }
                    if (modified) IconButton(onSave) { Icon(NarIcons.Save, stringResource(R.string.save)) }
                },
                menu = buildList {
                    add(MenuAction(stringResource(R.string.save), onSave))
                    add(MenuAction(stringResource(R.string.save_as), onSaveAs))
                    add(MenuAction(stringResource(R.string.share), onShare))
                    add(MenuAction(stringResource(R.string.find)) { searching = true })
                    add(MenuAction(stringResource(R.string.zoom_in)) { grid.zoom = (grid.zoom * 1.2f).coerceAtMost(3f) })
                    add(MenuAction(stringResource(R.string.zoom_out)) { grid.zoom = (grid.zoom / 1.2f).coerceAtLeast(0.4f) })
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            // Name box and formula line.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(72.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (sel.rows == 1 && sel.cols == 1) CellRef.name(cr, cc) else sel.toString(),
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("fx", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = SheetGreen,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(
                        if (editing) input.text else sheet.inputText(cr, cc),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).clickable { if (!editing) startEditing() }.padding(8.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                GridView(
                    sheet = sheet, state = grid, tick = tick,
                    onTap = { r, c -> if (editing) commit(false); grid.select(r, c) },
                    onDoubleTap = { r, c -> grid.select(r, c); startEditing() },
                    onLongPress = { r, c ->
                        if (editing) commit(false)
                        if (!sel.contains(r, c)) grid.select(r, c)
                        cellMenu = true
                    },
                    onSelectHeader = { r, c ->
                        if (editing) commit(false)
                        if (r != null) {
                            grid.anchor = r to 0; grid.focus = r to maxOf(sheet.maxCol, 25)
                        } else if (c != null) {
                            grid.anchor = 0 to c; grid.focus = maxOf(sheet.maxRow, 99) to c
                        }
                    },
                )
                DropdownMenu(cellMenu, { cellMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.copy)) }, { cellMenu = false; copy(false) })
                    DropdownMenuItem({ Text(stringResource(R.string.cut)) }, { cellMenu = false; copy(true) })
                    DropdownMenuItem({ Text(stringResource(R.string.paste)) }, { cellMenu = false; paste() })
                    DropdownMenuItem({ Text(stringResource(R.string.clear_cells)) }, {
                        cellMenu = false; doc.clearRange(sheetIndex, sel); refresh()
                    })
                    HorizontalDivider()
                    DropdownMenuItem({ Text(stringResource(R.string.insert_row_above)) }, {
                        cellMenu = false; doc.insertRows(sheetIndex, sel.r1, sel.rows); refresh()
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.insert_row_below)) }, {
                        cellMenu = false; doc.insertRows(sheetIndex, sel.r2 + 1, sel.rows); refresh()
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.delete_rows)) }, {
                        cellMenu = false; doc.deleteRows(sheetIndex, sel.r1, sel.rows); grid.select(sel.r1, sel.c1); refresh()
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.insert_col_left)) }, {
                        cellMenu = false; doc.insertColumns(sheetIndex, sel.c1, sel.cols); refresh()
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.insert_col_right)) }, {
                        cellMenu = false; doc.insertColumns(sheetIndex, sel.c2 + 1, sel.cols); refresh()
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.delete_cols)) }, {
                        cellMenu = false; doc.deleteColumns(sheetIndex, sel.c1, sel.cols); grid.select(sel.r1, sel.c1); refresh()
                    })
                }
            }

            if (editing) {
                InputBar(
                    value = input,
                    onValue = { input = it },
                    focus = focus,
                    onDone = { commit() },
                    onCancel = { cancel() },
                    onInsert = { s ->
                        val t = input.text
                        val st = input.selection.min
                        val nt = t.substring(0, st) + s + t.substring(input.selection.max)
                        input = TextFieldValue(nt, TextRange(st + s.length - if (s.endsWith("()")) 1 else 0))
                    },
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            } else {
                Column(Modifier.navigationBarsPadding()) {
                    if (sel.rows > 1 || sel.cols > 1) SelectionStats(sheet, sel)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolIcon(NarIcons.Function, stringResource(R.string.functions)) { functionsDialog = true }
                            ToolText("Σ", stringResource(R.string.sum), weight = FontWeight.Normal) { autoSum() }
                            ToolDivider()
                            ToolText("B", stringResource(R.string.bold), current?.bold == true) { style(StyleChange.Bold(current?.bold != true)) }
                            ToolText("I", stringResource(R.string.italic), current?.italic == true, italic = true) { style(StyleChange.Italic(current?.italic != true)) }
                            ToolText("U", stringResource(R.string.underline), current?.underline == true, underline = true) { style(StyleChange.Underline(current?.underline != true)) }
                            ToolDropdown(
                                (current?.fontSizePt ?: 11f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() },
                                width = 36,
                                items = listOf(8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 36f).map { s ->
                                    s.toInt().toString() to { style(StyleChange.FontSize(s)) }
                                },
                            )
                            IconButton({ colorDialog = true }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("A", style = MaterialTheme.typography.titleMedium)
                                    Box(Modifier.width(20.dp).height(4.dp).background(current?.fontColor?.let { Color(it) } ?: Color.Black))
                                }
                            }
                            ToolIcon(NarIcons.Fill, stringResource(R.string.fill_color), current?.fill != null) { fillDialog = true }
                            ToolDivider()
                            val align = current?.hAlign ?: HAlign.GENERAL
                            ToolIcon(NarIcons.AlignLeft, stringResource(R.string.align_left), align == HAlign.LEFT) { style(StyleChange.Align(HAlign.LEFT)) }
                            ToolIcon(NarIcons.AlignCenter, stringResource(R.string.align_center), align == HAlign.CENTER) { style(StyleChange.Align(HAlign.CENTER)) }
                            ToolIcon(NarIcons.AlignRight, stringResource(R.string.align_right), align == HAlign.RIGHT) { style(StyleChange.Align(HAlign.RIGHT)) }
                            ToolIcon(NarIcons.Wrap, "Wrap", current?.wrap == true) { style(StyleChange.Wrap(current?.wrap != true)) }
                            ToolIcon(NarIcons.Table, "Borders", current?.top?.present == true) { style(StyleChange.Borders(current?.top?.present != true)) }
                            ToolDivider()
                            ToolDropdown(
                                stringResource(R.string.number_format), width = 96,
                                items = listOf(
                                    stringResource(R.string.fmt_general) to { style(StyleChange.NumberFormat(NumFmtPreset.GENERAL.code)) },
                                    stringResource(R.string.fmt_number) to { style(StyleChange.NumberFormat(NumFmtPreset.DECIMAL2.code)) },
                                    stringResource(R.string.fmt_thousands) to { style(StyleChange.NumberFormat(NumFmtPreset.THOUSANDS.code)) },
                                    stringResource(R.string.fmt_percent) to { style(StyleChange.NumberFormat(NumFmtPreset.PERCENT.code)) },
                                    stringResource(R.string.fmt_date) to { style(StyleChange.NumberFormat(NumFmtPreset.DATE.code)) },
                                ),
                            )
                            ToolIcon(NarIcons.Copy, stringResource(R.string.copy)) { copy(false) }
                            ToolIcon(NarIcons.Paste, stringResource(R.string.paste)) { paste() }
                        }
                    }
                    SheetTabs(
                        names = doc.sheets.map { it.name },
                        hidden = doc.sheets.map { it.hidden },
                        current = sheetIndex,
                        onSelect = { sheetIndex = it },
                        onAdd = { sheetIndex = doc.addSheet(); refresh() },
                        onRename = { renameDialog = it },
                        onDelete = { i ->
                            if (doc.deleteSheet(i)) {
                                sheetIndex = sheetIndex.coerceAtMost(doc.sheetCount - 1); refresh()
                            }
                        },
                        canEdit = !sd.csv,
                    )
                }
            }
        }
    }

    renameDialog?.let { i ->
        TextInputDialog(stringResource(R.string.rename_sheet), doc.sheets[i].name, onDone = { name ->
            if (doc.renameSheet(i, name.trim())) refresh()
            renameDialog = null
        }, onDismiss = { renameDialog = null })
    }
    if (fillDialog) ColorDialog(stringResource(R.string.fill_color), stringResource(R.string.no_color),
        onPick = { style(StyleChange.Fill(it)); fillDialog = false }, onDismiss = { fillDialog = false })
    if (colorDialog) ColorDialog(stringResource(R.string.text_color), stringResource(R.string.automatic),
        onPick = { style(StyleChange.FontColor(it)); colorDialog = false }, onDismiss = { colorDialog = false })
    if (functionsDialog) FunctionsDialog(
        onPick = { f ->
            functionsDialog = false
            val arg = if (sel.rows > 1 || sel.cols > 1) sel.toString() else ""
            startEditing("=$f($arg)")
            input = TextFieldValue(input.text, TextRange(input.text.length - 1))
        },
        onDismiss = { functionsDialog = false },
    )
}

@Composable
private fun InputBar(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onInsert: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.navigationBarsPadding().imePadding()) {
        Column {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                for (s in listOf("=", "SUM()", "AVERAGE()", "IF()", ":", ";", "(", ")", "+", "-", "*", "/", "&", "\"", "$", "%")) {
                    Box(
                        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onInsert(s) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text(s.removeSuffix("()"), style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onCancel) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                BasicTextField(
                    value, onValue,
                    textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(SheetGreen),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, SheetGreen, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .focusRequester(focus),
                )
                IconButton(onDone) { Icon(Icons.Default.Check, stringResource(R.string.done), tint = SheetGreen) }
            }
        }
    }
}

@Composable
private fun SelectionStats(sheet: io.github.Prestgg.naroffice.xlsx.Sheet, sel: CellRange) {
    val stats = remember(sheet, sel, sheet.generation) {
        var sum = 0.0
        var n = 0
        var count = 0
        val r2 = minOf(sel.r2, sheet.maxRow)
        val c2 = minOf(sel.c2, sheet.maxCol)
        for (r in sel.r1..r2) for (c in sel.c1..c2) {
            when (val v = sheet.value(r, c)) {
                is XValue.Num -> { sum += v.v; n++; count++ }
                is XValue.Empty -> {}
                else -> count++
            }
        }
        Triple(sum, n, count)
    }
    fun fmt(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else "%.4g".format(d)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "${stringResource(R.string.sum)}: ${fmt(stats.first)}   " +
                    "${stringResource(R.string.average)}: ${if (stats.second > 0) fmt(stats.first / stats.second) else "–"}   " +
                    "${stringResource(R.string.count)}: ${stats.third}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Default,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetTabs(
    names: List<String>,
    hidden: List<Boolean>,
    current: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRename: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    canEdit: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (canEdit) IconButton(onAdd) { Icon(Icons.Default.Add, stringResource(R.string.add_sheet)) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                for ((i, name) in names.withIndex()) {
                    if (hidden[i]) continue
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        val selected = i == current
                        Text(
                            name,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) SheetGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 160.dp)
                                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                .combinedClickable(onClick = { onSelect(i) }, onLongClick = { if (canEdit) menu = true })
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; onRename(i) })
                            if (names.size > 1) DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { menu = false; onDelete(i) })
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

private val FUNCTIONS = listOf(
    "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "COUNTA", "IF", "IFERROR", "ROUND", "SUMIF", "COUNTIF",
    "AVERAGEIF", "VLOOKUP", "HLOOKUP", "INDEX", "MATCH", "AND", "OR", "NOT", "ABS", "SQRT", "POWER", "MOD",
    "PRODUCT", "INT", "LEN", "LEFT", "RIGHT", "MID", "UPPER", "LOWER", "TRIM", "CONCAT", "TEXT", "VALUE",
    "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY",
)

@Composable
private fun FunctionsDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.functions)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(400.dp)) {
                items(FUNCTIONS.size) { i ->
                    Text(
                        FUNCTIONS[i],
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(FUNCTIONS[i]) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
