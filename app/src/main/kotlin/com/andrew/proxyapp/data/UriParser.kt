package com.andrew.proxyapp.data

import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 代理 URI 解析器
 * 支持: vless://, vmess://, hysteria2://, tuic://, anytls://, ss://
 */
object UriParser {

    private const val TAG = "UriParser"

    fun parse(uri: String): ProxyConfig? {
        val trimmed = uri.trim()
        return try {
            when {
                trimmed.startsWith("vless://") -> parseVless(trimmed)
                trimmed.startsWith("vmess://") -> parseVmess(trimmed)
                trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
                trimmed.startsWith("tuic://") -> parseTuic(trimmed)
                trimmed.startsWith("anytls://") -> parseAnytls(trimmed)
                trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
                trimmed.startsWith("ssr://") -> parseShadowsocksR(trimmed)
                trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
                trimmed.startsWith("socks5://") || trimmed.startsWith("socks://") -> parseSocks(trimmed)
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> parseHttp(trimmed)
                else -> {
                    Log.w(TAG, "Unsupported protocol: ${trimmed.take(20)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URI: ${e.message}", e)
            null
        }
    }

    private fun parseVless(uri: String): ProxyConfig {
        val u = uri.toUri()
        val config = ProxyConfig(
            protocol = ProtocolType.VLESS,
            uuid = u.userInfo ?: "",
            server = u.host ?: "",
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "VLESS",
            rawUri = uri
        )
        val q = u.queryParameterNames
        config.tlsEnabled = q.contains("security") && (u.getQueryParameter("security") != "none")
        config.sni = u.getQueryParameter("sni") ?: u.getQueryParameter("peer") ?: ""
        config.insecure = u.getQueryParameter("allowInsecure") == "1" || u.getQueryParameter("insecure") == "1"
        config.flow = u.getQueryParameter("flow") ?: ""
        config.fingerprint = u.getQueryParameter("fp") ?: "chrome"
        config.alpn = u.getQueryParameter("alpn")?.replace(",", ", ") ?: ""
        config.realityPublicKey = u.getQueryParameter("pbk") ?: ""
        config.realityShortId = u.getQueryParameter("sid") ?: ""
        config.packetEncoding = u.getQueryParameter("packetEncoding")
            ?: u.getQueryParameter("packet_encoding")
            ?: ""

        val type = u.getQueryParameter("type") ?: "tcp"
        config.transportType = when (type) {
            "ws" -> TransportType.WS
            "grpc" -> TransportType.GRPC
            "http" -> TransportType.HTTP
            "httpupgrade" -> TransportType.HTTPUPGRADE
            else -> TransportType.NONE
        }
        config.wsPath = u.getQueryParameter("path") ?: "/"
        config.wsHost = u.getQueryParameter("host") ?: config.sni
        config.grpcServiceName = u.getQueryParameter("serviceName")
            ?: u.getQueryParameter("service_name")
            ?: ""
        return config
    }

    private fun parseVmess(uri: String): ProxyConfig {
        // vmess://base64(json)
        val b64 = uri.removePrefix("vmess://")
        val json = decodeBase64(b64)
        val obj = JSONObject(json)
        val config = ProxyConfig(
            protocol = ProtocolType.VMESS,
            uuid = obj.optString("id", ""),
            server = obj.optString("add", ""),
            port = obj.optInt("port", 443),
            alterId = obj.optInt("aid", 0),
            vmessSecurity = obj.optString("scy", obj.optString("security", "auto")),
            name = obj.optString("ps", "VMess"),
            sni = obj.optString("sni", obj.optString("host", "")),
            wsPath = obj.optString("path", "/"),
            wsHost = obj.optString("host", ""),
            rawUri = uri
        )
        config.tlsEnabled = obj.optString("tls", "") == "tls"
        config.alpn = obj.optString("alpn", "").replace(",", ", ")
        config.fingerprint = obj.optString("fp", "chrome")
        config.realityPublicKey = obj.optString("pbk", obj.optString("publicKey", ""))
        config.realityShortId = obj.optString("sid", obj.optString("shortId", ""))
        config.insecure = obj.optBoolean("allowInsecure", false) ||
            (obj.has("verifyCert") && !obj.optBoolean("verifyCert", true))

        val net = obj.optString("net", "tcp")
        config.transportType = when (net) {
            "ws" -> TransportType.WS
            "grpc" -> TransportType.GRPC
            "h2", "http" -> TransportType.HTTP
            else -> TransportType.NONE
        }
        if (config.transportType == TransportType.GRPC) {
            config.grpcServiceName = obj.optString("path", "")
        }
        return config
    }

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace("\n", "").replace("\r", "")
        val androidDecoded = runCatching { Base64.decode(normalized, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        if (androidDecoded != null) return String(androidDecoded, Charsets.UTF_8)
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return runCatching { String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8) }
            .recoverCatching { String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8) }
            .getOrThrow()
    }

    private fun parseHysteria2(uri: String): ProxyConfig {
        val normalized = if (uri.startsWith("hy2://")) uri.replaceFirst("hy2://", "hysteria2://") else uri
        val u = normalized.toUri()
        val config = ProxyConfig(
            protocol = ProtocolType.HYSTERIA2,
            password = u.userInfo ?: "",
            server = u.host ?: "",
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "Hysteria2",
            rawUri = normalized
        )
        config.sni = u.getQueryParameter("sni") ?: ""
        config.alpn = u.getQueryParameter("alpn")?.replace(",", ", ") ?: "h3"
        config.insecure = u.getQueryParameter("insecure") == "1" || u.getQueryParameter("allowInsecure") == "1"
        config.tlsEnabled = true
        config.upMbps = u.getQueryParameter("up")?.toIntOrNull() ?: 0
        config.downMbps = u.getQueryParameter("down")?.toIntOrNull() ?: 0
        return config
    }

    private fun parseTuic(uri: String): ProxyConfig {
        val u = uri.toUri()
        val userInfo = u.userInfo ?: ""
        // tuic://uuid:password@host:port
        val parts = userInfo.split(":")
        val uuid = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""

        val config = ProxyConfig(
            protocol = ProtocolType.TUIC,
            uuid = uuid,
            password = password,
            server = u.host ?: "",
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "TUIC",
            rawUri = uri
        )
        config.sni = u.getQueryParameter("sni") ?: ""
        config.alpn = u.getQueryParameter("alpn")?.replace(",", ", ") ?: "h3"
        config.insecure = u.getQueryParameter("insecure") == "1" || u.getQueryParameter("allow_insecure") == "1"
        config.tlsEnabled = true
        config.congestionControl = u.getQueryParameter("congestion_control") ?: "bbr"
        return config
    }

    private fun parseAnytls(uri: String): ProxyConfig {
        val u = uri.toUri()
        val config = ProxyConfig(
            protocol = ProtocolType.ANYTLS,
            password = u.userInfo ?: "",
            server = u.host ?: "",
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "AnyTLS",
            rawUri = uri
        )
        config.sni = u.getQueryParameter("sni") ?: ""
        config.insecure = u.getQueryParameter("insecure") == "1"
        config.tlsEnabled = true
        config.alpn = u.getQueryParameter("alpn")?.replace(",", ", ") ?: ""
        return config
    }

    private fun parseShadowsocks(uri: String): ProxyConfig {
        // ss://base64(method:password)@host:port#name
        // 或 ss://base64(method:password@host:port)#name （旧格式）
        val u = uri.toUri()
        val config = ProxyConfig(
            protocol = ProtocolType.SHADOWSOCKS,
            server = u.host ?: "",
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "Shadowsocks",
            rawUri = uri
        )
        config.tlsEnabled = false

        // 解析 method:password
        val userInfo = u.userInfo
        if (userInfo != null && userInfo.contains(":")) {
            // base64(method:password) 已在 userInfo 中（部分 URI 不做 base64）
            val parts = userInfo.split(":")
            config.ssMethod = parts[0]
            config.password = parts.drop(1).joinToString(":")
        } else if (userInfo != null) {
            // base64 编码的 method:password
            try {
                val decoded = String(Base64.decode(userInfo, Base64.DEFAULT), Charsets.UTF_8)
                val parts = decoded.split(":")
                config.ssMethod = parts[0]
                config.password = parts.drop(1).joinToString(":")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode ss userInfo: ${e.message}")
            }
        }
        return config
    }

    private fun parseTrojan(uri: String): ProxyConfig {
        val u = uri.toUri()
        return ProxyConfig(
            protocol = ProtocolType.TROJAN,
            password = u.userInfo.orEmpty(),
            server = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 443,
            name = u.fragment ?: "Trojan",
            sni = u.getQueryParameter("sni") ?: u.getQueryParameter("peer") ?: "",
            insecure = u.getQueryParameter("allowInsecure") == "1" || u.getQueryParameter("insecure") == "1",
            tlsEnabled = true,
            alpn = u.getQueryParameter("alpn")?.replace(",", ", ").orEmpty(),
            rawUri = uri
        ).also { config ->
            config.fingerprint = u.getQueryParameter("fp") ?: "chrome"
            config.transportType = when (u.getQueryParameter("type")?.lowercase()) {
                "ws" -> TransportType.WS
                "grpc" -> TransportType.GRPC
                "http", "h2" -> TransportType.HTTP
                else -> TransportType.NONE
            }
            config.wsPath = u.getQueryParameter("path") ?: "/"
            config.wsHost = u.getQueryParameter("host") ?: config.sni
            config.grpcServiceName = u.getQueryParameter("serviceName") ?: u.getQueryParameter("service_name").orEmpty()
        }
    }

    private fun parseSocks(uri: String): ProxyConfig {
        val u = uri.toUri()
        val userInfo = u.userInfo.orEmpty()
        val separator = userInfo.indexOf(':')
        return ProxyConfig(
            protocol = ProtocolType.SOCKS5,
            username = if (separator >= 0) userInfo.substring(0, separator) else userInfo,
            password = if (separator >= 0) userInfo.substring(separator + 1) else "",
            server = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 1080,
            name = u.fragment ?: "SOCKS5",
            tlsEnabled = false,
            rawUri = uri
        )
    }

    private fun parseHttp(uri: String): ProxyConfig? {
        val u = uri.toUri()
        // A subscription URL is also an HTTP URL. Only accept an explicit
        // proxy port or an URI without a path to avoid importing download URLs.
        if (u.port <= 0 && !u.path.isNullOrBlank() && u.path != "/") return null
        val userInfo = u.userInfo.orEmpty()
        val separator = userInfo.indexOf(':')
        return ProxyConfig(
            protocol = ProtocolType.HTTP_PROXY,
            username = if (separator >= 0) userInfo.substring(0, separator) else userInfo,
            password = if (separator >= 0) userInfo.substring(separator + 1) else "",
            server = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 8080,
            name = u.fragment ?: "HTTP",
            tlsEnabled = false,
            rawUri = uri
        )
    }

    private fun parseShadowsocksR(uri: String): ProxyConfig {
        val encoded = uri.removePrefix("ssr://").trimEnd('=')
        val decoded = decodeBase64(encoded)
        val split = decoded.substringBefore("/?").split(":")
        val query = decoded.substringAfter("/?", "")
        val config = ProxyConfig(
            protocol = ProtocolType.SHADOWSOCKSR,
            server = split.getOrNull(0).orEmpty(),
            port = split.getOrNull(1)?.toIntOrNull() ?: 0,
            ssrProtocol = split.getOrNull(2).orEmpty(),
            ssMethod = split.getOrNull(3).orEmpty(),
            ssrObfs = split.getOrNull(4).orEmpty(),
            password = decodeBase64Value(split.getOrNull(5).orEmpty()),
            name = "ShadowsocksR",
            tlsEnabled = false,
            rawUri = uri
        )
        query.split('&').forEach { item ->
            val key = item.substringBefore('=')
            val value = decodeBase64Value(item.substringAfter('=', ""))
            when (key) {
                "obfsparam" -> config.ssrObfsParam = value
                "protoparam" -> config.ssrProtocolParam = value
                "remarks" -> config.name = value.ifBlank { config.name }
                "group" -> Unit
            }
        }
        return config
    }

    private fun decodeBase64Value(value: String): String = if (value.isBlank()) "" else runCatching {
        decodeBase64(URLDecoder.decode(value, StandardCharsets.UTF_8.name()))
    }.getOrDefault(value)
}
