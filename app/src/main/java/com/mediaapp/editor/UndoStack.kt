package com.mediaapp.editor

class UndoStack<T>(initial: T) {
    private val back = ArrayDeque<T>()
    private val forward = ArrayDeque<T>()
    var current: T = initial
        private set
    private var gestureBase: T? = null

    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    fun push(next: T) {
        if (next == current) return
        back.addLast(current)
        while (back.size > MAX) back.removeFirst()
        forward.clear()
        current = next
    }

    fun undo(): Boolean {
        val previous = back.removeLastOrNull() ?: return false
        forward.addLast(current)
        current = previous
        return true
    }

    fun redo(): Boolean {
        val next = forward.removeLastOrNull() ?: return false
        back.addLast(current)
        current = next
        return true
    }

    fun beginGesture() {
        if (gestureBase == null) gestureBase = current
    }

    fun preview(next: T) {
        current = next
    }

    fun endGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        val next = current
        current = base
        push(next)
    }

    fun reset(next: T) {
        back.clear()
        forward.clear()
        gestureBase = null
        current = next
    }

    private companion object {
        const val MAX = 40
    }
}
