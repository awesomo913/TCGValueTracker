package com.owner.assist.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.owner.assist.data.SecureKeyStore

class GlassesButtonReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (!SecureKeyStore(ctx).glassesButtonsEnabled) return

        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> handleHook(ctx.applicationContext)
            // volume keys: pass through, not intercepted
        }
    }

    companion object {
        private const val TAG = "GlassesBtn"
        private const val DOUBLE_TAP_MS = 500L

        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var lastHookMs = 0L
        @Volatile private var pendingSingle: Runnable? = null

        private fun handleHook(appCtx: Context) {
            val now = SystemClock.elapsedRealtime()
            val dt = now - lastHookMs
            lastHookMs = now

            if (dt < DOUBLE_TAP_MS) {
                // Second tap within window — cancel pending single and toggle on/off
                pendingSingle?.let { handler.removeCallbacks(it) }
                pendingSingle = null
                val isRunning = AssistantStateBus.state.value != AssistantState.OFF
                val action = if (isRunning) AssistantService.ACTION_STOP else AssistantService.ACTION_START
                appCtx.startService(Intent(appCtx, AssistantService::class.java).setAction(action))
                Log.i(TAG, "double-tap → $action")
            } else {
                // First tap — wait to see if a second follows before acting
                val r = Runnable {
                    pendingSingle = null
                    appCtx.startService(
                        Intent(appCtx, AssistantService::class.java)
                            .setAction(AssistantService.ACTION_FORCE_RESPOND)
                    )
                    Log.i(TAG, "single-tap → FORCE_RESPOND")
                }
                pendingSingle = r
                handler.postDelayed(r, DOUBLE_TAP_MS)
            }
        }
    }
}
