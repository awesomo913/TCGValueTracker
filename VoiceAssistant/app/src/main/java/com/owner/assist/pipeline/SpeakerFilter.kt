package com.owner.assist.pipeline

import android.util.Log

/**
 * Filters the user's own voice using two independent signals:
 *
 *   Layer 1 — RMS volume (local, instant):
 *     The user wears the mic, so their voice hits the capsule directly and is louder
 *     than anyone else in the room. Anything above [selfRmsDb] - [VOLUME_MARGIN_DB]
 *     is treated as self.
 *
 *   Layer 2 — Speaker ID (cloud, ~100ms delay):
 *     Deepgram diarize=true assigns integer speaker labels per utterance.
 *     Once the user's speaker ID is locked via [calibrateSelf], any transcript
 *     with that ID is filtered regardless of volume.
 *
 * Default volume threshold: -22 dBFS. Voices close to a clip-on mic are typically
 * -10 to -20 dBFS. Room voices land at -30 to -45 dBFS. Adjust via [setVolumeThreshold].
 */
class SpeakerFilter {

    @Volatile private var selfSpeakerId: Int = -1
    @Volatile private var selfRmsDb: Float = DEFAULT_SELF_DB

    /** Returns true if this transcript should be skipped (it's the user's voice). */
    fun isSelf(speakerId: Int, rmsDb: Float): Boolean {
        if (rmsDb > selfRmsDb - VOLUME_MARGIN_DB) return true
        if (selfSpeakerId >= 0 && speakerId >= 0 && speakerId == selfSpeakerId) return true
        return false
    }

    /**
     * Lock in the user's voice profile. Call when you know the user is speaking
     * (e.g. a "Calibrate my voice" button tap + 3 seconds of the user talking).
     */
    fun calibrateSelf(speakerId: Int, rmsDb: Float) {
        selfSpeakerId = speakerId
        selfRmsDb = rmsDb
        Log.i(TAG, "Self calibrated: speaker=$speakerId threshold=${"%.1f".format(rmsDb)} dBFS")
    }

    /** Adjust volume threshold alone (e.g. from a settings slider). */
    fun setVolumeThreshold(db: Float) {
        selfRmsDb = db
        Log.i(TAG, "Volume threshold set to ${"%.1f".format(db)} dBFS")
    }

    val isCalibrated: Boolean get() = selfSpeakerId >= 0
    val volumeThreshold: Float get() = selfRmsDb

    companion object {
        private const val TAG = "SpeakerFilter"
        private const val DEFAULT_SELF_DB = -22f
        private const val VOLUME_MARGIN_DB = 5f
    }
}
