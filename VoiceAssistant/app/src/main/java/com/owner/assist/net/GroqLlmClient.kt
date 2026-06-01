package com.owner.assist.net

import kotlinx.coroutines.flow.Flow

class GroqLlmClient(private val apiKey: String) : LlmProvider {

    override fun stream(systemPrompt: String, userMessage: String): Flow<String> =
        OpenAiCompatStream.stream(
            endpoint = ENDPOINT,
            apiKey = apiKey,
            model = MODEL,
            systemPrompt = systemPrompt,
            userMessage = userMessage,
        )

    companion object {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        // Llama 3.3 70B Versatile — ~275 tok/s, TTFT typically <250ms.
        // If the model name changes, swap here only.
        private const val MODEL = "llama-3.3-70b-versatile"
    }
}
