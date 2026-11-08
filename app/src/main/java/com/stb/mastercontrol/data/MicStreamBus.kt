package com.stb.mastercontrol.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny shared bus between MicStreamService (the writer) and MainViewModel
 * (the UI reader). Keeps "is the mic live?" consistent across service
 * restarts without exposing the service instance.
 */
object MicStreamBus {

    data class MicState(
        /** `true` while the foreground service is capturing and streaming. */
        val streaming: Boolean = false,
        /** Remote PC address the audio is being sent to. */
        val target: String = "",
        /** `true` during an auto-stopping short burst capture. */
        val burst: Boolean = false
    )

    private val _state = MutableStateFlow(MicState())
    val state: StateFlow<MicState> = _state.asStateFlow()

    fun setStreaming(streaming: Boolean, target: String = "", burst: Boolean = false) {
        _state.value = MicState(
            streaming = streaming,
            target = if (streaming) target else "",
            burst = if (streaming) burst else false
        )
    }
}
