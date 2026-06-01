package com.owner.assist.pipeline

import android.util.Log

/**
 * Wake-word detection interface.
 *
 * Default implementation is a no-op that always reports "detected" — so when
 * the wake-word toggle is OFF (or until a real engine is wired), the pipeline
 * runs in always-listening mode unchanged.
 *
 * To plug in Porcupine (recommended):
 *   1. Sign up at https://console.picovoice.ai (free tier)
 *   2. Train a custom wake word (e.g. "Hey J") and download the .ppn file
 *   3. Add the Porcupine Android SDK: `implementation("ai.picovoice:porcupine-android:3.0.2")`
 *   4. Place the .ppn in `app/src/main/assets/`
 *   5. Implement [PorcupineDetector] (skeleton below)
 *
 * Until then, set [PassthroughDetector] in [WakeWordFactory].
 */
interface WakeWordDetector {
    /** Returns true if the wake word was detected in this 16kHz PCM frame. */
    fun process(pcmFrame: ShortArray): Boolean

    /** Release any native resources. */
    fun release()

    companion object {
        const val FRAME_SAMPLES = 512  // Porcupine expects 512-sample frames
    }
}

/** No-op: wake word always considered "heard" → caller stays in always-listening. */
class PassthroughDetector : WakeWordDetector {
    override fun process(pcmFrame: ShortArray): Boolean = true
    override fun release() = Unit
}

/**
 * Skeleton for the Porcupine integration. Currently throws so the absence of
 * the SDK is obvious. Replace the body once the dependency is added.
 */
class PorcupineDetector(@Suppress("UNUSED_PARAMETER") accessKey: String) : WakeWordDetector {
    init {
        Log.w(TAG, "PorcupineDetector not yet wired — add ai.picovoice:porcupine-android dep and uncomment body")
    }
    override fun process(pcmFrame: ShortArray): Boolean {
        // val keywordIndex = porcupine.process(pcmFrame)
        // return keywordIndex >= 0
        return false
    }
    override fun release() {
        // porcupine.delete()
    }
    companion object { private const val TAG = "PorcupineDetector" }
}

object WakeWordFactory {
    /**
     * Return the correct detector based on user settings.
     * For now: if the toggle is on, fall back to Passthrough with a warning until
     * Porcupine is wired in.
     */
    fun build(enabled: Boolean, accessKey: String?): WakeWordDetector {
        if (!enabled) return PassthroughDetector()
        if (accessKey.isNullOrBlank()) {
            Log.w(TAG, "wake-word enabled but no Picovoice access key — running passthrough")
            return PassthroughDetector()
        }
        return PorcupineDetector(accessKey)
    }
    private const val TAG = "WakeWordFactory"
}
