// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.Prestgg.naroffice.pptx.PptxDocument
import io.github.Prestgg.naroffice.text.Align
import io.github.Prestgg.naroffice.ui.pptx.ShapeTextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTextEditTest {
    @Test
    fun typeSplitMergeAndCommit() {
        val doc = PptxDocument.blank()
        val shape = doc.slide(0).shapes.first { it.editable && it.placeholder != null }
        val te = ShapeTextEdit(doc, 0, shape.id)
        te.onValueChange(TextFieldValue("Salam", TextRange(5)))
        te.onValueChange(TextFieldValue("Salam\ndünya", TextRange(11)))
        assertEquals(2, te.paragraphs().size)
        te.setAlign(Align.CENTER)
        te.onValueChange(TextFieldValue("Salam\ndünya\n", TextRange(12)))
        assertEquals(3, te.paragraphs().size)
        // The new paragraph inherits the centred alignment.
        assertEquals(Align.CENTER, te.paragraphs()[2].align)
        te.onValueChange(TextFieldValue("Salamdünya", TextRange(5)))
        assertEquals(listOf("Salamdünya"), te.paragraphs().map { it.text })
        te.onValueChange(TextFieldValue("Salam dünya", TextRange(11)))
        te.onValueChange(te.value.copy(selection = TextRange(0, 5)))
        te.format { it.bold(true) }
        assertTrue(te.currentProps()!!.bold)
        assertTrue(te.commit())
        val again = PptxDocument.open(java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray())
        val text = again.slide(0).shapes.first { it.id == shape.id }.text!!
        assertEquals("Salam dünya", text.paras.joinToString("\n") { it.text })
        assertTrue(text.paras[0].runs.first().props.bold)
        assertTrue(doc.undo())
    }
}
