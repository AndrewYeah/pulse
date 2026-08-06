package com.andrew.proxyapp.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class SubscriptionFormat {
    CLASH_YAML,
    V2RAY_JSON,
    SING_BOX_JSON,
    URI,
    BASE64_URI,
    UNKNOWN
}

interface SubscriptionStore {
    fun replaceSubscriptionConfigs(subscription: Subscription, configs: List<ProxyConfig>)
    fun saveSubscription(subscription: Subscription)
}

object SubscriptionFormatDetector {
    fun detect(content: String): SubscriptionFormat {
        val trimmed = content.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) return SubscriptionFormat.UNKNOWN
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return try {
                val root = if (trimmed.startsWith("{")) org.json.JSONObject(trimmed) else null
                val outbounds = root?.optJSONArray("outbounds")
                val first = outbounds?.optJSONObject(0)
                if (root?.has("routing") == true || root?.has("inbounds") == true ||
                    first?.has("protocol") == true || first?.has("settings") == true
                ) {
                    SubscriptionFormat.V2RAY_JSON
                } else SubscriptionFormat.SING_BOX_JSON
            } catch (_: Exception) {
                SubscriptionFormat.UNKNOWN
            }
        }
        if (trimmed.contains("proxies:") || trimmed.startsWith("---")) return SubscriptionFormat.CLASH_YAML
        if (trimmed.contains("://")) return SubscriptionFormat.URI
        return SubscriptionFormat.BASE64_URI
    }
}

data class StandardTls(
    val enabled: Boolean,
    val serverName: String,
    val insecure: Boolean,
    val alpn: List<String>,
    val fingerprint: String,
    val realityPublicKey: String,
    val realityShortId: String
)

data class StandardTransport(
    val type: TransportType = TransportType.NONE,
    val path: String = "",
    val host: String = "",
    val grpcServiceName: String = ""
)

data class StandardCredentials(
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    val alterId: Int = 0,
    val security: String = "auto",
    val ssMethod: String = "",
    val flow: String = ""
)

data class StandardNode(
    val id: String,
    val name: String,
    val protocol: ProtocolType,
    val server: String,
    val port: Int,
    val credentials: StandardCredentials = StandardCredentials(),
    val tls: StandardTls = StandardTls(false, "", false, emptyList(), "chrome", "", ""),
    val transport: StandardTransport = StandardTransport(),
    val upMbps: Int = 0,
    val downMbps: Int = 0,
    val congestionControl: String = "bbr",
    val ssrProtocol: String = "",
    val ssrProtocolParam: String = "",
    val ssrObfs: String = "",
    val ssrObfsParam: String = "",
    val sourceFormat: SubscriptionFormat = SubscriptionFormat.UNKNOWN,
    val rawUri: String = "",
    val unsupportedReason: String = ""
) {
    fun toProxyConfig(subscriptionId: String): ProxyConfig = ProxyConfig(
        id = id,
        name = name,
        protocol = protocol,
        server = server,
        port = port,
        uuid = credentials.uuid,
        password = credentials.password,
        username = credentials.username,
        alterId = credentials.alterId,
        vmessSecurity = credentials.security,
        tlsEnabled = tls.enabled,
        sni = tls.serverName,
        alpn = tls.alpn.joinToString(", "),
        insecure = tls.insecure,
        fingerprint = tls.fingerprint,
        realityPublicKey = tls.realityPublicKey,
        realityShortId = tls.realityShortId,
        flow = credentials.flow,
        transportType = transport.type,
        wsPath = transport.path,
        wsHost = transport.host,
        grpcServiceName = transport.grpcServiceName,
        upMbps = upMbps,
        downMbps = downMbps,
        congestionControl = congestionControl,
        ssMethod = credentials.ssMethod,
        ssrProtocol = ssrProtocol,
        ssrProtocolParam = ssrProtocolParam,
        ssrObfs = ssrObfs,
        ssrObfsParam = ssrObfsParam,
        sourceFormat = sourceFormat.name.lowercase(),
        rawUri = rawUri,
        unsupportedReason = unsupportedReason,
        subscriptionId = subscriptionId
    )
}

data class ParseIssue(
    val index: Int,
    val name: String,
    val code: String,
    val message: String,
    val fatal: Boolean = false
)

data class SubscriptionParseResult(
    val format: SubscriptionFormat,
    val nodes: List<ProxyConfig>,
    val issues: List<ParseIssue> = emptyList(),
    val decodedBase64: Boolean = false
) {
    val hasUsableNodes: Boolean get() = nodes.any { it.validationError.isBlank() && it.unsupportedReason.isBlank() }
}

