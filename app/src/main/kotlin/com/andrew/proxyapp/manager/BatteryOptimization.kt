package com.andrew.proxyapp.manager

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class BatteryGuidance(
    val manufacturer: String,
    val isIgnoringOptimizations: Boolean,
    val title: String,
    val steps: List<String>
)

object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun openSystemSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun guidance(context: Context): BatteryGuidance {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val steps = stepsForManufacturer(manufacturer)
        return BatteryGuidance(
            manufacturer = manufacturer.ifBlank { "android" },
            isIgnoringOptimizations = isIgnoring(context),
            title = "Background activity settings",
            steps = steps
        )
    }

    fun stepsForManufacturer(manufacturer: String): List<String> = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                "Battery: set Pulse to No restrictions",
                "Security: enable Autostart for Pulse",
                "Recent apps: lock Pulse"
            )
            manufacturer.contains("huawei") -> listOf(
                "Battery: set Pulse to Unrestricted",
                "App launch: enable automatic and background launch"
            )
            else -> listOf("Battery: allow unrestricted background use for Pulse")
        }
}
