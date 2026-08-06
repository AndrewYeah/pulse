package com.andrew.proxyapp.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/** Downloads and normalizes all supported subscription formats. */
object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 15_000
    private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024

    private val userAgents = listOf("v2rayNG/1.8.5", "clash-verge/v1.5.0", "sing-box")
    private val inFlightUpdates = mutableMapOf<String, CompletableDeferred<Int>>()
    @Volatile private var fetcher: SubscriptionFetcher = HttpSubscriptionFetcher

    interface SubscriptionFetcher {
        suspend fun fetch(url: String, userAgent: String): String
    }

    fun setFetcherForTests(value: SubscriptionFetcher?) {
        fetcher = value ?: HttpSubscriptionFetcher
    }

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        validateSubscriptionUrl(url)
        var lastError: Exception? = null
        for (ua in userAgents) {
            try {
                return@withContext fetcher.fetch(url, ua)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastError = error
                Log.w(TAG, "Fetch failed with UA=$ua: ${error.message}")
            }
        }
        throw lastError ?: IOException("subscription download failed")
    }

    fun parseDetailed(content: String, subscriptionId: String): SubscriptionParseResult {
        val normalizedContent = content.trim().removePrefix("\uFEFF")
        if (normalizedContent.isBlank()) {
            return SubscriptionParseResult(
                SubscriptionFormat.UNKNOWN,
                emptyList(),
                listOf(ParseIssue(-1, "", "content_empty", "subscription is empty", true))
            )
        }
        val detected = SubscriptionFormatDetector.detect(normalizedContent)
        if (detected == SubscriptionFormat.BASE64_URI) {
            val decoded = decodeIfNeeded(normalizedContent)
            val innerFormat = SubscriptionFormatDetector.detect(decoded)
            if (decoded != normalizedContent && innerFormat !in setOf(SubscriptionFormat.BASE64_URI, SubscriptionFormat.URI, SubscriptionFormat.UNKNOWN)) {
                return parseDetailed(decoded, subscriptionId).copy(decodedBase64 = true)
            }
        }
        val result = when (detected) {
            SubscriptionFormat.SING_BOX_JSON -> SubscriptionParseResult(
                SubscriptionFormat.SING_BOX_JSON,
                SingBoxJsonParser.parse(normalizedContent, subscriptionId)
            )
            SubscriptionFormat.V2RAY_JSON -> V2RayJsonParser.parse(normalizedContent, subscriptionId)
            SubscriptionFormat.CLASH_YAML -> parseClash(normalizedContent, subscriptionId)
            SubscriptionFormat.URI, SubscriptionFormat.BASE64_URI -> parseUriList(normalizedContent, subscriptionId)
            SubscriptionFormat.UNKNOWN -> SubscriptionParseResult(
                SubscriptionFormat.UNKNOWN,
                emptyList(),
                listOf(ParseIssue(-1, "", "format_unknown", "unsupported subscription format", true))
            )
        }
        val stableNodes = result.nodes.map { config ->
            StandardNodeMapper.normalize(config, subscriptionId, result.format)
        }
        val validated = StandardNodeMapper.result(stableNodes, result.format, result.decodedBase64)
        return validated.copy(issues = result.issues + validated.issues)
    }

    fun parseNodes(content: String, subscriptionId: String): List<ProxyConfig> =
        parseDetailed(content, subscriptionId).nodes

    suspend fun fetchAndParse(url: String, subscriptionId: String): SubscriptionParseResult {
        validateSubscriptionUrl(url)
        var lastError: Exception? = null
        for (ua in userAgents) {
            try {
                val result = parseDetailed(fetcher.fetch(url, ua), subscriptionId)
                if (result.nodes.isNotEmpty()) return result
                lastError = IOException("$ua returned no valid nodes")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw lastError ?: IOException("subscription download failed")
    }

    private fun parseClash(content: String, subscriptionId: String): SubscriptionParseResult {
        val nodes = ClashYamlParser.parse(content, subscriptionId)
        return SubscriptionParseResult(SubscriptionFormat.CLASH_YAML, nodes)
    }

    private fun parseUriList(content: String, subscriptionId: String): SubscriptionParseResult {
        val decoded = decodeIfNeeded(content)
        val wasBase64 = decoded != content
        val issues = mutableListOf<ParseIssue>()
        val nodes = decoded.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.contains("://") }
            .mapIndexedNotNull { index, line ->
                UriParser.parse(line)?.also {
                    it.subscriptionId = subscriptionId
                } ?: run {
                    issues += ParseIssue(index, line.take(48), "uri_invalid", "unsupported or invalid proxy URI")
                    null
                }
            }
            .toList()
        if (nodes.isEmpty()) issues += ParseIssue(-1, "", "nodes_empty", "no supported proxy URI found", true)
        return SubscriptionParseResult(
            if (wasBase64) SubscriptionFormat.BASE64_URI else SubscriptionFormat.URI,
            nodes,
            issues,
            wasBase64
        )
    }

    private fun decodeIfNeeded(content: String): String {
        if (content.contains("://")) return content
        val compact = content.replace("\n", "").replace("\r", "").replace(" ", "")
        val androidDecoded = runCatching { Base64.decode(compact, Base64.DEFAULT or Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        if (androidDecoded != null) return String(androidDecoded, Charsets.UTF_8)
        val padded = compact.padEnd((compact.length + 3) / 4 * 4, '=')
        return runCatching { String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8) }
            .recoverCatching { String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8) }
            .getOrDefault(content)
    }

    suspend fun update(subscription: Subscription, store: SubscriptionStore): Int {
        val (deferred, owner) = synchronized(inFlightUpdates) {
            val existing = inFlightUpdates[subscription.id]
            if (existing != null) existing to false
            else CompletableDeferred<Int>().also { inFlightUpdates[subscription.id] = it } to true
        }
        if (!owner) return deferred.await()
        try {
            val result = performUpdate(subscription, store)
            deferred.complete(result)
            return result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            synchronized(inFlightUpdates) {
                if (inFlightUpdates[subscription.id] === deferred) inFlightUpdates.remove(subscription.id)
            }
        }
    }

    private suspend fun performUpdate(subscription: Subscription, store: SubscriptionStore): Int {
            validateSubscriptionUrl(subscription.url)
            var lastError: Exception? = null
            val attemptAt = System.currentTimeMillis()
            for (ua in userAgents) {
                try {
                    val content = fetcher.fetch(subscription.url, ua)
                    val result = parseDetailed(content, subscription.id)
                    if (result.nodes.isEmpty()) {
                        lastError = IOException("$ua returned no valid nodes: ${result.issues.firstOrNull()?.message.orEmpty()}")
                        continue
                    }
                    val update = subscription.copy(
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = result.nodes.size,
                        lastAttempt = attemptAt,
                        lastError = ""
                    )
                    store.replaceSubscriptionConfigs(update, result.nodes)
                    Log.i(TAG, "Updated ${subscription.id} with UA=$ua nodes=${result.nodes.size}")
                    return result.nodes.size
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    lastError = error
                    Log.w(TAG, "Update failed with UA=$ua: ${error.message}")
                }
            }
            val failure = lastError ?: IOException("subscription update failed; previous nodes retained")
            store.saveSubscription(subscription.copy(lastAttempt = attemptAt, lastError = failure.message.orEmpty()))
            throw failure
    }

    private object HttpSubscriptionFetcher : SubscriptionFetcher {
        override suspend fun fetch(url: String, userAgent: String): String = withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, text/yaml, text/plain, */*")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("subscription redirect must remain HTTPS")
                }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RESPONSE_BYTES) throw IOException("subscription response is too large")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                decodeResponse(bytes, connection.contentType)
            } finally {
                connection.disconnect()
            }
        }

        private fun decodeResponse(bytes: ByteArray, contentType: String?): String {
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentType.orEmpty())?.groupValues?.getOrNull(1)
                ?.trim()?.trim('"')
            return runCatching {
                if (charset != null) bytes.toString(Charset.forName(charset)) else bytes.toString(Charsets.UTF_8)
            }.getOrElse { bytes.toString(Charsets.UTF_8) }
        }
    }

    private fun validateSubscriptionUrl(value: String) {
        val url = runCatching { URL(value) }.getOrElse { throw IOException("invalid subscription URL") }
        if (!url.protocol.equals("https", ignoreCase = true) || url.host.isNullOrBlank()) {
            throw IOException("subscription URL must use HTTPS")
        }
    }
}
