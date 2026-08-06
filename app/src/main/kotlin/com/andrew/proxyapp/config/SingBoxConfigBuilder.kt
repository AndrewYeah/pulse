package com.andrew.proxyapp.config

import com.andrew.proxyapp.data.AppSettings
import com.andrew.proxyapp.data.MatchType
import com.andrew.proxyapp.data.NodeValidator
import com.andrew.proxyapp.data.PerAppMode
import com.andrew.proxyapp.data.ProtocolType
import com.andrew.proxyapp.data.ProxyConfig
import com.andrew.proxyapp.data.RouteMode
import com.andrew.proxyapp.data.RuleAction
import com.andrew.proxyapp.data.RoutingRule
import com.andrew.proxyapp.data.TransportType
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigBuilder {
    private const val TUN_INET4_ADDR = "172.19.0.1/30"
    private const val TUN_INET6_ADDR = "fdfe:dcba:9876::1/126"
    private const val TUN_MTU = 1500
    private const val SELECTOR_TAG = "proxy-out"

    fun nodeTag(configId: String): String = "node-$configId"

    fun build(config: ProxyConfig, settings: AppSettings, logPath: String? = null): String =
        build(listOf(config), config.id, settings, emptyMap(), "com.andrew.proxyapp", logPath)

    fun build(
        configs: List<ProxyConfig>,
        activeConfigId: String,
        settings: AppSettings,
        ruleSetPaths: Map<String, String>,
        appPackageName: String,
        logPath: String? = null
    ): String {
        require(configs.isNotEmpty()) { "No proxy configs available" }
        val usableConfigs = configs.filter { config ->
            config.validationError.isBlank() && NodeValidator.validate(config).none {
                it.fatal || it.code == "core_unsupported" || it.code == "runtime_unavailable"
            }
        }.distinctBy { it.id }
        require(usableConfigs.isNotEmpty()) {
            configs.flatMap { NodeValidator.validate(it) }.joinToString("; ") { it.message }
                .ifBlank { "No usable proxy configs available" }
        }
        val active = usableConfigs.firstOrNull { it.id == activeConfigId } ?: usableConfigs.first()
        return JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "info")
                put("timestamp", true)
                logPath?.let { put("output", it) }
            })
            put("dns", buildDns(settings, ruleSetPaths))
            put("inbounds", JSONArray().put(buildTunInbound(settings, appPackageName)))
            put("outbounds", buildOutbounds(usableConfigs, active, settings))
            put("route", buildRoute(settings, ruleSetPaths, usableConfigs.mapTo(mutableSetOf()) { it.id }, appPackageName))
            put("experimental", JSONObject().put("clash_api", JSONObject().apply {
                put("default_mode", if (settings.routeMode == RouteMode.GLOBAL) "Global" else "Rule")
            }))
        }.toString(2)
    }

    private fun buildDns(settings: AppSettings, ruleSetPaths: Map<String, String>): JSONObject {
        val servers = JSONArray()
            .put(JSONObject().apply {
                put("tag", "dns_direct")
                put("address", settings.localDns)
                put("detour", "direct-out")
            })
            .put(JSONObject().apply {
                put("tag", "dns_proxy")
                put("address", settings.remoteDns)
                put("address_resolver", "dns_direct")
                put("detour", SELECTOR_TAG)
            })
        val rules = JSONArray()
        // 关键：代理出站的服务器域名必须通过直连 DNS 解析，否则形成 DNS 循环依赖
        // （dns_proxy detour=proxy-out → proxy-out 需解析服务器域名 → 又走 dns_proxy → 死循环）
        // 参考 mihomo/clash-verge 的行为：代理节点域名始终用直连 nameserver 解析
        rules.put(JSONObject().apply {
            put("outbound", JSONArray().put("any"))
            put("action", "route")
            put("server", "dns_direct")
        })
        // 中国域名走直连 DNS（仅当 geosite-cn 规则集实际可用时才添加）
        if (ruleSetPaths.containsKey("geosite-cn")) {
            rules.put(JSONObject().apply {
                put("rule_set", JSONArray().put("geosite-cn"))
                put("action", "route")
                put("server", "dns_direct")
            })
        }
        return JSONObject().apply {
            put("servers", servers)
            put("rules", rules)
            put("final", "dns_proxy")
            put("independent_cache", true)
            put("strategy", settings.dnsStrategy)
        }
    }

    private fun buildTunInbound(settings: AppSettings, appPackageName: String) = JSONObject().apply {
        put("type", "tun")
        put("tag", "tun-in")
        put("address", JSONArray().put(TUN_INET4_ADDR).put(TUN_INET6_ADDR))
        put("mtu", TUN_MTU)
        put("auto_route", true)
        put("strict_route", true)
        put("stack", "gvisor")
        val assignedPackages = settings.appNodeAssignments.keys.filter { it.isNotBlank() && it != appPackageName }.toSet()
        val selectedPackages = settings.selectedPackages.filter { it.isNotBlank() && it != appPackageName }.toSet()
        val packages = when (settings.perAppMode) {
            PerAppMode.INCLUDE_SELECTED -> selectedPackages + assignedPackages
            PerAppMode.EXCLUDE_SELECTED -> selectedPackages - assignedPackages
        }
        if (packages.isNotEmpty()) {
            val values = JSONArray().apply { packages.forEach(::put) }
            when (settings.perAppMode) {
                PerAppMode.INCLUDE_SELECTED -> put("include_package", values)
                PerAppMode.EXCLUDE_SELECTED -> put("exclude_package", values)
            }
        }
    }

    private fun buildOutbounds(
        configs: List<ProxyConfig>,
        active: ProxyConfig,
        settings: AppSettings
    ) = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "selector")
            put("tag", SELECTOR_TAG)
            put("outbounds", JSONArray().apply { configs.forEach { put(nodeTag(it.id)) } })
            put("default", nodeTag(active.id))
        })
        configs.forEach { put(buildProxyOutbound(it, settings)) }
        put(JSONObject().put("type", "direct").put("tag", "direct-out"))
        put(JSONObject().put("type", "block").put("tag", "block-out"))
    }

    private fun buildProxyOutbound(config: ProxyConfig, settings: AppSettings) = JSONObject().apply {
        put("tag", nodeTag(config.id))
        put("server", config.server)
        put("server_port", config.port)
        when (config.protocol) {
            ProtocolType.VLESS -> {
                put("type", "vless"); put("uuid", config.uuid)
                if (config.flow.isNotBlank()) put("flow", config.flow)
                if (config.packetEncoding.isNotBlank()) put("packet_encoding", config.packetEncoding)
            }
            ProtocolType.VMESS -> {
                put("type", "vmess"); put("uuid", config.uuid)
                put("security", config.vmessSecurity.ifBlank { "auto" }); put("alter_id", config.alterId)
            }
            ProtocolType.HYSTERIA2 -> {
                put("type", "hysteria2"); put("password", config.password)
                if (config.upMbps > 0) put("up_mbps", config.upMbps)
                if (config.downMbps > 0) put("down_mbps", config.downMbps)
            }
            ProtocolType.TUIC -> {
                put("type", "tuic"); put("uuid", config.uuid); put("password", config.password)
                put("congestion_control", config.congestionControl)
            }
            ProtocolType.ANYTLS -> { put("type", "anytls"); put("password", config.password) }
            ProtocolType.SHADOWSOCKS -> {
                put("type", "shadowsocks"); put("method", config.ssMethod); put("password", config.password)
            }
            ProtocolType.TROJAN -> {
                put("type", "trojan"); put("password", config.password)
            }
            ProtocolType.SOCKS5 -> {
                put("type", "socks")
                if (config.username.isNotBlank()) put("username", config.username)
                if (config.password.isNotBlank()) put("password", config.password)
            }
            ProtocolType.HTTP_PROXY -> {
                put("type", "http")
                if (config.username.isNotBlank()) put("username", config.username)
                if (config.password.isNotBlank()) put("password", config.password)
            }
            ProtocolType.SHADOWSOCKSR -> error("ShadowsocksR is not supported by sing-box 1.13.14")
        }
        if (config.tlsEnabled) put("tls", JSONObject().apply {
            put("enabled", true)
            if (config.sni.isNotBlank()) put("server_name", config.sni)
            put("insecure", config.insecure || settings.skipCertVerify)
            if (config.alpn.isNotBlank()) put("alpn", JSONArray().apply {
                config.alpn.split(',').map(String::trim).filter(String::isNotBlank).forEach(::put)
            })
            if (config.protocol == ProtocolType.VLESS || config.protocol == ProtocolType.VMESS) {
                put("utls", JSONObject().put("enabled", true).put("fingerprint", config.fingerprint))
            }
            if ((config.protocol == ProtocolType.VLESS || config.protocol == ProtocolType.VMESS) && config.realityPublicKey.isNotBlank()) {
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", config.realityPublicKey)
                    if (config.realityShortId.isNotBlank()) put("short_id", config.realityShortId)
                })
            }
        })
        buildTransport(config)?.let { put("transport", it) }
    }

    private fun buildTransport(config: ProxyConfig): JSONObject? = when (config.transportType) {
        TransportType.WS -> JSONObject().put("type", "ws").put("path", config.wsPath).apply {
            if (config.wsHost.isNotBlank()) put("headers", JSONObject().put("Host", config.wsHost))
        }
        TransportType.GRPC -> JSONObject().put("type", "grpc").put("service_name", config.grpcServiceName)
        TransportType.HTTP -> JSONObject().put("type", "http").put("path", config.wsPath).apply {
            if (config.wsHost.isNotBlank()) put("host", JSONArray().put(config.wsHost))
        }
        TransportType.HTTPUPGRADE -> JSONObject().put("type", "httpupgrade").put("path", config.wsPath).apply {
            if (config.wsHost.isNotBlank()) put("headers", JSONObject().put("Host", config.wsHost))
        }
        TransportType.NONE -> null
    }

    private fun buildRoute(
        settings: AppSettings,
        ruleSetPaths: Map<String, String>,
        configIds: Set<String>,
        appPackageName: String
    ) = JSONObject().apply {
        val rules = JSONArray()
        rules.put(JSONObject().put("inbound", JSONArray().put("tun-in")).put("action", "sniff"))
        rules.put(JSONObject().put("inbound", JSONArray().put("tun-in")).put("port", JSONArray().put(53)).put("action", "hijack-dns"))
        settings.appNodeAssignments
            .filter { (packageName, configId) -> packageName.isNotBlank() && packageName != appPackageName && configId in configIds }
            .forEach { (packageName, configId) ->
                rules.put(routeRule(nodeTag(configId)).put("package_name", JSONArray().put(packageName)))
            }
        rules.put(routeRule(SELECTOR_TAG).put("clash_mode", "Global"))
        if (settings.lanDirect) rules.put(routeRule("direct-out").put("ip_is_private", true))
        settings.customRules.filter { it.enabled }.forEach { buildCustomRule(it, ruleSetPaths)?.let(rules::put) }
        settings.routingGroups.filter { it.enabled }.forEach { group ->
            if (group.id == "ads" && !settings.blockAds) return@forEach
            if (group.id == "china" && !settings.chinaDirect) return@forEach
            val ids = group.ruleSetIds.filter(ruleSetPaths::containsKey)
            if (ids.isNotEmpty()) rules.put(routeRule(outboundFor(group.action)).put("rule_set", JSONArray(ids)))
        }
        put("rules", rules)
        put("final", SELECTOR_TAG)
        val sets = JSONArray()
        ruleSetPaths.forEach { (id, path) ->
            sets.put(JSONObject().put("type", "local").put("tag", id).put("format", "binary").put("path", path))
        }
        if (sets.length() > 0) put("rule_set", sets)
    }

    private fun routeRule(outbound: String) = JSONObject().put("action", "route").put("outbound", outbound)

    private fun buildCustomRule(rule: RoutingRule, ruleSetPaths: Map<String, String>): JSONObject? {
        val json = routeRule(outboundFor(rule.action))
        when (rule.matchType) {
            MatchType.DOMAIN -> json.put("domain", JSONArray().put(rule.pattern))
            MatchType.DOMAIN_SUFFIX -> json.put("domain_suffix", JSONArray().put(rule.pattern))
            MatchType.DOMAIN_KEYWORD -> json.put("domain_keyword", JSONArray().put(rule.pattern))
            MatchType.IP_CIDR -> json.put("ip_cidr", JSONArray().put(rule.pattern))
            MatchType.GEOSITE, MatchType.GEOIP -> {
                val id = rule.pattern.removePrefix("geosite:").removePrefix("geoip:")
                if (!ruleSetPaths.containsKey(id)) return null
                json.put("rule_set", JSONArray().put(id))
            }
        }
        return json
    }

    private fun outboundFor(action: RuleAction) = when (action) {
        RuleAction.PROXY -> SELECTOR_TAG
        RuleAction.DIRECT -> "direct-out"
        RuleAction.BLOCK -> "block-out"
    }
}
