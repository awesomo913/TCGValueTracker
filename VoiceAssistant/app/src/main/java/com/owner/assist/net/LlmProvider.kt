package com.owner.assist.net

import com.owner.assist.data.LlmChoice
import com.owner.assist.data.SecureKeyStore
import kotlinx.coroutines.flow.Flow

/**
 * Streaming chat-completion interface. Implementations emit tokens (or token groups) as they arrive.
 */
interface LlmProvider {
    /** Streams the assistant's reply as text fragments. Concatenated == full reply. */
    fun stream(systemPrompt: String, userMessage: String): Flow<String>
}

object LlmProviderFactory {
    fun fromSettings(store: SecureKeyStore): LlmProvider = when (store.llmProvider) {
        LlmChoice.GROQ -> GroqLlmClient(store.groqKey)
        LlmChoice.DEEPSEEK -> DeepSeekLlmClient(store.deepseekKey)
    }
}
