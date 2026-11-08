// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.ui.KindBadge

class MenuAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    title: String,
    kind: DocKind,
    modified: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    menu: List<MenuAction>,
) {
    var open by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(kind, 24)
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (modified) "• " else "") + title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        actions = {
            actions()
            Box {
                IconButton({ open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(open, { open = false }) {
                    for (m in menu) DropdownMenuItem({ Text(m.label) }, { open = false; m.onClick() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** A search field shown in place of the top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    status: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
        title = {
            TextField(
                query, onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.find)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            Text(status, style = MaterialTheme.typography.labelMedium)
            IconButton(onPrev) { Icon(Icons.Default.KeyboardArrowUp, null) }
            IconButton(onNext) { Icon(Icons.Default.KeyboardArrowDown, null) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** A horizontally scrolling formatting toolbar that sits above the keyboard. */
@Composable
fun FormatBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun ToolIcon(icon: ImageVector, label: String, checked: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconToggleButton(checked, { onClick() }, enabled = enabled) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(22.dp)) }
    }
}

@Composable
fun ToolText(text: String, label: String, checked: Boolean = false, weight: FontWeight = FontWeight.Bold,
             italic: Boolean = false, underline: Boolean = false, strike: Boolean = false, onClick: () -> Unit) {
    IconToggleButton(checked, { onClick() }) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .background(if (checked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                fontWeight = weight,
                fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                textDecoration = when {
                    underline -> androidx.compose.ui.text.style.TextDecoration.Underline
                    strike -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else -> null
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun ToolDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A labelled dropdown button for the toolbar. */
@Composable
fun ToolDropdown(label: String, width: Int = 96, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.wrapContentSize()) {
        Row(
            Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp).width(width.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }) {
            for ((l, f) in items) DropdownMenuItem({ Text(l) }, { open = false; f() })
        }
    }
}

val PALETTE = listOf(
    0xFF000000, 0xFF434343, 0xFF888888, 0xFFCCCCCC, 0xFFFFFFFF,
    0xFFB5202E, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00897B, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFD81B60,
    0xFFFFCDD2, 0xFFFFE0B2, 0xFFFFF9C4, 0xFFC8E6C9, 0xFFBBDEFB,
).map { it.toInt() }

/** A palette dialog; picks null for "automatic"/"none". */
@Composable
fun ColorDialog(title: String, noneLabel: String, colors: List<Int> = PALETTE, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth()) {
                items(colors) { c ->
                    Box(
                        Modifier.padding(6.dp).size(40.dp).clip(CircleShape).background(Color(c))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onPick(c) },
                    )
                }
            }
        },
        confirmButton = { TextButton({ onPick(null) }) { Text(noneLabel) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Simple dialog asking for one line of text. */
@Composable
fun TextInputDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.focusRequester(focus)) },
        confirmButton = { TextButton({ onDone(v) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
