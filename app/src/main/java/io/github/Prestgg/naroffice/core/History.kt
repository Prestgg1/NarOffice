// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.Prestgg.naroffice.core

import io.github.Prestgg.naroffice.xml.XElement
import io.github.Prestgg.naroffice.xml.XNode

/**
 * Undo history made of splices on XML child lists. Removed and inserted nodes are never
 * mutated after the fact, so each step only stores references.
 */
class History(private val limit: Int = 200) {

    interface Step {
        fun undo()
        fun redo()
    }

    class Splice(
        val container: XElement,
        val index: Int,
        val removed: List<XNode>,
        val inserted: List<XNode>,
        val onChange: () -> Unit = {},
    ) : Step {
        override fun undo() {
            repeat(inserted.size) { container.children.removeAt(index) }
            container.children.addAll(index, removed)
            onChange()
        }

        override fun redo() {
            repeat(removed.size) { container.children.removeAt(index) }
            container.children.addAll(index, inserted)
            onChange()
        }
    }

    class Group(val steps: List<Step>) : Step {
        override fun undo() = steps.asReversed().forEach { it.undo() }
        override fun redo() = steps.forEach { it.redo() }
    }

    /** A step backed by plain lambdas, for state that is not an XML child list. */
    class Lambda(private val u: () -> Unit, private val r: () -> Unit) : Step {
        override fun undo() = u()
        override fun redo() = r()
    }

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Number of changes since the last save; negative when undone past it. */
    var changeCounter = 0
        private set

    fun push(step: Step) {
        undoStack.addLast(step)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        changeCounter++
    }

    /** Performs a splice and records it. */
    fun splice(container: XElement, index: Int, removeCount: Int, insert: List<XNode>, onChange: () -> Unit = {}) {
        val removed = ArrayList(container.children.subList(index, index + removeCount))
        val step = Splice(container, index, removed, insert, onChange)
        step.redo()
        push(step)
    }

    fun undo(): Boolean {
        val s = undoStack.removeLastOrNull() ?: return false
        s.undo()
        redoStack.addLast(s)
        changeCounter--
        return true
    }

    fun redo(): Boolean {
        val s = redoStack.removeLastOrNull() ?: return false
        s.redo()
        undoStack.addLast(s)
        changeCounter++
        return true
    }

    fun markSaved() {
        changeCounter = 0
    }
}
