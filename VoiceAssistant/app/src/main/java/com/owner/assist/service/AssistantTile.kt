package com.owner.assist.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.owner.assist.MainActivity
import com.owner.assist.data.SecureKeyStore

@RequiresApi(Build.VERSION_CODES.N)
class AssistantTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val running = AssistantStateBus.state.value != AssistantState.OFF
        if (running) {
            startService(AssistantService.stopIntent(this))
            refresh()
            return
        }

        // Validate before starting: keys + permissions. Otherwise the service
        // wakes only to immediately die or spin.
        val keys = SecureKeyStore(this)
        if (!keys.isAvailable || !keys.keysComplete()) {
            Toast.makeText(this, "Open the app and add API keys first.", Toast.LENGTH_SHORT).show()
            launchAppForSetup()
            return
        }
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Open the app to grant permissions.", Toast.LENGTH_SHORT).show()
            launchAppForSetup()
            return
        }

        ContextCompat.startForegroundService(this, AssistantService.startIntent(this))
        refresh()
    }

    private fun launchAppForSetup() {
        val launchIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun requiredPerms(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = AssistantStateBus.state.value != AssistantState.OFF
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
