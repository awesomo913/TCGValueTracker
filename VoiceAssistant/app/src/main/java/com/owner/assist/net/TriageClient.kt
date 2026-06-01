package com.owner.assist.net

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fast binary classifier using Groq llama-3.1-8b-instant (~500 tok/s).
 *
 * Sits between the speaker filter and the full 70B response model. Decides
 * in ~100-200ms whether the overheard utterance is worth whispering about.
 * Defaults to SKIP on timeout or error — better to stay silent than to spam.
 */
object TriageClient {

    private const val TAG = "TriageClient"
    private const val TIMEOUT_MS = 2_500L
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.1-8b-instant"

    private const val PROMPT =
        "You classify overheard speech. Reply with exactly one word: RESPOND or SKIP.\n" +
        "RESPOND if: a direct question was asked, a factual claim was made, " +
        "an instruction was given, a decision is being discussed, or someone needs information.\n" +
        "SKIP if: small talk, filler words, incomplete thought, greeting, laughter, or chitchat."

    /**
     * Returns true if the overheard [utterance] warrants a whispered response.
     * Requires a valid [groqApiKey]. Returns false if the API key is blank.
     */
    suspend fun shouldRespond(utterance: String, groqApiKey: String): Boolean {
        if (utterance.length < 5 || groqApiKey.isBlank()) return false

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            val buf = StringBuilder()
            runCatching {
                OpenAiCompatStream.stream(
                    endpoint = ENDPOINT,
                    apiKey = groqApiKey,
                    model = MODEL,
                    systemPrompt = PROMPT,
                    userMessage = utterance,
                    temperature = 0.0,
                    maxTokens = 10,
                ).collect { delta -> buf.append(delta) }
            }.onFailure { Log.w(TAG, "triage failed: ${it.message}") }
            buf.toString().trim().uppercase().startsWith("RESPOND")
        }

        if (result == null) {
            Log.w(TAG, "triage timeout — defaulting SKIP")
            return false
        }
        Log.d(TAG, "triage='${result}'")
        return result
    }
}
