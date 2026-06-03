package com.owner.assist.net

import android.util.Log
import com.owner.assist.data.LlmChoice
import com.owner.assist.data.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException

interface LlmProvider {
    fun stream(systemPrompt: String, userMessage: String): Flow<String>
}

/**
 * Tries [primary]; on any non-cancellation failure falls back to [backup].
 * Calls [onFallback] with a short reason string when switching.
 */
class FallbackLlmProvider(
    private val primary: LlmProvider,
    private val backup: LlmProvider,
    private val onFallback: (String) -> Unit,
) : LlmProvider {
    override fun stream(systemPrompt: String, userMessage: String): Flow<String> = flow {
        try {
            primary.stream(systemPrompt, userMessage).collect { emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FallbackLlm", "primary failed (${e.message?.take(40)}) — switching to backup")
            onFallback("LLM fallback: ${e.message?.take(40) ?: "error"}")
            backup.stream(systemPrompt, userMessage).collect { emit(it) }
        }
    }
}

object LlmProviderFactory {
    fun fromSettings(store: SecureKeyStore): LlmProvider = when (store.llmProvider) {
        LlmChoice.GROQ -> GroqLlmClient(store.groqKey)
        LlmChoice.DEEPSEEK -> DeepSeekLlmClient(store.deepseekKey)
    }

    /** Wraps the primary provider with an automatic fallback to the other key (if set). */
    fun fromSettingsWithFallback(store: SecureKeyStore, onFallback: (String) -> Unit): LlmProvider {
        val primary = fromSettings(store)
        val backupChoice = if (store.llmProvider == LlmChoice.GROQ) LlmChoice.DEEPSEEK else LlmChoice.GROQ
        val backupKey = if (backupChoice == LlmChoice.GROQ) store.groqKey else store.deepseekKey
        if (backupKey.isBlank()) return primary
        val backup: LlmProvider = when (backupChoice) {
            LlmChoice.GROQ -> GroqLlmClient(store.groqKey)
            LlmChoice.DEEPSEEK -> DeepSeekLlmClient(store.deepseekKey)
        }
        return FallbackLlmProvider(primary, backup, onFallback)
    }
}
