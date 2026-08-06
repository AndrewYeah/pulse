package com.andrew.proxyapp

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.RuleSetManager
import com.andrew.proxyapp.data.ThemeMode
import com.andrew.proxyapp.manager.RuleSetUpdateWorker

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val settings = ConfigStore.get(this).getSettings()
        AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(settings.language.ifBlank { "zh-CN" })
        )
        AppCompatDelegate.setDefaultNightMode(
            when (settings.themeMode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        RuleSetManager.prepare(this)
        RuleSetUpdateWorker.schedule(this)
        Log.i("MyApplication", "App started")
        // 可在此初始化全局组件（如数据库、日志、崩溃收集等）
        // 代理功能由 TunnelService 按需启动，无需在此初始化
    }
}
