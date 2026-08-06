package com.andrew.proxyapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andrew.proxyapp.MainActivity
import com.andrew.proxyapp.R
import com.andrew.proxyapp.config.SingBoxConfigBuilder
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.ConfigurationChanges
import com.andrew.proxyapp.data.RuleSetManager
import com.andrew.proxyapp.manager.RuntimeController
import com.andrew.proxyapp.manager.TunnelRuntimeCoordinator
import com.andrew.proxyapp.data.RuntimeErrorCategory
import com.andrew.proxyapp.data.RuntimePhase
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/**
 * TunnelService - VPN 隧道服务（基于官方 sing-box libbox 1.13.x API）
 *
 * 架构：
 *   VpnService（Android 系统 API）
 *     └── 创建 TUN 虚拟网卡
 *           └── sing-box libbox（CommandServer 模式）
 *                 └── 代理出站（Hysteria2 / VLESS / VMess / TUIC / AnyTLS）
 */
class TunnelService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "TunnelService"
        const val ACTION_START = "com.andrew.proxyapp.START_VPN"
        const val ACTION_STOP = "com.andrew.proxyapp.STOP_VPN"
        const val ACTION_RELOAD = "com.andrew.proxyapp.RELOAD_VPN"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL = "tunnel_service"

        private val _tunnelState = MutableStateFlow(TunnelState.STOPPED)
        val tunnelState: StateFlow<TunnelState> = _tunnelState

        @Volatile
        private var boxInitialized = false
    }

    enum class TunnelState { STOPPED, CONNECTING, RUNNING, ERROR }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var lifecycle: TunnelRuntimeCoordinator
    private val configStore by lazy { ConfigStore.get(applicationContext) }
    @Volatile private var expectedStop = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRefreshJob: Job? = null
    private var physicalNetworkSignature = ""
    private val interfaceListeners = mutableSetOf<InterfaceUpdateListener>()
    private val observedNetworks = mutableSetOf<Network>()

    // =====================================================
    // Service 生命周期
    // =====================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lifecycle = TunnelRuntimeCoordinator(serviceScope) { phase, attempt, category, error ->
            RuntimeController.setLifecycle(
                phase = phase,
                desiredRunning = configStore.desiredRunning(),
                retryAttempt = attempt,
                category = category,
                message = error?.message
            )
            when (phase) {
                RuntimePhase.STARTING -> _tunnelState.value = TunnelState.CONNECTING
                RuntimePhase.RECONNECTING -> {
                    _tunnelState.value = TunnelState.CONNECTING
                    updateNotification(getString(R.string.notification_reconnecting, if (attempt > 0) " ($attempt)" else ""))
                }
                RuntimePhase.RUNNING -> _tunnelState.value = TunnelState.RUNNING
                RuntimePhase.FAILED -> {
                    _tunnelState.value = TunnelState.ERROR
                    val failures = configStore.recordStartFailure()
                    configStore.setLastRuntimeError(error?.message)
                    val message = if (failures >= 3) {
                        getString(R.string.notification_start_failed_retry_stopped)
                    } else {
                        getString(
                            R.string.notification_start_failed,
                            com.andrew.proxyapp.manager.ErrorTranslator.userMessage(
                                category,
                                error?.message,
                                java.util.Locale.forLanguageTag(configStore.getSettings().language)
                            )
                        )
                    }
                    updateNotification(message)
                    if (failures >= 3) serviceScope.launch(Dispatchers.Main) { stopSelf() }
                }
                RuntimePhase.STOPPING, RuntimePhase.STOPPED -> _tunnelState.value = TunnelState.STOPPED
            }
        }
        RuntimeController.onDisconnected = { message ->
            if (!expectedStop && configStore.desiredRunning()) {
                recoverTunnel(IllegalStateException("command client disconnected: ${message.orEmpty()}"))
            }
        }
        registerNetworkCallback()
        serviceScope.launch {
            ConfigurationChanges.events.collectLatest {
                delay(500)
                if (configStore.desiredRunning() && _tunnelState.value == TunnelState.RUNNING && !expectedStop) {
                    lifecycle.reload({ stopTunnel(true) }, { startTunnelOnce() }).join()
                }
            }
        }
        Log.i(TAG, "TunnelService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))
                configStore.setDesiredRunning(true)
                configStore.clearStartFailures()
                expectedStop = false
                lifecycle.start { startTunnelOnce() }
                START_STICKY
            }
            ACTION_RELOAD -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_applying_settings)))
                configStore.setDesiredRunning(true)
                expectedStop = false
                lifecycle.reload({ stopTunnel(true) }, { startTunnelOnce() })
                START_STICKY
            }
            ACTION_STOP -> {
                configStore.setDesiredRunning(false)
                expectedStop = true
                serviceScope.launch {
                    lifecycle.stop { stopTunnel(true) }.join()
                    stopSelfResult(startId)
                }
                START_NOT_STICKY
            }
            else -> {
                if (configStore.desiredRunning() && !configStore.shouldSuppressAutoRestart()) {
                    expectedStop = false
                    startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_restoring)))
                    lifecycle.start { startTunnelOnce() }
                    START_STICKY
                } else START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        expectedStop = true
        lifecycle.requestStop()
        stopTunnel(true)
        RuntimeController.onDisconnected = null
        unregisterNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "TunnelService destroyed")
    }

    override fun onRevoke() {
        configStore.setDesiredRunning(false)
        expectedStop = true
        serviceScope.launch {
            lifecycle.stop { stopTunnel(true) }.join()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground service alive when the launcher task is swiped.
        // A later process recreation is handled by START_STICKY and the
        // persisted desired-running flag.
        if (configStore.desiredRunning() && !expectedStop) {
            updateNotification(getString(R.string.notification_background))
        }
        super.onTaskRemoved(rootIntent)
    }

    // =====================================================
    // 启动 / 停止
    // =====================================================

    private suspend fun startTunnelOnce() {
        expectedStop = false
        try {
            // 1. 初始化 libbox（全局一次）
            ensureInitialized()

            // 2. 获取配置
            val store = configStore
            val proxyConfig = store.getActiveConfig()
                ?: throw IllegalStateException("没有可用的代理配置，请先添加配置")
            val configs = configStore.getAllConfigs()
            val appSettings = configStore.getSettings()
            Log.i(TAG, "Using config: ${proxyConfig.summary}")

            // 3. 生成 sing-box 配置 JSON
            val logPath = applicationContext.filesDir.absolutePath + "/singbox.log"
            val ruleSetPaths = RuleSetManager.prepare(applicationContext)
            val config = SingBoxConfigBuilder.build(
                configs, proxyConfig.id, appSettings, ruleSetPaths, packageName, logPath
            )
            Log.d(TAG, "Generated sing-box config (${config.length} chars)")

            // 4. 创建 CommandServer 并启动
            if (commandServer == null) {
                commandServer = CommandServer(this, this)
                commandServer!!.start()
            }

            // 5. 加载配置并启动 sing-box 服务
            commandServer!!.checkConfig(config)
            commandServer!!.startOrReloadService(config, OverrideOptions())
            RuntimeController.attach()

            withContext(Dispatchers.Main) {
                _tunnelState.value = TunnelState.RUNNING
                updateNotification(getString(R.string.notification_running, proxyConfig.protocol.displayName))
            }
            Log.i(TAG, "Tunnel started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _tunnelState.value = TunnelState.ERROR
                updateNotification(getString(R.string.notification_start_failed, e.message.orEmpty()))
            }
            stopTunnel(false)
            throw e
        }
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(observedNetworks) { observedNetworks.add(network) }
                scheduleNetworkRefresh(manager)
            }
            override fun onLost(network: Network) {
                synchronized(observedNetworks) { observedNetworks.remove(network) }
                scheduleNetworkRefresh(manager)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(observedNetworks) { observedNetworks.add(network) }
                scheduleNetworkRefresh(manager)
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Unable to register network callback", it) }
    }

    private fun scheduleNetworkRefresh(manager: ConnectivityManager) {
        networkRefreshJob?.cancel()
        networkRefreshJob = serviceScope.launch {
            delay(750)
            val networks = synchronized(observedNetworks) { observedNetworks.toList() } +
                listOfNotNull(manager.activeNetwork)
            val physical = networks.distinct().mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) return@mapNotNull null
                val link = manager.getLinkProperties(network) ?: return@mapNotNull null
                val name = link.interfaceName ?: return@mapNotNull null
                Triple(network.networkHandle, name, !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }.sortedBy { it.first }
            val signature = physical.joinToString("|") { "${it.first}:${it.second}" }
            val changed = physicalNetworkSignature.isNotBlank() && signature != physicalNetworkSignature
            physicalNetworkSignature = signature
            physical.firstOrNull()?.let { (_, name, metered) ->
                val index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
                synchronized(interfaceListeners) {
                    interfaceListeners.forEach { listener ->
                        runCatching { listener.updateDefaultInterface(name, index, metered, false) }
                    }
                }
            }
            if (changed && signature.isNotBlank() && configStore.desiredRunning() && !expectedStop) {
                recoverTunnel(IllegalStateException("underlying network changed"))
            }
        }
    }

    private fun recoverTunnel(cause: Throwable) {
        lifecycle.recover(cause) {
            stopTunnel(false)
            startTunnelOnce()
        }
    }

    private fun unregisterNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = networkCallback ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
        synchronized(observedNetworks) { observedNetworks.clear() }
        networkRefreshJob?.cancel()
        networkRefreshJob = null
    }

    private fun stopTunnel(intentional: Boolean = false) {
        Log.i(TAG, "Stopping tunnel...")
        if (intentional) expectedStop = true
        RuntimeController.detach()
        try {
            commandServer?.closeService()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing service: ${e.message}")
        }
        try {
            commandServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing command server: ${e.message}")
        }
        commandServer = null

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN fd: ${e.message}")
        }
        tunFd = null

        _tunnelState.value = TunnelState.STOPPED
        Log.i(TAG, "Tunnel stopped")
    }

    /**
     * 初始化 libbox（全局一次）
     * 调用 Libbox.setup() 配置工作目录
     */
    private suspend fun ensureInitialized() {
        if (boxInitialized) return

        val baseDir = applicationContext.filesDir.absolutePath
        val workDir = applicationContext.getDir("singbox", 0).absolutePath
        val tempDir = applicationContext.cacheDir.absolutePath

        Log.i(TAG, "Setting up libbox: base=$baseDir, work=$workDir")

        val options = SetupOptions().apply {
            setBasePath(baseDir)
            setWorkingPath(workDir)
            setTempPath(tempDir)
            setFixAndroidStack(true)
        }
        Libbox.setup(options)
        boxInitialized = true
        Log.i(TAG, "libbox version: ${Libbox.version()}")
    }

    // =====================================================
    // PlatformInterface 实现
    // =====================================================

    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun() called, creating VPN interface...")

        val builder = Builder()
            .setSession("Pulse VPN")
            .setMtu(options.mtu.takeIf { it > 0 } ?: 9000)

        // IPv4 地址
        val inet4Addr = options.inet4Address
        while (inet4Addr.hasNext()) {
            val prefix = inet4Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        // IPv6 地址
        val inet6Addr = options.inet6Address
        while (inet6Addr.hasNext()) {
            val prefix = inet6Addr.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        // 路由：添加 libbox 提供的路由，并始终补充 IPv4 默认路由确保所有流量进入 TUN
        var hasDefaultV4 = false
        val inet4Route = options.inet4RouteAddress
        while (inet4Route.hasNext()) {
            val prefix = inet4Route.next()
            if (prefix.prefix() == 0) hasDefaultV4 = true
            builder.addRoute(prefix.address(), prefix.prefix())
        }
        var hasDefaultV6 = false
        val inet6Route = options.inet6RouteAddress
        while (inet6Route.hasNext()) {
            val prefix = inet6Route.next()
            if (prefix.prefix() == 0) hasDefaultV6 = true
            builder.addRoute(prefix.address(), prefix.prefix())
        }
        if (!hasDefaultV4) builder.addRoute("0.0.0.0", 0)
        if (!hasDefaultV6) builder.addRoute("::", 0)

        // DNS
        try {
            val dnsServer = options.getDNSServerAddress()
            if (dnsServer != null) {
                builder.addDnsServer(dnsServer.value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get DNS server address: ${e.message}")
        }
        builder.addDnsServer("1.1.1.1")

        // 包过滤
        try {
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                builder.addDisallowedApplication(exclude.next())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply exclude packages: ${e.message}")
        }
        try {
            val include = options.includePackage
            while (include.hasNext()) {
                builder.addAllowedApplication(include.next())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply include packages: ${e.message}")
        }

        // 不拦截本 APP 自身流量
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        tunFd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() returned null，请检查 VPN 权限")

        Log.i(TAG, "TUN interface created, fd = ${tunFd!!.fd}")
        return tunFd!!.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        Log.i(TAG, "autoDetectInterfaceControl($fd) called")
        if (!protect(fd)) {
            Log.e(TAG, "protect($fd) failed")
            throw IllegalStateException("protect($fd) failed")
        }
        Log.i(TAG, "protect($fd) succeeded")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): io.nekohasekai.libbox.ConnectionOwner? = io.nekohasekai.libbox.ConnectionOwner()

    override fun readWIFIState(): WIFIState? = null

    override fun getInterfaces(): NetworkInterfaceIterator? {
        // 返回真实的系统网络接口列表，让 libbox 能找到底层接口（wlan0 等）
        val interfaces = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        try {
            val enum = java.net.NetworkInterface.getNetworkInterfaces()
            while (enum.hasMoreElements()) {
                val ni = enum.nextElement()
                if (ni.isUp && !ni.isLoopback && !ni.isVirtual &&
                    !ni.name.startsWith("tun") && !ni.name.startsWith("wg") && ni.mtu > 0
                ) {
                    val libNi = io.nekohasekai.libbox.NetworkInterface()
                    libNi.setName(ni.name)
                    libNi.setIndex(ni.index)
                    libNi.setMTU(ni.mtu)
                    libNi.setType(interfaceType(ni.name))
                    val addrIter = StringListIterator(ni.interfaceAddresses.mapNotNull { it.address.hostAddress })
                    libNi.setAddresses(addrIter)
                    interfaces.add(libNi)
                    Log.i(TAG, "Found interface: ${ni.name} idx=${ni.index} mtu=${ni.mtu}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate network interfaces: ${e.message}")
        }
        return if (interfaces.isEmpty()) null else NetworkInterfaceListIterator(interfaces)
    }

    private fun interfaceType(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
        name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        listener ?: return
        synchronized(interfaceListeners) { interfaceListeners.add(listener) }
        getSystemService(ConnectivityManager::class.java)?.let(::scheduleNetworkRefresh)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        listener ?: return
        synchronized(interfaceListeners) { interfaceListeners.remove(listener) }
    }

    override fun clearDNSCache() {}

    override fun systemCertificates(): StringIterator? = null

    override fun sendNotification(notification: LibboxNotification?) {}

    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? = null

    // =====================================================
    // CommandServerHandler 实现
    // =====================================================

    override fun serviceReload() {
        Log.i(TAG, "Service reloaded")
    }

    override fun serviceStop() {
        Log.i(TAG, "Service stopped by command server")
        if (!expectedStop && configStore.desiredRunning()) {
            recoverTunnel(IllegalStateException("libbox service stopped"))
        } else {
            serviceScope.launch(Dispatchers.Main) {
                _tunnelState.value = TunnelState.STOPPED
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, "[libbox] ${message?.replace(Regex("(?i)(password|token|secret)[=:]\\s*[^,\\s}]+"), "${'$'}1=<redacted>")}")
    }

    // =====================================================
    // 通知栏
    // =====================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

/** 字符串列表迭代器，用于向 libbox 传递地址列表 */
private class StringListIterator(private val items: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun len(): Int = items.size
    override fun next(): String = items[index++]
}

/** 网络接口列表迭代器，用于向 libbox 传递系统网络接口 */
private class NetworkInterfaceListIterator(private val items: List<io.nekohasekai.libbox.NetworkInterface>) : NetworkInterfaceIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < items.size
    override fun next(): io.nekohasekai.libbox.NetworkInterface = items[index++]
}
