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

    /** Free-text domain context shown to the LLM as part of its system prompt.
     *  Blank = use the built-in forklift/technician default. */
    var contextBlurb: String
        get() = prefs?.getString(KEY_CONTEXT_BLURB, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CONTEXT_BLURB, value)?.apply()
                ?: Log.w(TAG, "drop write contextBlurb — keystore unavailable")
        }

    /**
     * 0 = most responsive (sends each sentence immediately, ~680ms gaps between sentences).
     * 100 = smoothest (buffers all sentences, no gaps, slowest start).
     * Maps to sentence-batch size: (level / 10) + 1.
     */
    var responsivenessLevel: Int
        get() = prefs?.getInt(KEY_RESPONSIVENESS, 25) ?: 25
        set(value) {
            prefs?.edit()?.putInt(KEY_RESPONSIVENESS, value.coerceIn(0, 100))?.apply()
                ?: Log.w(TAG, "drop write responsivenessLevel — keystore unavailable")
        }

    /** Max seconds the LLM is allowed to stream before being cut off. */
    var maxThinkTimeSec: Int
        get() = prefs?.getInt(KEY_MAX_THINK_SEC, 30) ?: 30
        set(value) {
            prefs?.edit()?.putInt(KEY_MAX_THINK_SEC, value.coerceIn(5, 60))?.apply()
                ?: Log.w(TAG, "drop write maxThinkTimeSec — keystore unavailable")
        }

    /** Absolute path to the saved voice calibration WAV file. Empty if none recorded yet. */
    var voiceCalibrationPath: String
        get() = prefs?.getString(KEY_CALIBRATION_PATH, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CALIBRATION_PATH, value)?.apply()
                ?: Log.w(TAG, "drop write voiceCalibrationPath — keystore unavailable")
        }

    /** PASSIVE = respond to others in the room. PERSONAL = respond to the user's own voice. */
    var assistantMode: AssistantMode
        get() = AssistantMode.fromId(prefs?.getString(KEY_ASSISTANT_MODE, AssistantMode.PASSIVE.id))
        set(value) {
            prefs?.edit()?.putString(KEY_ASSISTANT_MODE, value.id)?.apply()
                ?: Log.w(TAG, "drop write assistantMode — keystore unavailable")
        }

    /** Master switch for the vision / camera feature. */
    var visionEnabled: Boolean
        get() = prefs?.getBoolean(KEY_VISION_ENABLED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_VISION_ENABLED, value)?.apply()
                ?: Log.w(TAG, "drop write visionEnabled — keystore unavailable")
        }

    /** Word or phrase the assistant listens for before responding. Blank = respond to everything. */
    var customCallWord: String
        get() = prefs?.getString(KEY_CUSTOM_CALL_WORD, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CUSTOM_CALL_WORD, value)?.apply()
                ?: Log.w(TAG, "drop write customCallWord — keystore unavailable")
        }

    /** When true, capture a frame every 15s and log detections to AppLogger (no speech). */
    var visionAlwaysOn: Boolean
        get() = prefs?.getBoolean(KEY_VISION_ALWAYS_ON, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_VISION_ALWAYS_ON, value)?.apply()
                ?: Log.w(TAG, "drop write visionAlwaysOn — keystore unavailable")
        }

    /** When true, intercept Meta Ray-Ban touchpad events. Default OFF — requires Meta View disabled. */
    var glassesButtonsEnabled: Boolean
        get() = prefs?.getBoolean(KEY_GLASSES_BUTTONS, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_GLASSES_BUTTONS, value)?.apply()
                ?: Log.w(TAG, "drop write glassesButtonsEnabled — keystore unavailable")
        }

    var responseStyle: ResponseStyle
        get() = ResponseStyle.fromId(prefs?.getString(KEY_RESPONSE_STYLE, ResponseStyle.STANDARD.id))
        set(value) {
            prefs?.edit()?.putString(KEY_RESPONSE_STYLE, value.id)?.apply()
                ?: Log.w(TAG, "drop write responseStyle — keystore unavailable")
        }

    var personalityMode: PersonalityMode
        get() = PersonalityMode.fromId(prefs?.getString(KEY_PERSONALITY_MODE, PersonalityMode.DEFAULT.id))
        set(value) {
            prefs?.edit()?.putString(KEY_PERSONALITY_MODE, value.id)?.apply()
                ?: Log.w(TAG, "drop write personalityMode — keystore unavailable")
        }

    /** Custom personality text — used when personalityMode == CUSTOM. */
    var customPersonality: String
        get() = prefs?.getString(KEY_CUSTOM_PERSONALITY, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_CUSTOM_PERSONALITY, value)?.apply()
                ?: Log.w(TAG, "drop write customPersonality — keystore unavailable")
        }

    var stopKey: StopKey
        get() = StopKey.fromId(prefs?.getString(KEY_STOP_KEY, StopKey.NONE.id))
        set(value) {
            prefs?.edit()?.putString(KEY_STOP_KEY, value.id)?.apply()
                ?: Log.w(TAG, "drop write stopKey — keystore unavailable")
        }

    /** MAC address of the preferred BT mic. Blank = use any available BT SCO device. */
    var btMicAddress: String
        get() = prefs?.getString(KEY_BT_MIC_ADDRESS, "") ?: ""
        set(value) {
            prefs?.edit()?.putString(KEY_BT_MIC_ADDRESS, value)?.apply()
                ?: Log.w(TAG, "drop write btMicAddress — keystore unavailable")
        }

    /** Auto-save a 30-second rolling clip every 30s while the service is running. */
    var autoClipEnabled: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_CLIP, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_AUTO_CLIP, value)?.apply()
                ?: Log.w(TAG, "drop write autoClipEnabled — keystore unavailable")
        }

    /** Save a 10-second clip for each detected non-self speaker (up to 3). */
    var listenerClipsEnabled: Boolean
        get() = prefs?.getBoolean(KEY_LISTENER_CLIPS, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_LISTENER_CLIPS, value)?.apply()
                ?: Log.w(TAG, "drop write listenerClipsEnabled — keystore unavailable")
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
        private const val KEY_CONTEXT_BLURB = "context_blurb"
        private const val KEY_RESPONSIVENESS = "responsiveness_level"
        private const val KEY_MAX_THINK_SEC = "max_think_sec"
        private const val KEY_CALIBRATION_PATH = "calibration_path"
        private const val KEY_ASSISTANT_MODE = "assistant_mode"
        private const val KEY_GLASSES_BUTTONS = "glasses_buttons_enabled"
        private const val KEY_VISION_ENABLED = "vision_enabled"
        private const val KEY_VISION_ALWAYS_ON = "vision_always_on"
        private const val KEY_CUSTOM_CALL_WORD = "custom_call_word"
        private const val KEY_RESPONSE_STYLE = "response_style"
        private const val KEY_PERSONALITY_MODE = "personality_mode"
        private const val KEY_CUSTOM_PERSONALITY = "custom_personality"
        private const val KEY_STOP_KEY = "stop_key"
        private const val KEY_BT_MIC_ADDRESS = "bt_mic_address"
        private const val KEY_AUTO_CLIP = "auto_clip_enabled"
        private const val KEY_LISTENER_CLIPS = "listener_clips_enabled"
        private val DEFAULTS = mapOf(
            KEY_DEEPGRAM to "",
            KEY_GROQ to "",
        )
    }
}

