package com.andrew.proxyapp.manager

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.andrew.proxyapp.service.TunnelService
import kotlinx.coroutines.flow.StateFlow

/**
 * ProxyManager - 代理生命周期管理单例
 *
 * 封装了：
 *   - VPN 权限申请流程
 *   - 启动 / 停止 TunnelService
 *   - 状态查询
 *
 * 在 Activity 中：
 *   ProxyManager.requestPermissionAndStart(this)
 *   // 在 onActivityResult 中调用：
 *   ProxyManager.onPermissionResult(resultCode)
 */
object ProxyManager {

    private const val TAG = "ProxyManager"
    val state: StateFlow<TunnelService.TunnelState> = TunnelService.tunnelState

    val isRunning: Boolean
        get() = state.value == TunnelService.TunnelState.RUNNING

    /**
     * 检查并申请 VPN 权限，然后启动代理
     *
     * @param activity 调用方 Activity（用于显示权限弹窗）
     * @return true = 已有权限并立即启动；false = 需要等待权限授权回调
     */
    fun permissionIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * 在 Activity.onActivityResult 中调用
     * 用户点击了 VPN 权限弹窗的"确定"后，这里启动代理
     */
    fun onPermissionGranted(context: Context) {
        Log.i(TAG, "VPN permission granted by user, starting...")
        start(context)
    }

    /**
     * 直接启动代理（调用前请确保已有 VPN 权限）
     */
    fun start(context: Context) {
        val store = com.andrew.proxyapp.data.ConfigStore.get(context)
        store.setDesiredRunning(true)
        store.clearStartFailures()
        val intent = Intent(context, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
        }
        context.startForegroundService(intent)
        Log.i(TAG, "TunnelService start command sent")
    }

    /**
     * 停止代理
     */
    fun stop(context: Context) {
        com.andrew.proxyapp.data.ConfigStore.get(context).setDesiredRunning(false)
        val intent = Intent(context, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        }
        context.startService(intent)
        Log.i(TAG, "TunnelService stop command sent")
    }

    fun restart(context: Context) {
        com.andrew.proxyapp.data.ConfigStore.get(context).setDesiredRunning(true)
        val intent = Intent(context, TunnelService::class.java).apply {
            action = TunnelService.ACTION_RELOAD
        }
        context.startForegroundService(intent)
    }

    /**
     * 切换代理开关（运行中则停止，停止中则启动）
     */
}
