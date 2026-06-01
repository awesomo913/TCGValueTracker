package com.owner.assist.net

import kotlinx.coroutines.flow.Flow

class DeepSeekLlmClient(private val apiKey: String) : LlmProvider {

    override fun stream(systemPrompt: String, userMessage: String): Flow<String> =
        OpenAiCompatStream.stream(
            endpoint = ENDPOINT,
            apiKey = apiKey,
            model = MODEL,
            systemPrompt = systemPrompt,
            userMessage = userMessage,
        )

    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-chat"
    }
}
