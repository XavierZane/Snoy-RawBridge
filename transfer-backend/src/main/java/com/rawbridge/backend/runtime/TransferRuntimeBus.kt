package com.rawbridge.backend.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object TransferRuntimeBus {
    private val _state = MutableStateFlow(ReceiverRuntimeState())
    val state: StateFlow<ReceiverRuntimeState> = _state.asStateFlow()

    fun publish(state: ReceiverRuntimeState) {
        _state.value = state
    }
}
