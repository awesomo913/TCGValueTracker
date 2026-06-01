package com.owner.assist.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences wrapper for API keys + the LLM provider toggle.
 *
 * Keys are AES256-GCM encrypted at rest with a key tied to the Android Keystore.
 *
 * If the Android Keystore is corrupted or unavailable (rare, but documented), [prefs]
 * is null and all reads return defaults / writes are dropped with a logged warning.
 * Callers should check [isAvailable] and surface "secure storage unavailable" in UI.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences? = try {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed: ${e.javaClass.simpleName} ${e.message}", e)
        null
    }

    val isAvailable: Boolean get() = prefs != null

    var deepgramKey: String
        get() = read(KEY_DEEPGRAM)
        set(value) = write(KEY_DEEPGRAM, value)

    var groqKey: String
        get() = read(KEY_GROQ)
        set(value) = write(KEY_GROQ, value)

    var deepseekKey: String
        get() = read(KEY_DEEPSEEK)
        set(value) = write(KEY_DEEPSEEK, value)

    var llmProvider: LlmChoice
        get() = LlmChoice.fromId(prefs?.getString(KEY_LLM_CHOICE, LlmChoice.GROQ.id))
        set(value) {
            prefs?.edit()?.putString(KEY_LLM_CHOICE, value.id)?.apply()
                ?: Log.w(TAG, "drop write llmProvider — keystore unavailable")
        }

    var wakeWordEnabled: Boolean
        get() = prefs?.getBoolean(KEY_WAKE_WORD, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_WAKE_WORD, value)?.apply()
                ?: Log.w(TAG, "drop write wakeWord — keystore unavailable")
        }

    fun keysComplete(): Boolean {
        if (prefs == null) return false
        val llmKey = if (llmProvider == LlmChoice.GROQ) groqKey else deepseekKey
        return deepgramKey.isNotBlank() && llmKey.isNotBlank()
    }

    private fun read(key: String): String =
        prefs?.getString(key, "").takeIf { !it.isNullOrBlank() } ?: DEFAULTS[key] ?: ""

    private fun write(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
            ?: Log.w(TAG, "drop write $key — keystore unavailable")
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val PREFS_NAME = "assist_secure_prefs"
        private const val KEY_DEEPGRAM = "deepgram_key"
        private const val KEY_GROQ = "groq_key"
        private const val KEY_DEEPSEEK = "deepseek_key"
        private const val KEY_LLM_CHOICE = "llm_choice"
        private const val KEY_WAKE_WORD = "wake_word_enabled"
        private val DEFAULTS = mapOf(
            KEY_DEEPGRAM to "",
            KEY_GROQ to "",
        )
    }
}

enum class LlmChoice(val id: String, val display: String) {
    GROQ("groq", "Groq (Llama 3.3 70B)"),
    DEEPSEEK("deepseek", "DeepSeek");

    companion object {
        fun fromId(id: String?): LlmChoice = entries.firstOrNull { it.id == id } ?: GROQ
    }
}
