package com.owner.assist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.owner.assist.MainActivity
import com.owner.assist.R

object NotificationHelper {

    // v2: adds LED lights + color — forces channel recreation on first run
    const val CHANNEL_ID = "assistant_v2"
    const val FG_NOTIF_ID = 7301

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Remove old channel if present so we don't accumulate stale channels
        nm.deleteNotificationChannel("assistant_status")
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(true)
            lightColor = Color.GREEN
        }
        nm.createNotificationChannel(ch)
    }

    fun build(ctx: Context, statusText: String, state: AssistantState = AssistantState.LISTENING): Notification {
        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val (color, ledColor, ledOn, ledOff) = when (state) {
            AssistantState.LISTENING -> Quad(Color.parseColor("#2E7D32"), Color.GREEN, 1500, 2000)
            AssistantState.THINKING  -> Quad(Color.parseColor("#E65100"), Color.YELLOW, 300, 300)
            AssistantState.SPEAKING  -> Quad(Color.parseColor("#1565C0"), Color.CYAN, 200, 100)
            AssistantState.OFF       -> Quad(Color.GRAY, Color.BLACK, 0, 0)
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(statusText)
            .setColor(color)
            .setColorized(true)
            .setLights(ledColor, ledOn, ledOff)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, ctx.getString(R.string.notif_action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state == AssistantState.THINKING) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private data class Quad(val color: Int, val led: Int, val on: Int, val off: Int)
}
