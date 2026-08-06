package com.andrew.proxyapp.data

import com.google.gson.annotations.SerializedName

/**
 * 代理配置数据模型
 * 支持 vless, vmess, hysteria2, tuic, anytls 协议
 */
data class ProxyConfig(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var protocol: ProtocolType = ProtocolType.HYSTERIA2,
    var server: String = "",
    var port: Int = 443,

    // 通用认证
    var uuid: String = "",        // vless, vmess, tuic
    var password: String = "",    // hysteria2, anytls, tuic
    var username: String = "",    // socks/http
    var alterId: Int = 0,         // vmess
    var vmessSecurity: String = "auto",

    // TLS
    var tlsEnabled: Boolean = true,
    var sni: String = "",
    var alpn: String = "",        // 逗号分隔，如 "h3,h2"
    var insecure: Boolean = false,
    var fingerprint: String = "chrome",  // utls 指纹
    var realityPublicKey: String = "",
    var realityShortId: String = "",
    var packetEncoding: String = "",

    // 传输
    var transportType: TransportType = TransportType.NONE,
    var wsPath: String = "",
    var wsHost: String = "",
    var grpcServiceName: String = "",

    // Hysteria2 特有
    var upMbps: Int = 0,
    var downMbps: Int = 0,

    // TUIC 特有
    var congestionControl: String = "bbr",

    // VLESS flow
    var flow: String = "",

    // Shadowsocks 特有
    var ssMethod: String = "",  // 加密方法，如 aes-256-gcm、chacha20-ietf-poly1305

    // 原始 URI（用于显示）
    var ssrProtocol: String = "",
    var ssrProtocolParam: String = "",
    var ssrObfs: String = "",
    var ssrObfsParam: String = "",

    var sourceFormat: String = "",
    var validationError: String = "",
    var unsupportedReason: String = "",
    var lastLatencyMs: Int = 0,
    var lastTestedAt: Long = 0,

    // Raw URI retained for diagnostics and display.
    var rawUri: String = "",

    // 来源订阅 ID（空表示手动添加的节点）
    var subscriptionId: String = ""
) {
    val displayInfo: String
        get() = "$server:$port (${protocol.value})"

    val summary: String
        get() = if (name.isNotBlank()) "$name - $displayInfo" else displayInfo
}

enum class ProtocolType(val value: String, val displayName: String) {
    @SerializedName("vless") VLESS("vless", "VLESS"),
    @SerializedName("vmess") VMESS("vmess", "VMess"),
    @SerializedName("hysteria2") HYSTERIA2("hysteria2", "Hysteria2"),
    @SerializedName("tuic") TUIC("tuic", "TUIC"),
    @SerializedName("anytls") ANYTLS("anytls", "AnyTLS"),
    @SerializedName("shadowsocks") SHADOWSOCKS("shadowsocks", "Shadowsocks"),
    @SerializedName("trojan") TROJAN("trojan", "Trojan"),
    @SerializedName("socks") SOCKS5("socks", "SOCKS5"),
    @SerializedName("http") HTTP_PROXY("http", "HTTP"),
    @SerializedName("shadowsocksr") SHADOWSOCKSR("shadowsocksr", "ShadowsocksR");

    companion object {
        fun fromValue(v: String?): ProtocolType =
            entries.find { it.value == v } ?: HYSTERIA2
    }
}

enum class TransportType(val value: String, val displayName: String) {
    @SerializedName("none") NONE("none", "无"),
    @SerializedName("ws") WS("ws", "WebSocket"),
    @SerializedName("grpc") GRPC("grpc", "gRPC"),
    @SerializedName("http") HTTP("http", "HTTP"),
    @SerializedName("httpupgrade") HTTPUPGRADE("httpupgrade", "HTTPUpgrade");

    companion object {
        fun fromValue(v: String?): TransportType =
            entries.find { it.value == v } ?: NONE
    }
}
