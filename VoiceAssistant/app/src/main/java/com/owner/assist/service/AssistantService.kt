package com.owner.assist.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.owner.assist.R
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.pipeline.ConversationOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the audio pipeline + mic + BT routing.
 *
 * Hard-OFF: ACTION_STOP cancels the orchestrator and tears down the FG. The next
 * START rebuilds the CoroutineScope so a fresh ON tap (after an OFF) works.
 */
class AssistantService : Service() {

    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null
    private var orchestrator: ConversationOrchestrator? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP — hard terminating")
                hardStop()
                return START_NOT_STICKY
            }
            ACTION_CALIBRATE -> {
                orchestrator?.calibrateSelfNow()
                return START_NOT_STICKY
            }
            ACTION_CALIBRATE_WINDOW -> {
                val orch = orchestrator ?: return START_NOT_STICKY
                val s = scope
                if (s == null) {
                    Log.w(TAG, "CALIBRATE_WINDOW ignored — scope is null")
                } else {
                    s.launch { orch.startCalibrationWindow(3_500L) }
                }
                return START_NOT_STICKY
            }
            ACTION_RECORD_SESSION -> {
                val orch = orchestrator ?: return START_NOT_STICKY
                val path = orch.startSessionRecording()
                if (path.isNotEmpty()) {
                    AssistantStateBus.setSessionRecording(true)
                    AssistantStateBus.addEvent("Recording session...")
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORD -> {
                val orch = orchestrator ?: return START_NOT_STICKY
                val path = orch.stopSessionRecording()
                AssistantStateBus.setSessionRecording(false)
                if (path.isNotEmpty()) {
                    AssistantStateBus.setSessionSavedPath(path)
                    AssistantStateBus.addEvent("Saved: ${path.substringAfterLast('/')}")
                }
                return START_NOT_STICKY
            }
            ACTION_TUNE_QUESTIONER -> {
                val orch = orchestrator ?: return START_NOT_STICKY
                val s = scope ?: return START_NOT_STICKY
                s.launch { orch.startQuestionerTuneWindow(5_000L) }
                return START_NOT_STICKY
            }
            ACTION_CLEAR_QUESTIONERS -> {
                com.owner.assist.data.QuestionerProfileStore.clear(this)
                AssistantStateBus.addEvent("Questioner profiles cleared")
                return START_NOT_STICKY
            }
            ACTION_FORCE_RESPOND -> {
                val orch = orchestrator
                if (orch != null) orch.forceRespondNext.set(true)
                else Log.d(TAG, "FORCE_RESPOND ignored — orchestrator not running")
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                Log.i(TAG, "RESTART — tearing down and rebuilding in-place")
                softStop()
                android.os.Handler(mainLooper).postDelayed({ startUp() }, 250L)
                return START_NOT_STICKY
            }
            ACTION_START, null -> startUp()
        }
        return START_NOT_STICKY
    }

    private fun startUp() {
        if (orchestrator != null) {
            Log.i(TAG, "already running — ignoring START")
            return
        }
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = freshScope

        startForegroundCompat(label = getString(R.string.notif_listening), state = AssistantState.LISTENING)
        AssistantStateBus.set(AssistantState.LISTENING)

        stateJob = AssistantStateBus.state
            .onEach { st ->
                val text = when (st) {
                    AssistantState.LISTENING -> getString(R.string.notif_listening)
                    AssistantState.THINKING  -> getString(R.string.notif_thinking)
                    AssistantState.SPEAKING  -> getString(R.string.notif_speaking)
                    AssistantState.OFF       -> null
                }
                if (text != null) updateNotification(text, st)
            }
            .launchIn(freshScope)

        val keys = SecureKeyStore(this)
        if (!keys.isAvailable || !keys.keysComplete()) {
            Log.w(TAG, "keys missing — stopping immediately")
            hardStop()
            return
        }
        orchestrator = ConversationOrchestrator(this, freshScope, keys).also { it.start() }
    }

    private fun startForegroundCompat(label: String, state: AssistantState = AssistantState.LISTENING) {
        val notif = NotificationHelper.build(this, label, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.FG_NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NotificationHelper.FG_NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String, state: AssistantState = AssistantState.LISTENING) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NotificationHelper.FG_NOTIF_ID, NotificationHelper.build(this, text, state))
    }

    /** Tears down the orchestrator and scope but keeps the service alive as foreground.
     *  Used by ACTION_RESTART so startUp() can rebuild without a service lifecycle gap. */
    private fun softStop() {
        orchestrator?.stop()
        orchestrator = null
        stateJob?.cancel()
        stateJob = null
        scope?.cancel()
        scope = null
        AssistantStateBus.set(AssistantState.OFF)
    }

    private fun hardStop() {
        softStop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // Catch OS-driven kills (low-memory trim, adb shell stop).
        // hardStop() is idempotent.
        if (orchestrator != null || scope != null) {
            try {
                orchestrator?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "orchestrator.stop in onDestroy threw: ${e.message}")
            }
            orchestrator = null
            stateJob?.cancel()
            stateJob = null
            scope?.cancel()
            scope = null
        }
        AssistantStateBus.set(AssistantState.OFF)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AssistantService"
        const val ACTION_START = "com.owner.assist.action.START"
        const val ACTION_STOP = "com.owner.assist.action.STOP"
        const val ACTION_RESTART = "com.owner.assist.action.RESTART"
        const val ACTION_CALIBRATE = "com.owner.assist.action.CALIBRATE"
        const val ACTION_CALIBRATE_WINDOW = "com.owner.assist.action.CALIBRATE_WINDOW"
        const val ACTION_RECORD_SESSION = "com.owner.assist.action.RECORD_SESSION"
        const val ACTION_STOP_RECORD = "com.owner.assist.action.STOP_RECORD"
        const val ACTION_TUNE_QUESTIONER = "com.owner.assist.action.TUNE_QUESTIONER"
        const val ACTION_CLEAR_QUESTIONERS = "com.owner.assist.action.CLEAR_QUESTIONERS"
        const val ACTION_FORCE_RESPOND = "com.owner.assist.action.FORCE_RESPOND"

        fun startIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_STOP)

        fun restartIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_RESTART)

        fun calibrateIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_CALIBRATE)

        fun calibrateWindowIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_CALIBRATE_WINDOW)

        fun recordSessionIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_RECORD_SESSION)

        fun stopRecordIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_STOP_RECORD)

        fun tuneQuestionerIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_TUNE_QUESTIONER)

        fun clearQuestionersIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_CLEAR_QUESTIONERS)

        fun forceRespondIntent(ctx: android.content.Context): Intent =
            Intent(ctx, AssistantService::class.java).setAction(ACTION_FORCE_RESPOND)
    }
}