enum class LlmChoice(val id: String, val display: String) {
    GROQ("groq", "Groq"),
    DEEPSEEK("deepseek", "DeepSeek");

    companion object {
        fun fromId(id: String?): LlmChoice = entries.firstOrNull { it.id == id } ?: GROQ
    }
}

enum class AssistantMode(val id: String, val display: String) {
    PASSIVE("passive", "Others"),
    PERSONAL("personal", "Me");

    companion object {
        fun fromId(id: String?): AssistantMode = entries.firstOrNull { it.id == id } ?: PASSIVE
    }
}

enum class ResponseStyle(val id: String, val display: String) {
    IMMEDIATE("immediate", "Immediate"),
    STANDARD("standard", "Standard"),
    DESCRIPTIVE("descriptive", "Descriptive");

    companion object {
        fun fromId(id: String?): ResponseStyle = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

enum class PersonalityMode(val id: String, val display: String) {
    DEFAULT("default", "🔧 Technician"),
    SOCIAL_AUTOPILOT("social_autopilot", "🎭 Social Autopilot"),
    JOKE("joke", "😂 Joke Mode"),
    ELECTRICAL_GENIUS("electrical_genius", "⚡ Electrical Genius"),
    MYSTIC("mystic", "🔮 Mystic"),
    RAGEBAIT("ragebait", "😤 Ragebait"),
    COMEBACK_KING("comeback_king", "👑 Comeback King"),
    HYPE_MAN("hype_man", "🔥 Hype Man"),
    SHADOW_ANALYST("shadow_analyst", "🕵️ Shadow Analyst"),
    NEGOTIATOR("negotiator", "🤝 Negotiator"),
    DEBATE_COACH("debate_coach", "⚔️ Debate Coach"),
    PHILOSOPHER("philosopher", "🧠 Philosopher"),
    CONSPIRACY("conspiracy", "👁️ Conspiracy"),
    CLAIRVOYANT("clairvoyant", "🌙 Clairvoyant"),
    ENGLISH_1800S("english_1800s", "🎩 Victorian"),
    ENGLISH_1900S("english_1900s", "🧐 Edwardian"),
    ENGLISH_SCHOLAR("english_scholar", "📚 English Scholar"),
    RICHARD_NIXON("richard_nixon", "🇺🇸 Richard Nixon"),
    POLITICAL_NUTJOB("political_nutjob", "📢 Political Nutjob"),
    CUSTOM("custom", "✏️ Custom");

    companion object {
        fun fromId(id: String?): PersonalityMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class StopKey(val id: String, val display: String) {
    NONE("none", "None"),
    VOL_UP("vol_up", "Volume Up"),
    VOL_DOWN("vol_down", "Volume Down"),
    EITHER("either", "Either Volume Key");

    companion object {
        fun fromId(id: String?): StopKey = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Controls how fast the assistant speaks vs. how seamless the audio sounds.
 *  RESPONSIVE: send each sentence as it's ready — first word plays faster but
 *              there are ~680ms pauses between sentences.
 *  SMOOTH: wait for the full reply — no pauses, but audio starts later. */
enum class ResponseMode(val id: String, val display: String) {
    RESPONSIVE("responsive", "Responsive"),
    SMOOTH("smooth", "Smooth");

    companion object {
        fun fromId(id: String?): ResponseMode = entries.firstOrNull { it.id == id } ?: RESPONSIVE
    }
}
