package com.andrew.proxyapp.data

data class RuntimeSnapshot(
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val desiredRunning: Boolean = false,
    val startedAt: Long = 0,
    val memoryBytes: Long = 0,
    val connectionsIn: Int = 0,
    val connectionsOut: Int = 0,
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0,
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
    val retryAttempt: Int = 0,
    val errorCategory: RuntimeErrorCategory? = null,
    val errorMessage: String? = null,
    val lastTransitionAt: Long = System.currentTimeMillis()
)

enum class RuntimePhase { STOPPED, STARTING, RUNNING, STOPPING, RECONNECTING, FAILED }

enum class RuntimeErrorCategory {
    PERMISSION,
    CONFIGURATION,
    NETWORK,
    BINDER,
    CORE,
    UNKNOWN
}

data class ConnectionRecord(
    val id: String,
    val network: String,
    val source: String,
    val destination: String,
    val domain: String,
    val protocol: String,
    val rule: String,
    val outbound: String,
    val uploadTotal: Long,
    val downloadTotal: Long,
    val createdAt: Long
)

data class NodeLatency(
    val configId: String,
    val delayMs: Int,
    val testedAt: Long
)
