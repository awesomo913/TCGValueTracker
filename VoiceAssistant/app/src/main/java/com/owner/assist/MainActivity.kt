package com.owner.assist

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.owner.assist.data.SecureKeyStore
import com.owner.assist.data.StopKey
import com.owner.assist.service.AssistantService
import com.owner.assist.ui.ChatLogScreen
import com.owner.assist.ui.HomeScreen
import com.owner.assist.ui.NotesScreen
import com.owner.assist.ui.SettingsScreen
import com.owner.assist.ui.theme.VoiceAssistantTheme

class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result logged by VisionCapture */ }

    private lateinit var keys: SecureKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keys = SecureKeyStore(this)
        cameraPermission.launch(Manifest.permission.CAMERA)
        setContent {
            VoiceAssistantTheme {
                AppRoot()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val fires = when (keys.stopKey) {
                StopKey.VOL_UP   -> event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
                StopKey.VOL_DOWN -> event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                StopKey.EITHER   -> event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                    event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                StopKey.NONE     -> false
            }
            if (fires) {
                startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP_SPEAKING))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenChatLog  = { nav.navigate("chatlogs") },
                    onOpenNotes    = { nav.navigate("notes") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable("chatlogs") {
                ChatLogScreen(onBack = { nav.popBackStack() })
            }
            composable("notes") {
                NotesScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
