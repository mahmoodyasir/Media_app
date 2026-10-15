package com.mediaapp.data.vault

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class VaultSession @Inject constructor() {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    @Volatile var suppressAutoLock: Boolean = false

    private var failures = 0
    private var lockedUntil = 0L

    fun unlock() {
        failures = 0
        lockedUntil = 0L
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }

    fun cooldownRemaining(): Long = (lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerFailure() {
        failures += 1
        if (failures >= 5) {
            lockedUntil = System.currentTimeMillis() + 30_000L
            failures = 0
        }
    }
}
