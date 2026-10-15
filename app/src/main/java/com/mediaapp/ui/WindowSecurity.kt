package com.mediaapp.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reference-counted FLAG_SECURE. Vault routes acquire it so screenshots,
 * recents thumbnails, and non-secure displays cannot capture locked media.
 */
object WindowSecurity {
    private val _secure = MutableStateFlow(false)
    val secure: StateFlow<Boolean> = _secure.asStateFlow()
    private var holders = 0

    fun acquire() {
        holders += 1
        _secure.value = true
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        _secure.value = holders > 0
    }
}
