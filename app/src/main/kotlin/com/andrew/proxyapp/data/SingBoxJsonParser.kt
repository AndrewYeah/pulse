package com.andrew.proxyapp.data

import android.util.Log
import org.json.JSONObject

/**
 * sing-box JSON 订阅解析器。
 * 从 sing-box 配置的 outbounds 数组提取节点，转换为 ProxyConfig。
 *
 * 支持的 outbound type: vmess, vless, hysteria2, tuic, shadowsocks, anytls
 */
object SingBoxJsonParser {

    private const val TAG = "SingBoxJsonParser"
    private val NON_PROXY_OUTBOUNDS = setOf(
        "direct", "block", "dns", "selector", "urltest", "compatible", "drop", "reject"
    )
    private val SUPPORTED_PROXY_OUTBOUNDS = setOf(
        "vmess", "vless", "hysteria2", "tuic", "shadowsocks", "anytls", "trojan", "socks", "http"
    )

    fun parse(jsonText: String, subscriptionId: String): List<ProxyConfig> =
        parseDetailed(jsonText, subscriptionId).nodes

    fun parseDetailed(jsonText: String, subscriptionId: String): SubscriptionParseResult {
        return try {
            val root = JSONObject(jsonText)
            val hasServerInbounds = root.optJSONArray("inbounds")?.length()?.let { it > 0 } == true ||
                root.optJSONObject("inbound") != null
            val outbounds = root.optJSONArray("outbounds")
                ?: return SubscriptionParseResult(
                    SubscriptionFormat.SING_BOX_JSON,
                    emptyList(),
                    listOf(
                        ParseIssue(
                            -1,
                            "",
                            if (hasServerInbounds) "server_config_detected" else "outbounds_missing",
                            if (hasServerInbounds) {
                                "server-side sing-box configuration detected; import a client URI or client configuration"
                            } else {
                                "sing-box JSON has no outbounds"
                            },
                            true
                        )
                    )
                )
            val nodes = mutableListOf<ProxyConfig>()
            val issues = mutableListOf<ParseIssue>()
            for (i in 0 until outbounds.length()) {
                val obj = outbounds.optJSONObject(i)
                if (obj == null) {
                    issues += ParseIssue(i, "", "outbound_invalid", "outbound must be an object")
                    continue
                }
                val type = obj.optString("type", "").lowercase()
                val tag = obj.optString("tag", "Node-${i + 1}")
                if (type in NON_PROXY_OUTBOUNDS) continue
                if (type !in SUPPORTED_PROXY_OUTBOUNDS) {
                    issues += ParseIssue(i, tag, "outbound_unsupported", "unsupported sing-box outbound: $type")
                    continue
                }
                val server = obj.optString("server", "")
                val port = obj.optInt("server_port", obj.optInt("port", 0))
                if (server.isBlank() || port !in 1..65535) {
                    issues += ParseIssue(i, tag, "outbound_invalid", "proxy outbound requires a valid server and port")
                    continue
                }
                val config = parseOutbound(obj, subscriptionId) ?: continue
                nodes.add(config)
            }
            val noClientNodeIssue = if (nodes.isEmpty() && issues.isEmpty()) {
                ParseIssue(
                    -1,
                    "",
                    if (hasServerInbounds) "server_config_detected" else "proxy_outbounds_missing",
                    if (hasServerInbounds) {
                        "server-side sing-box configuration detected; import a client URI or client configuration"
                    } else {
                        "sing-box JSON contains no importable client proxy outbounds"
                    },
                    true
                )
            } else null
            Log.i(TAG, "Parsed ${nodes.size} nodes from sing-box JSON")
            SubscriptionParseResult(
                SubscriptionFormat.SING_BOX_JSON,
                nodes,
                issues + listOfNotNull(noClientNodeIssue)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sing-box JSON: ${e.message}", e)
            SubscriptionParseResult(
                SubscriptionFormat.SING_BOX_JSON,
                emptyList(),
                listOf(ParseIssue(-1, "", "json_invalid", e.message ?: "invalid JSON", true))
            )
        }
    }

    private fun parseOutbound(obj: JSONObject, subscriptionId: String): ProxyConfig? {
        val type = obj.optString("type", "")
        val tag = obj.optString("tag", "Node")
        val server = obj.optString("server", "")
        val port = obj.optInt("server_port", obj.optInt("port", 0))
        if (server.isBlank() || port <= 0) return null

        val config = ProxyConfig(
            name = tag,
            server = server,
            port = port,
            subscriptionId = subscriptionId,
            rawUri = "sing-box://$tag"
        )

        when (type) {
            "vmess" -> {
                config.protocol = ProtocolType.VMESS
                config.uuid = obj.optString("uuid", "")
                config.alterId = obj.optInt("alter_id", 0)
                config.vmessSecurity = obj.optString("security", "auto")
                parseTls(obj, config)
                parseTransport(obj, config)
            }
            "vless" -> {
                config.protocol = ProtocolType.VLESS
                config.uuid = obj.optString("uuid", "")
                config.flow = obj.optString("flow", "")
                config.packetEncoding = obj.optString("packet_encoding", "")
                parseTls(obj, config)
                parseTransport(obj, config)
            }
            "hysteria2" -> {
                config.protocol = ProtocolType.HYSTERIA2
                config.password = obj.optString("password", "")
                parseTls(obj, config)
                config.upMbps = obj.optInt("up_mbps", 0)
                config.downMbps = obj.optInt("down_mbps", 0)
            }
            "tuic" -> {
                config.protocol = ProtocolType.TUIC
                config.uuid = obj.optString("uuid", "")
                config.password = obj.optString("password", "")
                parseTls(obj, config)
                config.congestionControl = obj.optString("congestion_control", "bbr")
            }
            "shadowsocks" -> {
                config.protocol = ProtocolType.SHADOWSOCKS
                config.ssMethod = obj.optString("method", "aes-256-gcm")
                config.password = obj.optString("password", "")
                config.tlsEnabled = false
            }
            "anytls" -> {
                config.protocol = ProtocolType.ANYTLS
                config.password = obj.optString("password", "")
                parseTls(obj, config)
            }
            "trojan" -> {
                config.protocol = ProtocolType.TROJAN
                config.password = obj.optString("password", "")
                parseTls(obj, config)
                parseTransport(obj, config)
            }
            "socks" -> {
                config.protocol = ProtocolType.SOCKS5
                config.username = obj.optString("username", "")
                config.password = obj.optString("password", "")
                config.tlsEnabled = false
            }
            "http" -> {
                config.protocol = ProtocolType.HTTP_PROXY
                config.username = obj.optString("username", "")
                config.password = obj.optString("password", "")
                config.tlsEnabled = false
            }
            else -> {
                Log.w(TAG, "Unsupported sing-box outbound type: $type")
                return null
            }
        }
        return config
    }

    private fun parseTls(obj: JSONObject, config: ProxyConfig) {
        val tls = obj.optJSONObject("tls") ?: return
        config.tlsEnabled = tls.optBoolean("enabled", true)
        config.sni = tls.optString("server_name", "")
        config.insecure = tls.optBoolean("insecure", false)
        tls.optJSONObject("utls")?.let { utls ->
            if (utls.optBoolean("enabled", false)) {
                config.fingerprint = utls.optString("fingerprint", "chrome")
            }
        }
        tls.optJSONObject("reality")?.let { reality ->
            if (reality.optBoolean("enabled", false)) {
                config.realityPublicKey = reality.optString("public_key", "")
                config.realityShortId = reality.optString("short_id", "")
            }
        }
        val alpn = tls.optJSONArray("alpn")
        if (alpn != null) {
            config.alpn = (0 until alpn.length()).joinToString(", ") { alpn.getString(it) }
        }
    }

    private fun parseTransport(obj: JSONObject, config: ProxyConfig) {
        val transport = obj.optJSONObject("transport") ?: return
        val type = transport.optString("type", "")
        config.transportType = when (type) {
            "ws" -> TransportType.WS
            "grpc" -> TransportType.GRPC
            "http" -> TransportType.HTTP
            "httpupgrade" -> TransportType.HTTPUPGRADE
            else -> TransportType.NONE
        }
        when (config.transportType) {
            TransportType.WS, TransportType.HTTPUPGRADE -> {
                config.wsPath = transport.optString("path", "/")
                val headers = transport.optJSONObject("headers")
                config.wsHost = headers?.optString("Host", config.sni) ?: config.sni
            }
            TransportType.GRPC -> {
                config.grpcServiceName = transport.optString("service_name", "")
            }
            TransportType.HTTP -> {
                config.wsPath = transport.optString("path", "/")
                val host = transport.optJSONArray("host")
                config.wsHost = host?.optString(0, config.sni) ?: config.sni
            }
            else -> {}
        }
    }
}
