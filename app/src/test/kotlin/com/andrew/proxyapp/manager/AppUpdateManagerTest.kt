package com.andrew.proxyapp.manager

import com.andrew.proxyapp.data.AppUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AppUpdateManagerTest {

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(ReleaseUpdateChecker.compareVersions("v1.1.10", "1.1.2") > 0)
        assertTrue(ReleaseUpdateChecker.compareVersions("1.2.0", "1.1.99") > 0)
        assertEquals(0, ReleaseUpdateChecker.compareVersions("v1.1.2", "1.1.2.0"))
        assertTrue(ReleaseUpdateChecker.compareVersions("1.1.1", "1.1.2") < 0)
    }

    @Test
    fun parsesStableReleaseAndNormalizesTag() {
        val release = ReleaseUpdateChecker.parseRelease(
            """
            {
              "tag_name": "1.2.0",
              "html_url": "https://github.com/AndrewYeah/pulse/releases/tag/v1.2.0",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent()
        )

        assertEquals(AppUpdateInfo("v1.2.0", "https://github.com/AndrewYeah/pulse/releases/tag/v1.2.0"), release)
    }

    @Test
    fun rejectsDraftAndUntrustedReleaseUrls() {
        val draft = """
            {"tag_name":"v1.2.0","html_url":"https://github.com/AndrewYeah/pulse/releases/tag/v1.2.0","draft":true}
        """.trimIndent()
        assertTrue(runCatching { ReleaseUpdateChecker.parseRelease(draft) }.isFailure)

        val untrusted = """
            {"tag_name":"v1.2.0","html_url":"https://example.com/pulse/releases/tag/v1.2.0"}
        """.trimIndent()
        assertTrue(runCatching { ReleaseUpdateChecker.parseRelease(untrusted) }.isFailure)
        assertFalse(ReleaseUpdateChecker.isValidReleaseUrl("http://github.com/AndrewYeah/pulse/releases/tag/v1.2.0"))

        val invalidTag = """
            {"tag_name":"latest","html_url":"https://github.com/AndrewYeah/pulse/releases/tag/latest"}
        """.trimIndent()
        assertTrue(runCatching { ReleaseUpdateChecker.parseRelease(invalidTag) }.isFailure)
    }

    @Test
    fun reportsAvailableAndUpToDateResults() = runBlocking {
        val available = ReleaseUpdateChecker(
            currentVersion = "1.1.2",
            fetcher = StaticFetcher(stableJson("v1.1.3"))
        ).check()
        assertEquals(ReleaseCheckResult.Available(AppUpdateInfo("v1.1.3", releaseUrl("v1.1.3"))), available)

        val current = ReleaseUpdateChecker(
            currentVersion = "1.1.3",
            fetcher = StaticFetcher(stableJson("v1.1.3"))
        ).check()
        assertEquals(ReleaseCheckResult.UpToDate, current)
    }

    @Test
    fun cachesAvailableUpdateAndClearsItWhenCurrent() = runBlocking {
        val store = FakeStore()
        val manager = AppUpdateManager(
            currentVersion = "1.1.2",
            store = store,
            fetcher = StaticFetcher(stableJson("v1.1.3")),
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertTrue(manager.state.value is AppUpdateState.Idle)
        assertEquals(ReleaseCheckResult.Available(AppUpdateInfo("v1.1.3", releaseUrl("v1.1.3"))), manager.checkNow().await())
        assertEquals(AppUpdateInfo("v1.1.3", releaseUrl("v1.1.3")), store.value)
        assertTrue(manager.state.value is AppUpdateState.Available)

        val alreadyCurrent = FakeStore(AppUpdateInfo("v1.1.3", releaseUrl("v1.1.3")))
        val currentManager = AppUpdateManager(
            currentVersion = "1.1.3",
            store = alreadyCurrent,
            fetcher = StaticFetcher(stableJson("v1.1.3")),
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        assertNull(alreadyCurrent.value)
        assertTrue(currentManager.state.value is AppUpdateState.Idle)
    }

    @Test
    fun failedCheckKeepsPreviouslyCachedUpdate() = runBlocking {
        val cached = AppUpdateInfo("v1.1.3", releaseUrl("v1.1.3"))
        val store = FakeStore(cached)
        val manager = AppUpdateManager(
            currentVersion = "1.1.2",
            store = store,
            fetcher = object : ReleaseFetcher {
                override suspend fun fetch(): String = throw IOException("offline")
            },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        assertEquals(ReleaseCheckResult.Failed::class, manager.checkNow().await()::class)
        assertEquals(cached, store.value)
        assertEquals(AppUpdateState.Available(cached), manager.state.value)
    }

    private class StaticFetcher(private val body: String) : ReleaseFetcher {
        override suspend fun fetch(): String = body
    }

    private class FakeStore(initial: AppUpdateInfo? = null) : AppUpdateStore {
        var value: AppUpdateInfo? = initial

        override fun read(): AppUpdateInfo? = value
        override fun save(update: AppUpdateInfo) { value = update }
        override fun clear() { value = null }
    }

    private fun stableJson(version: String) =
        """{"tag_name":"$version","html_url":"${releaseUrl(version)}","draft":false,"prerelease":false}"""

    private fun releaseUrl(version: String) =
        "https://github.com/AndrewYeah/pulse/releases/tag/$version"
}
