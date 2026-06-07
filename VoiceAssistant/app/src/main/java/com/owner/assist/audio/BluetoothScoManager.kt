package com.owner.assist.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Connects Bluetooth SCO so the lavalier earbud can both deliver mic audio and play TTS.
 *
 * SCO is the protocol that gives us synchronous mic + speaker on a Bluetooth headset.
 * A2DP (the high-quality music profile) is mic-less; we MUST be on SCO for two-way.
 *
 * Two implementations:
 *   - API 31+ (Android 12+): AudioManager.setCommunicationDevice with the BT_SCO device.
 *   - Below: legacy startBluetoothSco + isBluetoothScoOn (deprecated but still works).
 *
 * Falls back to phone mic / earpiece when SCO can't be established.
 */
class BluetoothScoManager(private val ctx: Context) {

    enum class Route { SCO, FALLBACK_PHONE }

    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var legacyReceiver: BroadcastReceiver? = null
    private var modernDeviceSet: Boolean = false
    private var savedMusicVolume: Int = -1
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL

    suspend fun connect(timeoutMs: Long = SCO_TIMEOUT_MS, preferredAddress: String = ""): Route {
        // Ensure we start from NORMAL mode. A previous session may have set MODE_IN_COMMUNICATION
        // without restoring it (e.g. crash, hard kill). Stale incall mode silences AudioRecord
        // by routing the mic to the SCO headset instead of the phone's internal mic.
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            Log.w(TAG, "Stale audio mode ${audioManager.mode} — resetting to NORMAL")
            audioManager.mode = AudioManager.MODE_NORMAL
            delay(100)
        }
        val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) connectModern(preferredAddress)
        else connectLegacy(timeoutMs)
        if (route == Route.SCO) {
            // Max both streams: VOICE_CALL for legacy routing, MUSIC for USAGE_MEDIA AudioTrack.
            // AudioTrack(USAGE_MEDIA) maps to STREAM_MUSIC — if music volume is 0, silence.
            val maxCall = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0)
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            Log.i(TAG, "SCO volume set call=$maxCall/$maxCall music=$maxMusic/$maxMusic (was $savedMusicVolume)")
        }
        return route
    }

    fun release() {
        if (savedMusicVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                Log.i(TAG, "STREAM_MUSIC restored to $savedMusicVolume")
            } catch (e: Exception) {
                Log.w(TAG, "restore music volume failed: ${e.message}")
            }
            savedMusicVolume = -1
        }
        if (modernDeviceSet) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
            } catch (e: Exception) {
                Log.w(TAG, "clearCommunicationDevice failed: ${e.message}")
            }
            modernDeviceSet = false
        }
        try {
            legacyReceiver?.let { ctx.unregisterReceiver(it) }
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "legacy receiver already unregistered: ${e.message}")
        }
        legacyReceiver = null
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        } catch (e: Exception) {
            Log.w(TAG, "stopBluetoothSco threw: ${e.message}")
        }
    }

    // ------- API 31+ path -------

    @android.annotation.SuppressLint("NewApi")
    private suspend fun connectModern(preferredAddress: String = ""): Route {
        // Do NOT set MODE_IN_COMMUNICATION here — it routes the mic to the SCO headset mic,
        // which silences AudioRecord (SCO mic on Oakley Meta is 8kHz only, incompatible with
        // our 16kHz AudioRecord). Samsung routes USAGE_MEDIA to SCO automatically when
        // setCommunicationDevice is active, so we don't need the mode change for output either.
        repeat(SCO_MODERN_RETRIES) { attempt ->
            val devices = audioManager.availableCommunicationDevices
            Log.i(TAG, "SCO attempt ${attempt + 1}/$SCO_MODERN_RETRIES — available: ${devices.map { it.type }}")
            val scoDevice = if (preferredAddress.isNotBlank()) {
                devices.firstOrNull {
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                     (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                      it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)) &&
                    it.address == preferredAddress
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                     it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
            } else {
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                     it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
            }
            if (scoDevice != null) {
                val ok = try {
                    audioManager.setCommunicationDevice(scoDevice)
                } catch (e: Exception) {
                    Log.w(TAG, "setCommunicationDevice threw: ${e.message}")
                    false
                }
                if (ok) {
                    Log.i(TAG, "SCO route set via setCommunicationDevice type=${scoDevice.type} (attempt ${attempt + 1})")
                    modernDeviceSet = true
                    return Route.SCO
                }
            }
            if (attempt < SCO_MODERN_RETRIES - 1) {
                Log.w(TAG, "no BT SCO/BLE device yet — retrying in ${SCO_MODERN_DELAY_MS}ms")
                delay(SCO_MODERN_DELAY_MS)
            }
        }
        Log.w(TAG, "SCO not available after $SCO_MODERN_RETRIES attempts — FALLBACK_PHONE")
        return Route.FALLBACK_PHONE
    }

    // ------- legacy path (< API 31) -------

    private suspend fun connectLegacy(timeoutMs: Long): Route {
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w(TAG, "SCO unavailable on this device")
            return Route.FALLBACK_PHONE
        }
        repeat(SCO_MAX_RETRIES) { attempt ->
            if (tryScoOnce(timeoutMs)) return Route.SCO
            if (attempt < SCO_MAX_RETRIES - 1) {
                Log.w(TAG, "SCO attempt ${attempt + 1} failed — retrying in ${SCO_RETRY_DELAY_MS}ms")
                delay(SCO_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "SCO failed after $SCO_MAX_RETRIES attempts")
        return Route.FALLBACK_PHONE
    }

    private suspend fun tryScoOnce(timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        var seenConnecting = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR
                ) ?: AudioManager.SCO_AUDIO_STATE_ERROR
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "SCO connected (legacy)")
                        deferred.complete(true)
                    }
                    // Samsung fires DISCONNECTED immediately as the current-idle state broadcast
                    // before transitioning — ignore it unless we already saw CONNECTING.
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> seenConnecting = true
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Log.w(TAG, "SCO state=$state seenConnecting=$seenConnecting")
                        if (seenConnecting && !deferred.isCompleted) deferred.complete(false)
                    }
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        @Suppress("DEPRECATION") audioManager.startBluetoothSco()
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "SCO timed out after ${timeoutMs}ms")
            false
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
            legacyReceiver = null
        }
    }

    companion object {
        private const val TAG = "BluetoothScoManager"
        private const val SCO_TIMEOUT_MS = 8_000L
        private const val SCO_MAX_RETRIES = 3
        private const val SCO_RETRY_DELAY_MS = 2_000L
        // Modern path retries: MODE_IN_COMMUNICATION triggers async BT negotiation;
        // SCO device may not appear in availableCommunicationDevices for up to 1-2s.
        private const val SCO_MODERN_RETRIES = 5
        private const val SCO_MODERN_DELAY_MS = 400L
        const val ACTION_HEADSET_STATE = BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED

        data class BtHeadsetInfo(val address: String, val name: String)

        @SuppressLint("MissingPermission")
        fun listPairedHeadsets(ctx: Context): List<BtHeadsetInfo> {
            return try {
                val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val adapter = btManager?.adapter ?: return emptyList()
                if (!adapter.isEnabled) return emptyList()
                adapter.bondedDevices
                    ?.filter { device ->
                        device.bluetoothClass?.deviceClass?.let { cls ->
                            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                            cls == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
                        } ?: false
                    }
                    ?.map { BtHeadsetInfo(it.address, it.name ?: it.address) }
                    ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "listPairedHeadsets failed: ${e.message}")
                emptyList()
            }
        }
    }
}
