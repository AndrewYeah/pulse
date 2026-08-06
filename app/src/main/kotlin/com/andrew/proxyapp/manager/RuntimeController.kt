package com.andrew.proxyapp.manager

import com.andrew.proxyapp.config.SingBoxConfigBuilder
import com.andrew.proxyapp.data.ConnectionRecord
import com.andrew.proxyapp.data.NodeLatency
import com.andrew.proxyapp.data.RouteMode
import com.andrew.proxyapp.data.RuntimeSnapshot
import com.andrew.proxyapp.data.RuntimePhase
import com.andrew.proxyapp.data.RuntimeErrorCategory
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object RuntimeController : CommandClientHandler {
    @Volatile
    var onDisconnected: ((String?) -> Unit)? = null
    private val _snapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = _snapshot.asStateFlow()
    private val _connections = MutableStateFlow<List<ConnectionRecord>>(emptyList())
    val connections: StateFlow<List<ConnectionRecord>> = _connections.asStateFlow()
    private val _latencies = MutableStateFlow<Map<String, NodeLatency>>(emptyMap())
    val latencies: StateFlow<Map<String, NodeLatency>> = _latencies.asStateFlow()
    private val _selectedConfigId = MutableStateFlow("")
    val selectedConfigId: StateFlow<String> = _selectedConfigId.asStateFlow()
    private val _mode = MutableStateFlow(RouteMode.RULE)
    val mode: StateFlow<RouteMode> = _mode.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val connectionMap = linkedMapOf<String, ConnectionRecord>()
    private var client: CommandClient? = null
    @Volatile private var intentionalDisconnect = false

    @Synchronized
    fun setLifecycle(
        phase: RuntimePhase,
        desiredRunning: Boolean = _snapshot.value.desiredRunning,
        retryAttempt: Int = _snapshot.value.retryAttempt,
        category: RuntimeErrorCategory? = null,
        message: String? = null
    ) {
        _snapshot.value = _snapshot.value.copy(
            phase = phase,
            desiredRunning = desiredRunning,
            retryAttempt = retryAttempt,
            errorCategory = category,
            errorMessage = message,
            lastTransitionAt = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun attach(): CommandClient {
        detach()
        val options = CommandClientOptions().apply {
            setStatusInterval(1_000_000_000L)
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandConnections)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandClashMode)
            addCommand(Libbox.CommandLog)
        }
        return CommandClient(this, options).also {
            client = it
            intentionalDisconnect = false
            it.connect()
        }
    }

    @Synchronized
    fun detach() {
        val oldClient = client
        client = null
        if (oldClient != null) {
            intentionalDisconnect = true
            runCatching { oldClient.disconnect() }
        }
        connectionMap.clear()
        _connections.value = emptyList()
        _snapshot.value = _snapshot.value.copy(
            phase = RuntimePhase.STOPPED,
            startedAt = 0,
            memoryBytes = 0,
            connectionsIn = 0,
            connectionsOut = 0,
            uploadSpeed = 0,
            downloadSpeed = 0,
            uploadTotal = 0,
            downloadTotal = 0,
            retryAttempt = 0,
            errorCategory = null,
            errorMessage = null,
            lastTransitionAt = System.currentTimeMillis()
        )
        _selectedConfigId.value = ""
    }

    suspend fun selectConfig(configId: String): Boolean {
        val accepted = synchronized(this) {
            val current = client ?: return@synchronized false
            runCatching {
                current.selectOutbound("proxy-out", SingBoxConfigBuilder.nodeTag(configId))
            }.isSuccess
        }
        if (!accepted) return false
        return withTimeoutOrNull(3_000) {
            selectedConfigId.filter { it == configId }.first()
            true
        } ?: false
    }

    fun setMode(mode: RouteMode) {
        client?.setClashMode(if (mode == RouteMode.GLOBAL) "Global" else "Rule")
        _mode.value = mode
    }

    fun testAllNodes() = client?.urlTest("proxy-out")
    fun closeConnection(id: String) = client?.closeConnection(id)
    fun closeAllConnections() = client?.closeConnections()
    fun clearLogsNow() {
        client?.clearLogs()
        _logs.value = emptyList()
    }

    override fun connected() {
        val startedAt = runCatching { client?.getStartedAt() ?: 0 }.getOrDefault(0)
        _snapshot.value = _snapshot.value.copy(startedAt = startedAt)
    }
    override fun disconnected(message: String?) {
        if (intentionalDisconnect) {
            intentionalDisconnect = false
            return
        }
        if (!message.isNullOrBlank()) appendLog(message)
        onDisconnected?.invoke(message)
    }
    override fun clearLogs() { _logs.value = emptyList() }
    override fun setDefaultLogLevel(level: Int) = Unit

    override fun initializeClashMode(modes: StringIterator?, currentMode: String?) {
        updateClashMode(currentMode)
    }

    override fun updateClashMode(mode: String?) {
        _mode.value = if (mode.equals("Global", true)) RouteMode.GLOBAL else RouteMode.RULE
    }

    override fun writeStatus(status: StatusMessage?) {
        status ?: return
        _snapshot.value = _snapshot.value.copy(
            memoryBytes = status.memory,
            connectionsIn = status.connectionsIn,
            connectionsOut = status.connectionsOut,
            uploadSpeed = status.uplink,
            downloadSpeed = status.downlink,
            uploadTotal = status.uplinkTotal,
            downloadTotal = status.downlinkTotal
        )
    }

    @Synchronized
    override fun writeConnectionEvents(events: ConnectionEvents?) {
        events ?: return
        if (events.reset) connectionMap.clear()
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            when (event.type.toLong()) {
                Libbox.ConnectionEventClosed -> connectionMap.remove(event.id)
                Libbox.ConnectionEventNew, Libbox.ConnectionEventUpdate -> event.connection?.let { connection ->
                    connectionMap[event.id] = ConnectionRecord(
                        event.id, connection.network, connection.source, connection.destination,
                        connection.domain, connection.protocol, connection.rule, connection.outbound,
                        connection.uplinkTotal, connection.downlinkTotal, connection.createdAt
                    )
                }
            }
        }
        _connections.value = connectionMap.values.sortedByDescending { it.createdAt }
    }

    override fun writeGroups(groups: OutboundGroupIterator?) {
        groups ?: return
        val latencyMap = _latencies.value.toMutableMap()
        while (groups.hasNext()) {
            val group = groups.next()
            if (group.tag != "proxy-out") continue
            _selectedConfigId.value = group.selected.removePrefix("node-")
            val items = group.items
            while (items.hasNext()) {
                val item = items.next()
                val id = item.tag.removePrefix("node-")
                if (item.urlTestDelay > 0) latencyMap[id] = NodeLatency(id, item.urlTestDelay, item.urlTestTime)
            }
        }
        _latencies.value = latencyMap
    }

    override fun writeLogs(logs: LogIterator?) {
        logs ?: return
        while (logs.hasNext()) appendLog(logs.next().message)
    }

    private fun appendLog(message: String?) {
        if (message.isNullOrBlank()) return
        _logs.value = (_logs.value + redact(message)).takeLast(500)
    }

    private fun redact(message: String): String = message
        .replace(Regex("(?i)(hysteria2|vless|vmess|trojan|tuic|ss|anytls)://[^\\s/@]+@"), "${'$'}1://<redacted>@")
        .replace(Regex("(?i)(password|passwd|token|uuid|secret)[=:]\\s*[^,\\s}]+"), "${'$'}1=<redacted>")
}