object StableNodeId {
    fun forConfig(config: ProxyConfig, subscriptionId: String): String {
        val identity = listOf(
            subscriptionId,
            config.protocol.value,
            config.server.trim().lowercase(),
            config.port,
            config.uuid,
            config.password,
            config.username,
            config.alterId,
            config.vmessSecurity,
            config.ssMethod,
            config.tlsEnabled,
            config.sni,
            config.alpn,
            config.insecure,
            config.fingerprint,
            config.transportType.value,
            config.wsPath,
            config.wsHost,
            config.grpcServiceName,
            config.realityPublicKey,
            config.realityShortId,
            config.packetEncoding,
            config.flow,
            config.upMbps,
            config.downMbps,
            config.congestionControl,
            config.ssrProtocol,
            config.ssrProtocolParam,
            config.ssrObfs,
            config.ssrObfsParam
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

object NodeValidator {
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val supportedSsMethods = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"
    )

    fun validate(config: ProxyConfig): List<ParseIssue> {
        val issues = mutableListOf<ParseIssue>()
        fun issue(code: String, message: String, fatal: Boolean = false) =
            issues.add(ParseIssue(-1, config.name, code, message, fatal))

        if (config.server.isBlank()) issue("server_missing", "server is required", true)
        if (config.port !in 1..65535) issue("port_invalid", "port must be between 1 and 65535", true)
        when (config.protocol) {
            ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TUIC -> {
                if (config.uuid.isBlank()) issue("uuid_missing", "UUID is required", true)
                else if (config.protocol != ProtocolType.TUIC && !uuidPattern.matches(config.uuid)) {
                    issue("uuid_invalid", "UUID format is invalid", true)
                }
            }
            ProtocolType.HYSTERIA2, ProtocolType.ANYTLS, ProtocolType.TROJAN ->
                if (config.password.isBlank()) issue("password_missing", "password is required", true)
            ProtocolType.SHADOWSOCKS -> {
                if (config.ssMethod.isBlank()) issue("cipher_missing", "cipher is required", true)
                else if (config.ssMethod.lowercase() !in supportedSsMethods) issue("cipher_unsupported", "cipher is not supported", true)
                if (config.password.isBlank()) issue("password_missing", "password is required", true)
            }
            ProtocolType.SOCKS5, ProtocolType.HTTP_PROXY -> Unit
            ProtocolType.SHADOWSOCKSR -> {
                if (config.password.isBlank()) issue("password_missing", "password is required", true)
                if (config.ssrProtocol.isBlank() || config.ssrObfs.isBlank()) issue("ssr_fields_missing", "SSR protocol and obfs are required", true)
                issue("core_unsupported", "SSR is not supported by sing-box 1.13.14")
            }
        }
        if (config.tlsEnabled && config.realityPublicKey.isNotBlank() && config.protocol !in setOf(ProtocolType.VLESS, ProtocolType.VMESS)) {
            issue("reality_unsupported", "Reality is only supported for VLESS and VMess", true)
        }
        if (config.transportType == TransportType.GRPC && config.grpcServiceName.isBlank()) {
            issue("grpc_service_missing", "gRPC service name is required", true)
        }
        if (config.protocol == ProtocolType.SHADOWSOCKSR) {
            issue("runtime_unavailable", "Import is retained for diagnostics but cannot be started")
        }
        return issues
    }
}

object StandardNodeMapper {
    fun normalize(config: ProxyConfig, subscriptionId: String, format: SubscriptionFormat): ProxyConfig {
        val normalized = fromProxy(config, format)
        val mapped = normalized.toProxyConfig(subscriptionId)
        return mapped.copy(id = StableNodeId.forConfig(mapped, subscriptionId))
    }

    fun fromProxy(config: ProxyConfig, format: SubscriptionFormat): StandardNode {
        val normalized = config.copy(
            server = config.server.trim(),
            name = config.name.trim().ifBlank { config.protocol.displayName },
            sourceFormat = format.name.lowercase()
        )
        return StandardNode(
            id = normalized.id,
            name = normalized.name,
            protocol = normalized.protocol,
            server = normalized.server,
            port = normalized.port,
            credentials = StandardCredentials(
                uuid = normalized.uuid,
                password = normalized.password,
                username = normalized.username,
                alterId = normalized.alterId,
                security = normalized.vmessSecurity,
                ssMethod = normalized.ssMethod,
                flow = normalized.flow
            ),
            tls = StandardTls(
                enabled = normalized.tlsEnabled,
                serverName = normalized.sni,
                insecure = normalized.insecure,
                alpn = normalized.alpn.split(',').map(String::trim).filter(String::isNotBlank),
                fingerprint = normalized.fingerprint,
                realityPublicKey = normalized.realityPublicKey,
                realityShortId = normalized.realityShortId
            ),
            transport = StandardTransport(
                type = normalized.transportType,
                path = normalized.wsPath,
                host = normalized.wsHost,
                grpcServiceName = normalized.grpcServiceName
            ),
            upMbps = normalized.upMbps,
            downMbps = normalized.downMbps,
            congestionControl = normalized.congestionControl,
            ssrProtocol = normalized.ssrProtocol,
            ssrProtocolParam = normalized.ssrProtocolParam,
            ssrObfs = normalized.ssrObfs,
            ssrObfsParam = normalized.ssrObfsParam,
            sourceFormat = format,
            rawUri = normalized.rawUri,
            unsupportedReason = normalized.unsupportedReason
        )
    }

    fun result(configs: List<ProxyConfig>, format: SubscriptionFormat, decodedBase64: Boolean = false): SubscriptionParseResult {
        val accepted = mutableListOf<ProxyConfig>()
        val acceptedIds = mutableSetOf<String>()
        val issues = mutableListOf<ParseIssue>()
        configs.forEachIndexed { index, config ->
            val nodeIssues = NodeValidator.validate(config)
            if (nodeIssues.isEmpty() || nodeIssues.none { it.fatal }) {
                val withWarnings = config.copy(
                    validationError = config.validationError.ifBlank {
                        nodeIssues.firstOrNull { it.code != "core_unsupported" && it.code != "runtime_unavailable" }?.message.orEmpty()
                    },
                    unsupportedReason = config.unsupportedReason.ifBlank {
                        nodeIssues.firstOrNull { it.code == "core_unsupported" || it.code == "runtime_unavailable" }?.message.orEmpty()
                    }
                )
                if (acceptedIds.add(withWarnings.id)) {
                    accepted.add(withWarnings)
                } else {
                    issues.add(ParseIssue(index, withWarnings.name, "node_duplicate", "duplicate proxy node was merged"))
                }
            }
            issues.addAll(nodeIssues.map { it.copy(index = index) })
        }
        return SubscriptionParseResult(format, accepted, issues, decodedBase64)
    }
}
