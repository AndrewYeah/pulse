package com.andrew.proxyapp.manager

import com.andrew.proxyapp.data.RuntimeErrorCategory
import com.andrew.proxyapp.data.RuntimePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Serializes VPN lifecycle operations and owns bounded recovery retries.
 * The coordinator is deliberately platform independent so its transitions can
 * be exercised without starting a real VpnService.
 */
class TunnelRuntimeCoordinator(
    private val scope: CoroutineScope,
    private val maxRetries: Int = 5,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val onTransition: (RuntimePhase, Int, RuntimeErrorCategory?, Throwable?) -> Unit
) {
    private val mutex = Mutex()
    @Volatile private var desiredRunning = false
    @Volatile private var currentJob: Job? = null
    @Volatile private var recoveryJob: Job? = null
    private var phase: RuntimePhase = RuntimePhase.STOPPED

    @Synchronized
    fun start(block: suspend () -> Unit): Job {
        desiredRunning = true
        val existing = currentJob
        if (existing?.isActive == true) return existing
        val job = scope.launch {
            mutex.withLock {
                val running = currentJob
                if (running != null && running !== coroutineContext[Job] && running.isActive) return@withLock
                try {
                    runWithRecovery(block)
                } finally {
                    if (currentJob === coroutineContext[Job]) currentJob = null
                }
            }
        }
        currentJob = job
        return job
    }

    fun stop(block: suspend () -> Unit): Job {
        desiredRunning = false
        recoveryJob?.cancel()
        return scope.launch {
        val toCancel = currentJob
        if (toCancel != null && toCancel !== coroutineContext[Job]) {
            toCancel.cancelAndJoinIfNeeded()
        }
        mutex.withLock {
            transition(RuntimePhase.STOPPING, 0, null, null)
            runCatching { block() }
                .onFailure { transition(RuntimePhase.FAILED, 0, RuntimeErrorCategory.CORE, it) }
            transition(RuntimePhase.STOPPED, 0, null, null)
        }
    }
    }

    fun reload(stopBlock: suspend () -> Unit, startBlock: suspend () -> Unit): Job = scope.launch {
        stop(stopBlock).join()
        start(startBlock).join()
    }

    @Synchronized
    fun recover(cause: Throwable, block: suspend () -> Unit): Job {
        desiredRunning = true
        recoveryJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            try {
                currentJob?.cancelAndJoinIfNeeded()
                if (!desiredRunning) return@launch
                onTransition(RuntimePhase.RECONNECTING, 0, classify(cause), cause)
                if (desiredRunning) start(block).join()
            } finally {
                if (recoveryJob === coroutineContext[Job]) recoveryJob = null
            }
        }
        recoveryJob = job
        return job
    }

    fun requestStop() {
        desiredRunning = false
        recoveryJob?.cancel()
        currentJob?.cancel()
    }

    fun isDesiredRunning(): Boolean = desiredRunning

    private suspend fun runWithRecovery(block: suspend () -> Unit) {
        var attempt = 0
        while (desiredRunning) {
            coroutineContext.ensureActive()
            transition(if (attempt == 0) RuntimePhase.STARTING else RuntimePhase.RECONNECTING, attempt, null, null)
            try {
                block()
                transition(RuntimePhase.RUNNING, attempt, null, null)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val category = classify(error)
                if (!desiredRunning || category == RuntimeErrorCategory.PERMISSION ||
                    category == RuntimeErrorCategory.CONFIGURATION || attempt >= maxRetries
                ) {
                    transition(RuntimePhase.FAILED, attempt, category, error)
                    return
                }
                attempt++
                transition(RuntimePhase.RECONNECTING, attempt, category, error)
                retryDelay(backoffMillis(attempt))
            }
        }
    }

    private suspend fun Job.cancelAndJoinIfNeeded() {
        if (isActive) {
            cancel()
            join()
        }
    }

    private fun transition(
        next: RuntimePhase,
        attempt: Int,
        category: RuntimeErrorCategory?,
        error: Throwable?
    ) {
        phase = next
        onTransition(next, attempt, category, error)
    }

    private fun classify(error: Throwable): RuntimeErrorCategory {
        val text = (error.message.orEmpty() + " " + error::class.java.simpleName).lowercase()
        return when {
            error is IllegalArgumentException -> RuntimeErrorCategory.CONFIGURATION
            "permission" in text || "vpn" in text || "protect" in text -> RuntimeErrorCategory.PERMISSION
            "config" in text || "invalid" in text || "unsupported" in text -> RuntimeErrorCategory.CONFIGURATION
            "binder" in text || "deadobject" in text || "closed" in text -> RuntimeErrorCategory.BINDER
            "timeout" in text || "network" in text || "interface" in text || "connection" in text -> RuntimeErrorCategory.NETWORK
            "libbox" in text || "sing-box" in text || "core" in text -> RuntimeErrorCategory.CORE
            else -> RuntimeErrorCategory.UNKNOWN
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(30_000L)
}
