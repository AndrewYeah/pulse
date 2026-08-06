package com.andrew.proxyapp.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Parses V2Ray/Xray JSON without importing its inbounds, DNS, or routing. */
object V2RayJsonParser {
    private const val TAG = "V2RayJsonParser"

    fun parse(jsonText: String, subscriptionId: String): SubscriptionParseResult {
        return try {
            val root = JSONObject(jsonText)
            val outbounds = root.optJSONArray("outbounds")
                ?: root.optJSONObject("outbound")?.let { JSONArray().put(it) }
                ?: return SubscriptionParseResult(
                    SubscriptionFormat.V2RAY_JSON,
                    emptyList(),
                    listOf(ParseIssue(-1, "", "outbounds_missing", "V2Ray JSON has no outbounds", true))
                )
            val nodes = mutableListOf<ProxyConfig>()
            val issues = mutableListOf<ParseIssue>()
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i)
                if (outbound == null) {
                    issues += ParseIssue(i, "", "outbound_invalid", "outbound must be an object")
                    continue
                }
                val parsed = parseOutbound(outbound, subscriptionId, i, issues)
                nodes += parsed
            }
            val normalized = nodes.map { StandardNodeMapper.normalize(it, subscriptionId, SubscriptionFormat.V2RAY_JSON) }
            val result = StandardNodeMapper.result(normalized, SubscriptionFormat.V2RAY_JSON)
            result.copy(issues = issues + result.issues)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse V2Ray JSON", error)
            SubscriptionParseResult(
                SubscriptionFormat.V2RAY_JSON,
                emptyList(),
                listOf(ParseIssue(-1, "", "json_invalid", error.message ?: "invalid JSON", true))
            )
        }
    }

    private fun parseOutbound(
        outbound: JSONObject,
        subscriptionId: String,
        index: Int,
        issues: MutableList<ParseIssue>
    ): List<ProxyConfig> {
        val type = outbound.optString("protocol", outbound.optString("type", "")).lowercase()
        if (type in setOf("freedom", "blackhole", "dns", "loopback", "dokodemo-door", "routing")) return emptyList()
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val tag = outbound.optString("tag", "Node-${index + 1}")
        val nodes = mutableListOf<ProxyConfig>()
        fun add(config: ProxyConfig) {
            config.subscriptionId = subscriptionId
            applyStreamSettings(outbound.optJSONObject("streamSettings"), config, issues, index)
            config.rawUri = "v2ray-json://$tag"
            config.sourceFormat = SubscriptionFormat.V2RAY_JSON.name.lowercase()
            nodes += config
        }
        when (type) {
            "vmess", "vless" -> {
                val servers = settings.optJSONArray("vnext") ?: JSONArray()
                for (j in 0 until servers.length()) {
                    val server = servers.optJSONObject(j) ?: continue
                    val users = server.optJSONArray("users") ?: JSONArray()
                    val user = users.optJSONObject(0) ?: JSONObject()
                    add(ProxyConfig(
                        name = if (servers.length() == 1) tag else "$tag-${j + 1}",
                        protocol = if (type == "vmess") ProtocolType.VMESS else ProtocolType.VLESS,
                        server = server.optString("address", ""),
                        port = server.optInt("port", 443),
                        uuid = user.optString("id", ""),
                        alterId = user.optInt("alterId", user.optInt("alter_id", 0)),
                        vmessSecurity = user.optString("security", "auto"),
                        flow = user.optString("flow", "")
                    ))
                }
            }
            "trojan" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                for (j in 0 until servers.length()) {
                    val server = servers.optJSONObject(j) ?: continue
                    add(ProxyConfig(
                        name = if (servers.length() == 1) tag else "$tag-${j + 1}",
                        protocol = ProtocolType.TROJAN,
                        server = server.optString("address", ""),
                        port = server.optInt("port", 443),
                        password = server.optString("password", ""),
                        flow = server.optString("flow", "")
                    ))
                }
            }
            "shadowsocks" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                for (j in 0 until servers.length()) {
                    val server = servers.optJSONObject(j) ?: continue
                    add(ProxyConfig(
                        name = if (servers.length() == 1) tag else "$tag-${j + 1}",
                        protocol = ProtocolType.SHADOWSOCKS,
                        server = server.optString("address", ""),
                        port = server.optInt("port", 443),
                        password = server.optString("password", ""),
                        ssMethod = server.optString("method", "aes-256-gcm"),
                        tlsEnabled = false
                    ))
                }
            }
            "socks", "http" -> {
                val servers = settings.optJSONArray("servers") ?: JSONArray()
                for (j in 0 until servers.length()) {
                    val server = servers.optJSONObject(j) ?: continue
                    val users = server.optJSONArray("users")
                    val user = users?.optJSONObject(0) ?: JSONObject()
                    add(ProxyConfig(
                        name = if (servers.length() == 1) tag else "$tag-${j + 1}",
                        protocol = if (type == "socks") ProtocolType.SOCKS5 else ProtocolType.HTTP_PROXY,
                        server = server.optString("address", ""),
                        port = server.optInt("port", if (type == "socks") 1080 else 8080),
                        username = user.optString("user", server.optString("username", "")),
                        password = user.optString("pass", server.optString("password", "")),
                        tlsEnabled = false
                    ))
                }
            }
            else -> issues += ParseIssue(index, tag, "outbound_unsupported", "unsupported V2Ray outbound: $type")
        }
        return nodes
    }

    private fun applyStreamSettings(
        stream: JSONObject?,
        config: ProxyConfig,
        issues: MutableList<ParseIssue>,
        index: Int
    ) {
        if (stream == null) return
        when (stream.optString("network", "tcp").lowercase()) {
            "ws" -> {
                config.transportType = TransportType.WS
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                config.wsPath = ws.optString("path", "/")
                config.wsHost = ws.optJSONObject("headers")?.optString("Host", "").orEmpty()
            }
            "grpc" -> {
                config.transportType = TransportType.GRPC
                config.grpcServiceName = stream.optJSONObject("grpcSettings")?.optString("serviceName", "").orEmpty()
            }
            "http", "h2" -> {
                config.transportType = TransportType.HTTP
                val http = stream.optJSONObject("httpSettings") ?: JSONObject()
                config.wsPath = http.optString("path", "/")
                config.wsHost = http.optJSONArray("host")?.optString(0, "").orEmpty()
            }
            "tcp" -> Unit
            "kcp", "quic", "xhttp" -> {
                config.unsupportedReason = "V2Ray transport ${stream.optString("network")} has no safe sing-box mapping"
                issues += ParseIssue(index, config.name, "transport_unsupported", config.unsupportedReason)
            }
            else -> issues += ParseIssue(index, config.name, "transport_unsupported", "unknown V2Ray transport")
        }
        when (stream.optString("security", "none").lowercase()) {
            "tls" -> {
                config.tlsEnabled = true
                val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()
                config.sni = tls.optString("serverName", "")
                config.insecure = tls.optBoolean("allowInsecure", false)
                config.fingerprint = tls.optString("fingerprint", "chrome")
                config.alpn = tls.optJSONArray("alpn")?.let { array ->
                    (0 until array.length()).joinToString(", ") { array.optString(it) }
                }.orEmpty()
            }
            "reality" -> {
                config.tlsEnabled = true
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject()
                config.sni = reality.optString("serverName", "")
                config.realityPublicKey = reality.optString("publicKey", "")
                config.realityShortId = reality.optString("shortId", "")
                config.fingerprint = reality.optString("fingerprint", "chrome")
            }
            "none", "" -> Unit
            else -> issues += ParseIssue(index, config.name, "tls_unsupported", "unknown V2Ray stream security")
        }
    }
}
