package com.owner.assist.pipeline

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hard-mute the STT send-path while the assistant is speaking.
 *
 * Prevents the mic from hearing its own TTS playback and treating it as a new
 * user utterance (self-listening loop). Cheaper and more reliable than acoustic
 * echo cancellation across Bluetooth SCO.
 *
 * Usage:
 *   - call [openMic] when speaking starts
 *   - in the mic stream loop, gate each chunk with [shouldSend]
 *   - call [closeMic] after TTS playback ends + a small tail delay
 */
class BargeInGate {
    private val muted = AtomicBoolean(false)

    /** Returns true if this PCM chunk should be forwarded to STT. */
    fun shouldSend(): Boolean = !muted.get()

    /** Mute mic send-path. Called at first TTS audio. */
    fun openMic() {
        muted.set(true)
    }

    /** Unmute mic send-path. Called after AudioTrack drains + tail delay. */
    fun closeMic() {
        muted.set(false)
    }

    val isMuted: Boolean get() = muted.get()
}
