package com.ethran.notable.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncViewModel : ViewModel() {
    private val _syncConflictDetected = MutableStateFlow(false)
    val syncConflictDetected = _syncConflictDetected.asStateFlow()

    fun setSyncConflict(hasConflict: Boolean) {
        _syncConflictDetected.value = hasConflict
    }
}