package com.andrew.proxyapp.manager

import android.util.Log
import com.andrew.proxyapp.BuildConfig
import com.andrew.proxyapp.data.AppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Checking(val cachedUpdate: AppUpdateInfo? = null) : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val update: AppUpdateInfo) : AppUpdateState
    data class Failed(val error: Throwable) : AppUpdateState
}

sealed interface ReleaseCheckResult {
    data class Available(val update: AppUpdateInfo) : ReleaseCheckResult
    data object UpToDate : ReleaseCheckResult
    data class Failed(val error: Throwable) : ReleaseCheckResult
}

interface ReleaseFetcher {
    suspend fun fetch(): String
}

interface AppUpdateStore {
    fun read(): AppUpdateInfo?
    fun save(update: AppUpdateInfo)
    fun clear()
}

/** Reads and compares the public GitHub latest-release response. */
class ReleaseUpdateChecker(
    private val currentVersion: String,
    private val fetcher: ReleaseFetcher = HttpReleaseFetcher
) {
    suspend fun check(): ReleaseCheckResult {
        return try {
            val release = parseRelease(fetcher.fetch())
            if (isNewerVersion(release.version, currentVersion)) {
                ReleaseCheckResult.Available(release)
            } else {
                ReleaseCheckResult.UpToDate
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ReleaseCheckResult.Failed(error)
        }
    }

    companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/AndrewYeah/pulse/releases/latest"

        private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+){2,})$")
        private const val releasePathPrefix = "/AndrewYeah/pulse/releases/"

        fun parseRelease(json: String): AppUpdateInfo {
            val payload = JSONObject(json)
            if (payload.optBoolean("draft", false) || payload.optBoolean("prerelease", false)) {
                throw IOException("latest release is not a stable release")
            }
            val version = normalizeVersion(payload.optString("tag_name"))
            val releaseUrl = payload.optString("html_url").trim()
            require(isValidReleaseUrl(releaseUrl)) { "invalid GitHub release URL" }
            return AppUpdateInfo(version = version, releaseUrl = releaseUrl)
        }

        fun isNewerVersion(candidate: String, current: String): Boolean =
            compareVersions(candidate, current) > 0

        fun compareVersions(left: String, right: String): Int {
            val leftParts = parseVersion(left)
                ?: throw IllegalArgumentException("invalid version: $left")
            val rightParts = parseVersion(right)
                ?: throw IllegalArgumentException("invalid version: $right")
            val length = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until length) {
                val comparison = (leftParts.getOrElse(index) { 0 })
                    .compareTo(rightParts.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }
            return 0
        }

        fun isValidReleaseUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.path.orEmpty().startsWith(releasePathPrefix)
        }

        private fun normalizeVersion(value: String): String {
            val parts = parseVersion(value)
                ?: throw IllegalArgumentException("invalid release tag: $value")
            return "v${parts.joinToString(".")}"
        }

        private fun parseVersion(value: String): List<Int>? {
            val normalized = value.trim()
            if (!versionPattern.matches(normalized)) return null
            return normalized.removePrefix("v").removePrefix("V")
                .split('.')
                .map { it.toIntOrNull() ?: return null }
        }
    }
}

/** Persists update notices and deduplicates startup/manual checks. */
class AppUpdateManager(
    private val currentVersion: String,
    private val store: AppUpdateStore,
    private val fetcher: ReleaseFetcher = HttpReleaseFetcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val lock = Any()
    private var inFlight: Deferred<ReleaseCheckResult>? = null
    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun checkOnStartup(): Deferred<ReleaseCheckResult> = startCheck()

    fun checkNow(): Deferred<ReleaseCheckResult> = startCheck()

    fun availableUpdateOrNull(): AppUpdateInfo? = when (val current = _state.value) {
        is AppUpdateState.Available -> current.update
        is AppUpdateState.Checking -> current.cachedUpdate
        else -> null
    }

    private fun startCheck(): Deferred<ReleaseCheckResult> {
        synchronized(lock) {
            inFlight?.let { return it }
            val cached = readValidCached()
            _state.value = AppUpdateState.Checking(cached)
            val job = scope.async {
                val result = ReleaseUpdateChecker(currentVersion, fetcher).check()
                applyResult(result)
                result
            }
            inFlight = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlight === job) inFlight = null
                }
            }
            return job
        }
    }

    private fun restoreState(): AppUpdateState =
        readValidCached()?.let { AppUpdateState.Available(it) } ?: AppUpdateState.Idle

    private fun readValidCached(): AppUpdateInfo? {
        val cached = runCatching { store.read() }.getOrNull() ?: return null
        val valid = runCatching {
            ReleaseUpdateChecker.isNewerVersion(cached.version, currentVersion) &&
                ReleaseUpdateChecker.isValidReleaseUrl(cached.releaseUrl)
        }.getOrDefault(false)
        if (valid) return cached
        runCatching { store.clear() }
        return null
    }

    private fun applyResult(result: ReleaseCheckResult) {
        when (result) {
            is ReleaseCheckResult.Available -> {
                runCatching { store.save(result.update) }
                    .onFailure { Log.w(TAG, "Could not cache available update", it) }
                _state.value = AppUpdateState.Available(result.update)
            }

            ReleaseCheckResult.UpToDate -> {
                runCatching { store.clear() }
                    .onFailure { Log.w(TAG, "Could not clear update cache", it) }
                _state.value = AppUpdateState.UpToDate
            }

            is ReleaseCheckResult.Failed -> {
                val cached = readValidCached()
                if (cached != null) {
                    _state.value = AppUpdateState.Available(cached)
                } else {
                    _state.value = AppUpdateState.Failed(result.error)
                }
                Log.w(TAG, "Release check failed: ${result.error.message}")
            }
        }
    }

    private companion object {
        const val TAG = "AppUpdateManager"
    }
}

class ConfigStoreUpdateStore(
    private val store: com.andrew.proxyapp.data.ConfigStore
) : AppUpdateStore {
    override fun read(): AppUpdateInfo? = store.getAvailableUpdate()

    override fun save(update: AppUpdateInfo) {
        store.saveAvailableUpdate(update)
    }

    override fun clear() {
        store.clearAvailableUpdate()
    }
}

private object HttpReleaseFetcher : ReleaseFetcher {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024

    override suspend fun fetch(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val connection = (URL(ReleaseUpdateChecker.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Pulse-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("GitHub API HTTP $responseCode")
            if (!connection.url.protocol.equals("https", ignoreCase = true) ||
                !connection.url.host.equals("api.github.com", ignoreCase = true)
            ) {
                throw IOException("GitHub API redirect must remain on api.github.com")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("GitHub API response is too large")
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
