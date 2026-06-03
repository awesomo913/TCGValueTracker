package com.owner.assist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.owner.assist.data.LlmChoice
import com.owner.assist.data.OemAutostart
import com.owner.assist.data.SecureKeyStore
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { SecureKeyStore(ctx) }

    var deepgram by remember { mutableStateOf(store.deepgramKey) }
    var groq by remember { mutableStateOf(store.groqKey) }
    var deepseek by remember { mutableStateOf(store.deepseekKey) }
    var provider by remember { mutableStateOf(store.llmProvider) }
    var wakeWord by remember { mutableStateOf(store.wakeWordEnabled) }
    var contextBlurb by remember { mutableStateOf(store.contextBlurb) }
    var responsivenessLevel by remember { mutableIntStateOf(store.responsivenessLevel) }
    var maxThinkTimeSec by remember { mutableIntStateOf(store.maxThinkTimeSec) }
    var glassesButtons by remember { mutableStateOf(store.glassesButtonsEnabled) }
    var visionEnabled by remember { mutableStateOf(store.visionEnabled) }
    var piVisionUrl by remember { mutableStateOf(store.piVisionUrl) }
    var btCameraUrl by remember { mutableStateOf(store.btCameraUrl) }
    var visionAlwaysOn by remember { mutableStateOf(store.visionAlwaysOn) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            if (!store.isAvailable) {
                Text(
                    "Secure storage unavailable — keystore may be corrupted. Reinstall the app.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel("API keys")
            SecretField("Deepgram (STT + TTS)", deepgram) { deepgram = it }
            SecretField("Groq", groq) { groq = it }
            SecretField("DeepSeek (fallback)", deepseek) { deepseek = it }

            SectionLabel("LLM provider")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = provider == choice,
                        onClick = { provider = choice },
                        label = { Text(choice.display) },
                    )
                }
            }

            SectionLabel("Wake word (v2)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Require 'Hey J' before listening")
                Switch(checked = wakeWord, onCheckedChange = { wakeWord = it })
            }
            Text(
                "Off = always-listening (~\$0.75/hr active). On = on-device wake word, near-zero idle cost.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionLabel("Background reliability")
            Text(
                "Android will kill long-running background services unless you exempt this app.",
                style = MaterialTheme.typography.bodySmall,
            )
            val batteryOk = !OemAutostart.isBatteryOptimized(ctx)
            Text(
                "Battery optimization: " + if (batteryOk) "allowed" else "RESTRICTED",
                color = if (batteryOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { OemAutostart.openBatteryOptimizationSettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open battery exemption") }

            OemAutostart.oemHint()?.let { hint ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { OemAutostart.openOemAutostartSettings(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open autostart settings") }
            }

            SectionLabel("Response speed")
            val speedLabel = when {
                responsivenessLevel <= 20  -> "Very responsive — fastest start, noticeable gaps between sentences"
                responsivenessLevel <= 45  -> "Responsive — quick start with small gaps"
                responsivenessLevel <= 65  -> "Balanced — moderate start time, minimal gaps"
                responsivenessLevel <= 85  -> "Smooth — slower start, seamless audio"
                else                       -> "Very smooth — waits for full reply, no gaps at all"
            }
            Text(speedLabel, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = responsivenessLevel.toFloat(),
                onValueChange = { responsivenessLevel = it.roundToInt() },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fast", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Smooth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionLabel("Max formulation time")
            Text(
                "How long the AI can think before being cut off. Currently: ${maxThinkTimeSec}s. " +
                "If responses run on too long, lower this.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = maxThinkTimeSec.toFloat(),
                onValueChange = { maxThinkTimeSec = it.roundToInt() },
                valueRange = 5f..60f,
                steps = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("5s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("60s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionLabel("Domain knowledge")
            Text(
                "What should the assistant know about? " +
                "Leave blank to keep the built-in forklift / technician context.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = contextBlurb,
                onValueChange = { contextBlurb = it },
                label = { Text("Context (optional)") },
                placeholder = { Text("e.g. You are an assistant for an HVAC technician…") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("Vision / Camera")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Enable vision commands")
                Switch(checked = visionEnabled, onCheckedChange = { visionEnabled = it })
            }
            Text(
                "Say \"what is this\", \"read that\", \"identify this\" to capture and describe what the camera sees.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (visionEnabled) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = piVisionUrl,
                    onValueChange = { piVisionUrl = it },
                    label = { Text("Pi vision server URL") },
                    placeholder = { Text("http://192.168.1.213:8766") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = btCameraUrl,
                    onValueChange = { btCameraUrl = it },
                    label = { Text("Secondary camera URL (optional)") },
                    placeholder = { Text("http://192.168.1.x:8080/shot.jpg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Secondary camera: IP Webcam app → /shot.jpg, DroidCam → port 4747/shot.jpg. Leave blank to use phone camera.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Always-on scanning (Pi only)")
                    Switch(checked = visionAlwaysOn, onCheckedChange = { visionAlwaysOn = it })
                }
                Text(
                    "Captures a frame every 15s and logs detections — no speech. Requires Pi on same WiFi.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionLabel("Glasses button control (in dev)")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Intercept Meta Ray-Ban taps (in dev)")
                Switch(checked = false, onCheckedChange = {}, enabled = false)
            }
            Text(
                "Not yet active — needs live testing with glasses. " +
                "Requires Meta View disabled or uninstalled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    store.deepgramKey = deepgram.trim()
                    store.groqKey = groq.trim()
                    store.deepseekKey = deepseek.trim()
                    store.llmProvider = provider
                    store.wakeWordEnabled = wakeWord
                    store.contextBlurb = contextBlurb.trim()
                    store.responsivenessLevel = responsivenessLevel
                    store.maxThinkTimeSec = maxThinkTimeSec
                    store.glassesButtonsEnabled = glassesButtons
                    store.visionEnabled = visionEnabled
                    store.piVisionUrl = piVisionUrl.trim()
                    store.btCameraUrl = btCameraUrl.trim()
                    store.visionAlwaysOn = visionAlwaysOn
                    onBack()
                },
                enabled = store.isAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
