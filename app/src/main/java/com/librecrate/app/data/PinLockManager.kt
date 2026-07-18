package com.librecrate.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object PinLockManager {
    var isLocked by mutableStateOf(false)
        private set
    fun lock() { isLocked = true }
    fun unlock() { isLocked = false }
}
