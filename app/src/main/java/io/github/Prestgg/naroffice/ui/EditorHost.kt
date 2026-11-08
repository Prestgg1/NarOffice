// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.ui

import androidx.compose.runtime.Composable
import io.github.Prestgg.naroffice.core.Session
import io.github.Prestgg.naroffice.ui.docx.DocxEditor

@Composable
fun EditorHost(
    session: Session,
    saveTick: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: () -> Unit,
) {
    when (val d = session.doc) {
        is WordDoc -> DocxEditor(session, d.doc, saveTick, onBack, onSave, onSaveAs, onShare)
        is io.github.Prestgg.naroffice.ui.xlsx.SheetDoc ->
            io.github.Prestgg.naroffice.ui.xlsx.XlsxEditor(session, d, saveTick, onBack, onSave, onSaveAs, onShare)
    }
}
