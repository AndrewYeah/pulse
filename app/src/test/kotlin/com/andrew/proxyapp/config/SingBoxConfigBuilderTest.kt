package com.andrew.proxyapp.config

import com.andrew.proxyapp.data.AppSettings
import com.andrew.proxyapp.data.PerAppMode
import com.andrew.proxyapp.data.ProtocolType
import com.andrew.proxyapp.data.ProxyConfig
import com.andrew.proxyapp.data.RouteMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {
    private val rules = mapOf(
        "ads" to "/rules/ads.srs", "apple" to "/rules/apple.srs",
        "google" to "/rules/google.srs", "telegram-site" to "/rules/telegram-site.srs",
        "telegram-ip" to "/rules/telegram-ip.srs", "github" to "/rules/github.srs",
        "geosite-cn" to "/rules/geosite-cn.srs", "geoip-cn" to "/rules/geoip-cn.srs"
    )

    @Test fun buildsSelectorWithStableTagsAndAllProtocols() {
        val configs = ProtocolType.entries.filter { it != ProtocolType.SHADOWSOCKSR }
            .mapIndexed { index, type -> node("id-$index", type) }
        val json = build(configs, configs[2].id)
        val outbounds = json.getJSONArray("outbounds")
        val selector = outbounds.getJSONObject(0)
        assertEquals("selector", selector.getString("type"))
        assertEquals("proxy-out", selector.getString("tag"))
        assertEquals("node-id-2", selector.getString("default"))
        assertEquals(configs.size + 3, outbounds.length())
        ProtocolType.entries.filter { it != ProtocolType.SHADOWSOCKSR }.forEach { type ->
            assertTrue((1 until outbounds.length()).any { outbounds.getJSONObject(it).optString("type") == type.value })
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShadowsocksRBecauseCoreHasNoOutbound() {
        build(listOf(node("ssr", ProtocolType.SHADOWSOCKSR).apply {
            ssrProtocol = "origin"
            ssrObfs = "plain"
        }), "ssr")
    }

    @Test fun ruleModeOrdersGlobalPrivateAdsAndChinaBeforeFinal() {
        val json = build(listOf(node("one", ProtocolType.HYSTERIA2)), "one")
        val route = json.getJSONObject("route")
        val routeRules = route.getJSONArray("rules")
        assertEquals("sniff", routeRules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", routeRules.getJSONObject(1).getString("action"))
        assertEquals("Global", routeRules.getJSONObject(2).getString("clash_mode"))
        assertTrue((0 until routeRules.length()).any { routeRules.getJSONObject(it).optBoolean("ip_is_private") })
        assertEquals("proxy-out", route.getString("final"))
        assertEquals(rules.size, route.getJSONArray("rule_set").length())
    }

    @Test fun writesDnsModeAndPerAppPackages() {
        val settings = AppSettings(
            routeMode = RouteMode.GLOBAL,
            perAppMode = PerAppMode.INCLUDE_SELECTED,
            selectedPackages = mutableSetOf("com.example.one", "com.andrew.proxyapp")
        )
        val json = JSONObject(SingBoxConfigBuilder.build(
            listOf(node("one", ProtocolType.VLESS)), "one", settings, rules,
            "com.andrew.proxyapp"
        ))
        assertEquals("Global", json.getJSONObject("experimental").getJSONObject("clash_api").getString("default_mode"))
        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, tun.getJSONArray("include_package").length())
        assertEquals("com.example.one", tun.getJSONArray("include_package").getString(0))
        val dns = json.getJSONObject("dns")
        assertEquals("dns_proxy", dns.getString("final"))
        assertEquals("dns_direct", dns.getJSONArray("rules").getJSONObject(0).getString("server"))
    }

    @Test fun routesAssignedAppsToStableNodeBeforeGlobalAndCapturesThem() {
        val configs = listOf(node("one", ProtocolType.VLESS), node("two", ProtocolType.HYSTERIA2))
        val settings = AppSettings(
            routeMode = RouteMode.GLOBAL,
            perAppMode = PerAppMode.EXCLUDE_SELECTED,
            selectedPackages = mutableSetOf("com.example.assigned", "com.example.excluded"),
            appNodeAssignments = mutableMapOf(
                "com.example.assigned" to "two",
                "com.example.stale" to "missing"
            )
        )
        val json = JSONObject(SingBoxConfigBuilder.build(
            configs, "one", settings, rules, "com.andrew.proxyapp"
        ))

        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, tun.getJSONArray("exclude_package").length())
        assertEquals("com.example.excluded", tun.getJSONArray("exclude_package").getString(0))

        val routeRules = json.getJSONObject("route").getJSONArray("rules")
        val appRule = routeRules.getJSONObject(2)
        assertEquals("node-two", appRule.getString("outbound"))
        assertEquals("com.example.assigned", appRule.getJSONArray("package_name").getString(0))
        assertEquals("Global", routeRules.getJSONObject(3).getString("clash_mode"))
        assertTrue((0 until routeRules.length()).none {
            routeRules.getJSONObject(it).optString("outbound") == "node-missing"
        })
    }

    @Test fun writesVlessRealityTlsFields() {
        val config = node("reality", ProtocolType.VLESS).apply {
            realityPublicKey = "public-key"
            realityShortId = "short-id"
        }
        val outbound = build(listOf(config), config.id).getJSONArray("outbounds").getJSONObject(1)
        val reality = outbound.getJSONObject("tls").getJSONObject("reality")
        assertTrue(reality.getBoolean("enabled"))
        assertEquals("public-key", reality.getString("public_key"))
        assertEquals("short-id", reality.getString("short_id"))
    }

    @Test fun preservesVlessPacketEncodingAndVmessSecurity() {
        val vless = node("vless", ProtocolType.VLESS).apply { packetEncoding = "xudp" }
        val vmess = node("vmess", ProtocolType.VMESS).apply { vmessSecurity = "aes-128-gcm" }
        val outbounds = build(listOf(vless, vmess), vless.id).getJSONArray("outbounds")
        assertEquals("xudp", outbounds.getJSONObject(1).getString("packet_encoding"))
        assertEquals("aes-128-gcm", outbounds.getJSONObject(2).getString("security"))
    }

    @Test fun writesVmessRealityAndTrojanSocksHttpCredentials() {
        val vmess = node("vmess-reality", ProtocolType.VMESS).apply {
            realityPublicKey = "key"
            realityShortId = "sid"
        }
        val trojan = node("trojan", ProtocolType.TROJAN)
        val socks = node("socks", ProtocolType.SOCKS5).apply { username = "u"; password = "p"; tlsEnabled = false }
        val http = node("http", ProtocolType.HTTP_PROXY).apply { username = "u"; password = "p"; tlsEnabled = false }
        val outbounds = build(listOf(vmess, trojan, socks, http), vmess.id).getJSONArray("outbounds")
        assertTrue(outbounds.getJSONObject(1).getJSONObject("tls").has("reality"))
        assertEquals("trojan", outbounds.getJSONObject(2).getString("type"))
        assertEquals("u", outbounds.getJSONObject(3).getString("username"))
        assertEquals("http", outbounds.getJSONObject(4).getString("type"))
    }

    @Test fun removesDuplicateStableTagsBeforeBuildingSelector() {
        val first = node("same-id", ProtocolType.VLESS)
        val duplicate = first.copy(name = "same connection with another display name")
        val outbounds = build(listOf(first, duplicate), first.id).getJSONArray("outbounds")
        val selector = outbounds.getJSONObject(0).getJSONArray("outbounds")

        assertEquals(1, selector.length())
        assertEquals("node-same-id", selector.getString(0))
        assertEquals(4, outbounds.length())
    }

    private fun build(configs: List<ProxyConfig>, active: String) = JSONObject(
        SingBoxConfigBuilder.build(configs, active, AppSettings(), rules, "com.andrew.proxyapp")
    )

    private fun node(id: String, type: ProtocolType) = ProxyConfig(
        id = id, name = id, protocol = type, server = "example.com", port = 443,
        uuid = "00000000-0000-4000-8000-000000000000", password = "password",
        ssMethod = "aes-128-gcm"
    )
}
