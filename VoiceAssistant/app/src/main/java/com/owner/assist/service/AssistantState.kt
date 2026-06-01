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

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    fun set(s: AssistantState) {
        _state.value = s
    }

    fun addEvent(msg: String) {
        _events.update { current -> (listOf(msg) + current).take(6) }
    }
}
