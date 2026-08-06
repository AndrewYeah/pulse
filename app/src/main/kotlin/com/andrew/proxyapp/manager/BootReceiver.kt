package com.andrew.proxyapp.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启 Receiver（可选功能）
 * 如果不需要开机自动连接代理，可以忽略此文件
 *
 * 启用方法：在 MainActivity 中 SharedPreferences 存储"自动连接"偏好，
 * 然后在此处读取并决定是否自动启动
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i("BootReceiver", "Device booted, checking auto-connect preference...")

        // 读取用户设置的"开机自连"偏好
        val store = com.andrew.proxyapp.data.ConfigStore.get(context)
        val autoConnect = store.desiredRunning() && !store.shouldSuppressAutoRestart()

        if (autoConnect) {
            Log.i("BootReceiver", "Auto-connect enabled, starting proxy...")
            ProxyManager.start(context)
        }
    }
}
