// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.Prestgg.naroffice.BuildConfig
import io.github.Prestgg.naroffice.R
import io.github.Prestgg.naroffice.core.DocKind
import io.github.Prestgg.naroffice.core.RecentFile

fun kindColor(kind: DocKind): Color = when (kind) {
    DocKind.WORD -> WordBlue
    DocKind.SHEET, DocKind.CSV -> SheetGreen
    DocKind.SLIDES -> SlideOrange
}

fun kindLetter(kind: DocKind): String = when (kind) {
    DocKind.WORD -> "W"
    DocKind.SHEET -> "X"
    DocKind.CSV -> "C"
    DocKind.SLIDES -> "P"
}

@Composable
fun KindBadge(kind: DocKind, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(kindColor(kind)),
        contentAlignment = Alignment.Center,
    ) {
        Text(kindLetter(kind), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.45f).sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    recents: List<RecentFile>,
    onOpen: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onNew: (DocKind) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
    onPinRecent: (RecentFile) -> Unit,
    onClearRecent: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NarLogo(28)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.clear_recent)) }, { menu = false; onClearRecent() })
                        DropdownMenuItem({ Text(stringResource(R.string.about)) }, { menu = false; about = true })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpen,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.open_file)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.create_new),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NewCard(DocKind.WORD, stringResource(R.string.new_document), Modifier.weight(1f)) { onNew(DocKind.WORD) }
                    NewCard(DocKind.SHEET, stringResource(R.string.new_sheet), Modifier.weight(1f)) { onNew(DocKind.SHEET) }
                    NewCard(DocKind.SLIDES, stringResource(R.string.new_slides), Modifier.weight(1f)) { onNew(DocKind.SLIDES) }
                }
                Text(
                    stringResource(R.string.recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                )
            }
            if (recents.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_recent),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            items(recents, key = { it.uri }) { r ->
                var itemMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onOpenRecent(r) }, onLongClick = { itemMenu = true })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindBadge(r.kind)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                DateUtils.getRelativeTimeSpanString(r.time).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.pinned) Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        IconButton({ itemMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    }
                    DropdownMenu(itemMenu, { itemMenu = false }, Modifier.align(Alignment.TopEnd)) {
                        DropdownMenuItem(
                            { Text(stringResource(if (r.pinned) R.string.unpin else R.string.pin)) },
                            { itemMenu = false; onPinRecent(r) },
                        )
                        DropdownMenuItem({ Text(stringResource(R.string.remove_from_list)) }, { itemMenu = false; onRemoveRecent(r) })
                    }
                }
            }
        }
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            icon = { NarLogo(48) },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME)) },
            confirmButton = { TextButton({ about = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun NewCard(kind: DocKind, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KindBadge(kind, 44)
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun NarLogo(size: Int) {
    val ctx = LocalContext.current
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_logo),
        contentDescription = null,
        modifier = Modifier.size(size.dp),
    )
}
