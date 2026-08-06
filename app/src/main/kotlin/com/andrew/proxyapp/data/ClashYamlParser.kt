package com.andrew.proxyapp.data

import android.util.Log
import net.mamoe.yamlkt.Yaml

/**
 * Clash YAML 订阅解析器。
 * 从 Clash 配置的 proxies 数组提取节点，转换为 ProxyConfig。
 *
 * 支持的 Clash proxy type: ss, vmess, vless, hysteria2, tuic
 */
object ClashYamlParser {

    private const val TAG = "ClashYamlParser"

    fun parse(yamlText: String, subscriptionId: String): List<ProxyConfig> {
        val nodes = mutableListOf<ProxyConfig>()
        try {
            val root = Yaml.decodeMapFromString(yamlText)
            val proxies = root["proxies"] as? List<*> ?: return nodes
            for (proxy in proxies) {
                val map = proxy as? Map<*, *> ?: continue
                val config = parseProxy(map, subscriptionId) ?: continue
                nodes.add(config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Clash YAML: ${e.message}", e)
        }
        Log.i(TAG, "Parsed ${nodes.size} nodes from Clash YAML")
        return nodes
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProxy(map: Map<*, *>, subscriptionId: String): ProxyConfig? {
        val type = map["type"]?.toString()?.lowercase() ?: return null
        val name = map["name"]?.toString() ?: "Node"
        val server = map["server"]?.toString() ?: return null
        val port = (map["port"]?.toString()?.toIntOrNull()) ?: return null

        val config = ProxyConfig(
            name = name,
            server = server,
            port = port,
            subscriptionId = subscriptionId
        )

        when (type) {
            "ss", "shadowsocks" -> {
                config.protocol = ProtocolType.SHADOWSOCKS
                config.ssMethod = map["cipher"]?.toString() ?: "aes-256-gcm"
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = false
            }
            "vmess" -> {
                config.protocol = ProtocolType.VMESS
                config.uuid = map["uuid"]?.toString() ?: ""
                config.alterId = map["alterId"]?.toString()?.toIntOrNull() ?: 0
                config.vmessSecurity = map["cipher"]?.toString() ?: "auto"
                config.tlsEnabled = map["tls"]?.toString() == "true"
                config.sni = map["servername"]?.toString() ?: map["sni"]?.toString() ?: ""
                config.insecure = map["skip-cert-verify"]?.toString() == "true"
                config.fingerprint = map["client-fingerprint"]?.toString() ?: "chrome"
                applyReality(config, map)
                val network = map["network"]?.toString() ?: "tcp"
                applyTransport(config, network, map)
            }
            "vless" -> {
                config.protocol = ProtocolType.VLESS
                config.uuid = map["uuid"]?.toString() ?: ""
                config.flow = map["flow"]?.toString() ?: ""
                config.tlsEnabled = map["tls"]?.toString() == "true"
                config.sni = map["servername"]?.toString() ?: map["sni"]?.toString() ?: ""
                config.insecure = map["skip-cert-verify"]?.toString() == "true"
                config.fingerprint = map["client-fingerprint"]?.toString() ?: "chrome"
                applyReality(config, map)
                config.packetEncoding = map["packet-encoding"]?.toString()
                    ?: map["packet_encoding"]?.toString()
                    ?: ""
                val network = map["network"]?.toString() ?: "tcp"
                applyTransport(config, network, map)
            }
            "hysteria2", "hy2" -> {
                config.protocol = ProtocolType.HYSTERIA2
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = true
                config.sni = map["sni"]?.toString() ?: ""
                config.insecure = map["skip-cert-verify"]?.toString() == "true"
                config.upMbps = map["up"]?.toString()?.toIntOrNull() ?: 0
                config.downMbps = map["down"]?.toString()?.toIntOrNull() ?: 0
            }
            "tuic" -> {
                config.protocol = ProtocolType.TUIC
                config.uuid = map["uuid"]?.toString() ?: ""
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = true
                config.sni = map["sni"]?.toString() ?: ""
                config.congestionControl = map["congestion-controller"]?.toString() ?: "bbr"
                config.alpn = (map["alpn"] as? List<*>)?.joinToString(", ") ?: "h3"
            }
            "trojan" -> {
                config.protocol = ProtocolType.TROJAN
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = true
                config.sni = map["sni"]?.toString() ?: map["servername"]?.toString() ?: ""
                config.insecure = map["skip-cert-verify"]?.toString()?.toBoolean() == true
                config.alpn = (map["alpn"] as? List<*>)?.joinToString(", ") ?: ""
                config.fingerprint = map["client-fingerprint"]?.toString() ?: "chrome"
                applyTransport(config, map["network"]?.toString() ?: "tcp", map)
            }
            "socks5", "socks" -> {
                config.protocol = ProtocolType.SOCKS5
                config.username = map["username"]?.toString() ?: ""
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = false
            }
            "http" -> {
                config.protocol = ProtocolType.HTTP_PROXY
                config.username = map["username"]?.toString() ?: ""
                config.password = map["password"]?.toString() ?: ""
                config.tlsEnabled = false
            }
            "ssr" -> {
                config.protocol = ProtocolType.SHADOWSOCKSR
                config.ssMethod = map["cipher"]?.toString() ?: ""
                config.password = map["password"]?.toString() ?: ""
                config.ssrProtocol = map["protocol"]?.toString() ?: ""
                config.ssrProtocolParam = map["protocol-param"]?.toString() ?: map["protocol_param"]?.toString() ?: ""
                config.ssrObfs = map["obfs"]?.toString() ?: ""
                config.ssrObfsParam = map["obfs-param"]?.toString() ?: map["obfs_param"]?.toString() ?: ""
                config.tlsEnabled = false
            }
            else -> {
                Log.w(TAG, "Unsupported Clash proxy type: $type")
                return null
            }
        }
        return config
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyTransport(config: ProxyConfig, network: String, map: Map<*, *>) {
        when (network) {
            "ws" -> {
                config.transportType = TransportType.WS
                val opts = map["ws-opts"] as? Map<*, *>
                config.wsPath = opts?.get("path")?.toString() ?: "/"
                config.wsHost = opts?.get("headers")?.let { (it as? Map<*, *>)?.get("Host")?.toString() } ?: config.sni
            }
            "grpc" -> {
                config.transportType = TransportType.GRPC
                val opts = map["grpc-opts"] as? Map<*, *>
                config.grpcServiceName = opts?.get("grpc-service-name")?.toString() ?: ""
            }
            "h2", "http" -> {
                config.transportType = TransportType.HTTP
                val opts = map["h2-opts"] as? Map<*, *>
                config.wsPath = opts?.get("path")?.toString() ?: "/"
                config.wsHost = (opts?.get("host") as? List<*>)?.firstOrNull()?.toString() ?: config.sni
            }
            else -> config.transportType = TransportType.NONE
        }
    }

    private fun applyReality(config: ProxyConfig, map: Map<*, *>) {
        val options = map["reality-opts"] as? Map<*, *> ?: return
        config.realityPublicKey = options["public-key"]?.toString()
            ?: options["public_key"]?.toString()
            ?: ""
        config.realityShortId = options["short-id"]?.toString()
            ?: options["short_id"]?.toString()
            ?: ""
    }
}
