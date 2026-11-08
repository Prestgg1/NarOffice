// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.core

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** A document opened in an editor, independent of its format. */
abstract class OfficeDoc {
    abstract val kind: DocKind
    abstract val isModified: Boolean
    abstract fun save(out: OutputStream)
    open fun markSaved() {}
    fun toBytes(): ByteArray = ByteArrayOutputStream().also { save(it) }.toByteArray()
}

class Session(
    var uri: Uri?,
    var name: String,
    val doc: OfficeDoc,
) {
    val kind get() = doc.kind

    /** Set by the editor: writes any in-progress edit into the document model. */
    var flush: () -> Unit = {}
}
