// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.core

import android.net.Uri
import java.io.OutputStream

/** A document opened in an editor, independent of its format. */
abstract class OfficeDoc {
    abstract val kind: DocKind
    abstract val isModified: Boolean
    abstract fun save(out: OutputStream)
    open fun markSaved() {}

    /** Releases the file the document reads from. */
    open fun close() {}
}

class Session(
    var uri: Uri?,
    var name: String,
    val doc: OfficeDoc,
    /** Local copy of the opened file; parts are read from it lazily. */
    val tempFile: java.io.File? = null,
) {
    fun close() {
        doc.close()
        tempFile?.delete()
    }

    val kind get() = doc.kind

    /** Set by the editor: writes any in-progress edit into the document model. */
    var flush: () -> Unit = {}
}
