package com.owner.assist.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class AssistantState { OFF, LISTENING, THINKING, SPEAKING }

/**
 * Process-global state so the UI, tile, and service share a single source of truth.
 * Process-global is fine here: only one service instance runs at a time.
 */
object AssistantStateBus {
    private val _state = MutableStateFlow(AssistantState.OFF)
    val state: StateFlow<AssistantState> = _state

    /** 0–1 normalised mic energy: 0 = silence, 1 = very loud. Drives the UI level ring. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    /** True while the session-recorder is writing to a WAV file. */
    private val _isSessionRecording = MutableStateFlow(false)
    val isSessionRecording: StateFlow<Boolean> = _isSessionRecording

    /** Path returned when session recording finishes. */
    private val _sessionSavedPath = MutableStateFlow("")
    val sessionSavedPath: StateFlow<String> = _sessionSavedPath

    /** True while recording audio to build a questioner speaker profile. */
    private val _isTuningQuestioner = MutableStateFlow(false)
    val isTuningQuestioner: StateFlow<Boolean> = _isTuningQuestioner

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    fun set(s: AssistantState) { _state.value = s }

    fun setAudioLevel(level: Float) { _audioLevel.value = level.coerceIn(0f, 1f) }

    fun setSessionRecording(active: Boolean) { _isSessionRecording.value = active }

    fun setSessionSavedPath(path: String) { _sessionSavedPath.value = path }

    fun addEvent(msg: String) {
        _events.update { current -> (listOf(msg) + current).take(8) }
    }

    fun setTuningQuestioner(active: Boolean) { _isTuningQuestioner.value = active }
}
