package com.owner.assist.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Battery + OEM autostart deep-links.
 *
 * Many Chinese OEM skins (MIUI/Xiaomi, ColorOS/Oppo, EMUI/Huawei, Realme,
 * Vivo Funtouch, OnePlus) kill background services aggressively. This object
 * helps the user grant the necessary exemptions.
 *
 * Public helpers:
 *   - isBatteryOptimized(): whether the app is currently restricted
 *   - openBatteryOptimizationSettings(): ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *   - openOemAutostartSettings(): deep-link into the manufacturer's autostart panel
 *   - oemHint(): a short human string with which OEM-specific steps apply
 */
object OemAutostart {

    private const val TAG = "OemAutostart"

    fun isBatteryOptimized(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @Suppress("BatteryLife") // intentional — we ARE the rare app that legit needs always-on
    fun openBatteryOptimizationSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct battery settings unavailable, falling back: ${e.message}")
            openFallbackBatterySettings(ctx)
        }
    }

    private fun openFallbackBatterySettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Fallback battery settings unavailable: ${e.message}")
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Best-effort deep-link to the OEM's autostart / background-app panel.
     * Returns true if we launched something, false if we couldn't find an intent.
     */
    fun openOemAutostartSettings(ctx: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val candidates = OEM_INTENTS[manufacturer].orEmpty()
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "OEM intent $pkg/$cls failed: ${e.message}")
            }
        }
        Log.i(TAG, "no OEM-specific autostart intent for manufacturer=$manufacturer")
        return false
    }

    fun oemHint(): String? = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" ->
            "Xiaomi/Redmi: Open Security app → Permissions → Autostart → enable Voice Assistant."
        "oppo" -> "Oppo: Settings → Battery → App energy saver → Voice Assistant → Don't optimize."
        "realme" -> "Realme: Settings → Battery → App battery management → Voice Assistant → allow background."
        "vivo" -> "Vivo: iManager → App manager → Autostart manager → enable Voice Assistant."
        "huawei", "honor" -> "Huawei/Honor: Settings → Apps → App launch → Voice Assistant → Manage manually → allow all."
        "samsung" ->
            "Samsung: Settings → Apps → Voice Assistant → Battery → Unrestricted. Also Device care → Battery → Background usage limits → never sleeping apps → add Voice Assistant."
        "oneplus" -> "OnePlus: Settings → Battery → Battery optimization → Voice Assistant → Don't optimize."
        else -> null
    }

    /** Map of manufacturer -> list of (package, class) candidates for autostart settings. */
    private val OEM_INTENTS: Map<String, List<Pair<String, String>>> = mapOf(
        "xiaomi" to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "redmi" to listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        "oppo" to listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        "vivo" to listOf(
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        "huawei" to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        "honor" to listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        "samsung" to listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        ),
        "oneplus" to listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
    )
}
