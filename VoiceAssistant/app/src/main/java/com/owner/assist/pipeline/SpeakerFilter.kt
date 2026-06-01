package com.owner.assist.pipeline

import android.util.Log

/**
 * Filters the user's own voice using two independent signals:
 *
 *   Layer 1 — RMS volume (local, instant):
 *     The user wears the mic, so their voice hits the capsule directly and is louder
 *     than anyone else in the room. Anything above [selfRmsMinDb] - [VOLUME_MARGIN_DB]
 *     is treated as self.
 *
 *   Layer 2 — Speaker ID (cloud, ~100ms delay):
 *     Deepgram diarize=true assigns integer speaker labels per utterance.
 *     Once the user's speaker ID is locked via calibration, any transcript
 *     with that ID is filtered regardless of volume.
 *
 * Default volume threshold: -22 dBFS. Voices close to a clip-on mic are typically
 * -10 to -20 dBFS. Room voices land at -30 to -45 dBFS. Adjust via [setVolumeThreshold].
 *
 * Calibration: prefer [beginCalibration]/[addCalibrationSample]/[commitCalibration]
 * over the single-shot [calibrateSelf] — multi-sample averaging gives a more stable
 * speaker ID and volume threshold.
 */
class SpeakerFilter {

    @Volatile private var selfSpeakerId: Int = -1
    @Volatile private var selfRmsMinDb: Float = DEFAULT_SELF_DB

    // Multi-sample calibration accumulator
    private val calibSamples = mutableListOf<Pair<Int, Float>>()
    @Volatile private var calibrating = false

    /** Returns true if this transcript should be skipped (it's the user's voice). */
    fun isSelf(speakerId: Int, rmsDb: Float): Boolean {
        if (rmsDb > selfRmsMinDb - VOLUME_MARGIN_DB) return true
        if (selfSpeakerId >= 0 && speakerId >= 0 && speakerId == selfSpeakerId) return true
        return false
    }

    /**
     * Single-shot calibration — locks in exactly the current snapshot.
     * Prefer [beginCalibration] + [addCalibrationSample] + [commitCalibration] for
     * a multi-sample window which is more robust.
     */
    fun calibrateSelf(speakerId: Int, rmsDb: Float) {
        selfSpeakerId = speakerId
        selfRmsMinDb = rmsDb
        Log.i(TAG, "Self calibrated (point): speaker=$speakerId threshold=${"%.1f".format(rmsDb)} dBFS")
    }

    /**
     * Start a multi-sample calibration window. Call this, then feed samples via
     * [addCalibrationSample] at ~150ms intervals while the user speaks, then call
     * [commitCalibration] to lock in the averaged profile.
     */
    @Synchronized
    fun beginCalibration() {
        calibSamples.clear()
        calibrating = true
        Log.i(TAG, "Calibration window started")
    }

    /** Feed one RMS + speaker-ID sample into the accumulator during a calibration window. */
    @Synchronized
    fun addCalibrationSample(speakerId: Int, rmsDb: Float) {
        if (!calibrating) return
        calibSamples.add(speakerId to rmsDb)
    }

    /**
     * Finish the calibration window. Computes the modal speaker ID and average RMS
     * from all collected samples, then locks them in as the self profile.
     *
     * @return Number of samples used (0 means calibration had no data — profile unchanged).
     */
    @Synchronized
    fun commitCalibration(): Int {
        calibrating = false
        val samples = calibSamples.toList()
        calibSamples.clear()

        if (samples.isEmpty()) {
            Log.w(TAG, "Calibration committed with 0 samples — profile unchanged")
            return 0
        }

        // Modal speaker ID from labeled (>=0) samples
        val modalId = samples
            .filter { it.first >= 0 }
            .groupingBy { it.first }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: -1

        val avgRms = samples.map { it.second }.average().toFloat()

        selfSpeakerId = modalId
        selfRmsMinDb = avgRms

        Log.i(TAG, "Calibration complete: ${samples.size} samples " +
            "speaker=$modalId avg=${"%.1f".format(avgRms)} dBFS")
        return samples.size
    }

    /** Adjust volume threshold alone (e.g. from a settings slider). */
    fun setVolumeThreshold(db: Float) {
        selfRmsMinDb = db
        Log.i(TAG, "Volume threshold set to ${"%.1f".format(db)} dBFS")
    }

    val isCalibrated: Boolean get() = selfSpeakerId >= 0
    val volumeThreshold: Float get() = selfRmsMinDb

    companion object {
        private const val TAG = "SpeakerFilter"
        private const val DEFAULT_SELF_DB = -22f
        private const val VOLUME_MARGIN_DB = 5f
    }
}
