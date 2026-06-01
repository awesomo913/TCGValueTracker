package com.owner.assist.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.service.AssistantService
import com.owner.assist.service.AssistantState
import com.owner.assist.service.AssistantStateBus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SecureKeyStore(ctx) }
    val state by AssistantStateBus.state.collectAsState()
    val events by AssistantStateBus.events.collectAsState()
    val isOff = state == AssistantState.OFF
    var calibratingIn by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(calibratingIn) {
        val c = calibratingIn ?: return@LaunchedEffect
        if (c > 0) {
            delay(1_000)
            calibratingIn = c - 1
        } else {
            ctx.startService(AssistantService.calibrateIntent(ctx))
            calibratingIn = null
        }
    }

    val requiredPerms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            ContextCompat.startForegroundService(ctx, AssistantService.startIntent(ctx))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Voice Assistant") },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            },
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(state.uiColor()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.label(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isOff) "Tap ON to start a session." else "Tap OFF for a hard stop.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (!store.keysComplete()) {
                Text(
                    "API keys missing — open Settings.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!isOff) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { if (calibratingIn == null) calibratingIn = 3 },
                    enabled = calibratingIn == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (calibratingIn != null) "Speak now… $calibratingIn"
                        else "Calibrate My Voice"
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    if (isOff) {
                        val missing = requiredPerms.filter {
                            ContextCompat.checkSelfPermission(ctx, it) !=
                                PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            permLauncher.launch(missing.toTypedArray())
                        } else {
                            ContextCompat.startForegroundService(
                                ctx,
                                AssistantService.startIntent(ctx),
                            )
                        }
                    } else {
                        ctx.startService(AssistantService.stopIntent(ctx))
                    }
                },
                enabled = isOff.let { off -> if (off) store.keysComplete() else true },
                colors = if (isOff) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isOff) "Turn ON" else "Turn OFF")
            }

            if (events.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    events.forEach { event ->
                        Text(
                            text = event,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun AssistantState.label(): String = when (this) {
    AssistantState.OFF -> "Off"
    AssistantState.LISTENING -> "Listening"
    AssistantState.THINKING -> "Thinking"
    AssistantState.SPEAKING -> "Speaking"
}

private fun AssistantState.uiColor(): Color = when (this) {
    AssistantState.OFF       -> Color(0xFF9E9E9E)
    AssistantState.LISTENING -> Color(0xFF2E7D32)
    AssistantState.THINKING  -> Color(0xFFE65100)
    AssistantState.SPEAKING  -> Color(0xFF1565C0)
}
