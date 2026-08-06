package com.andrew.proxyapp.manager

import com.andrew.proxyapp.data.RuntimePhase
import com.andrew.proxyapp.data.RuntimeErrorCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRuntimeCoordinatorTest {
    @Test
    fun duplicateStartsShareOneOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var starts = 0
        val coordinator = TunnelRuntimeCoordinator(scope, maxRetries = 0) { _, _, _, _ -> }
        val first = coordinator.start { starts++; delay(30) }
        val second = coordinator.start { starts++; delay(30) }
        first.join(); second.join()
        assertEquals(1, starts)
        coordinator.stop {}.join()
        scope.cancel()
    }

    @Test
    fun retriesTransientFailureAndReachesRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phases = mutableListOf<RuntimePhase>()
        var attempts = 0
        val coordinator = TunnelRuntimeCoordinator(scope, maxRetries = 2, retryDelay = {}) { phase, _, _, _ -> phases += phase }
        coordinator.start {
            attempts++
            if (attempts == 1) error("network timeout")
        }.join()
        assertEquals(2, attempts)
        assertTrue(phases.contains(RuntimePhase.RECONNECTING))
        assertEquals(RuntimePhase.RUNNING, phases.last())
        coordinator.stop {}.join()
        scope.cancel()
    }

    @Test
    fun stopCancelsPendingRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var attempts = 0
        val coordinator = TunnelRuntimeCoordinator(
            scope,
            maxRetries = 5,
            retryDelay = {
                retryStarted.complete(Unit)
                releaseRetry.await()
            }
        ) { _, _, _, _ -> }

        coordinator.start {
            attempts++
            error("network timeout")
        }
        retryStarted.await()
        coordinator.stop {}.join()
        releaseRetry.complete(Unit)

        assertEquals(1, attempts)
        assertTrue(!coordinator.isDesiredRunning())
        scope.cancel()
    }

    @Test
    fun permanentConfigurationFailureDoesNotRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var attempts = 0
        var finalCategory: RuntimeErrorCategory? = null
        val coordinator = TunnelRuntimeCoordinator(scope, maxRetries = 5, retryDelay = {}) { phase, _, category, _ ->
            if (phase == RuntimePhase.FAILED) finalCategory = category
        }

        coordinator.start {
            attempts++
            throw IllegalArgumentException("invalid config")
        }.join()

        assertEquals(1, attempts)
        assertEquals(RuntimeErrorCategory.CONFIGURATION, finalCategory)
        scope.cancel()
    }

    @Test
    fun binderDisconnectCanRecoverToRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phases = mutableListOf<RuntimePhase>()
        var starts = 0
        val coordinator = TunnelRuntimeCoordinator(scope, maxRetries = 0, retryDelay = {}) { phase, _, _, _ ->
            phases += phase
        }

        coordinator.start { starts++ }.join()
        coordinator.recover(IllegalStateException("binder closed")) { starts++ }.join()

        assertEquals(2, starts)
        assertTrue(phases.contains(RuntimePhase.RECONNECTING))
        assertEquals(RuntimePhase.RUNNING, phases.last())
        coordinator.stop {}.join()
        scope.cancel()
    }

    @Test
    fun userStopWinsAgainstPendingRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recoveryEntered = CompletableDeferred<Unit>()
        val allowRecovery = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = TunnelRuntimeCoordinator(scope, maxRetries = 0, retryDelay = {}) { _, _, _, _ -> }

        coordinator.start { starts++ }.join()
        coordinator.recover(IllegalStateException("binder closed")) {
            recoveryEntered.complete(Unit)
            allowRecovery.await()
            starts++
        }
        recoveryEntered.await()
        coordinator.stop {}.join()
        allowRecovery.complete(Unit)

        assertEquals(1, starts)
        assertTrue(!coordinator.isDesiredRunning())
        scope.cancel()
    }
}
