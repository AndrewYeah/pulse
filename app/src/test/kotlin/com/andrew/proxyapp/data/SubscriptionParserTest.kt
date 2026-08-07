package com.andrew.proxyapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionParserTest {
    @Test
    fun parsesClashTrojanSocksAndHttp() {
        val yaml = """
            proxies:
              - name: trojan-node
                type: trojan
                server: trojan.example.com
                port: 443
                password: secret
                sni: trojan.example.com
              - name: socks-node
                type: socks5
                server: 127.0.0.1
                port: 1080
                username: user
                password: pass
              - name: http-node
                type: http
                server: proxy.example.com
                port: 8080
        """.trimIndent()
        val result = SubscriptionManager.parseDetailed(yaml, "sub-1")
        assertEquals(SubscriptionFormat.CLASH_YAML, result.format)
        assertEquals(setOf(ProtocolType.TROJAN, ProtocolType.SOCKS5, ProtocolType.HTTP_PROXY), result.nodes.map { it.protocol }.toSet())
        assertTrue(result.nodes.all { it.id.length == 32 })
    }

    @Test
    fun mergesClashNodesWithTheSameConnectionIdentity() {
        val yaml = """
            proxies:
              - { name: first-name, type: vless, server: same.example.com, port: 443, uuid: 00000000-0000-4000-8000-000000000000, tls: true, servername: same.example.com }
              - { name: second-name, type: vless, server: same.example.com, port: 443, uuid: 00000000-0000-4000-8000-000000000000, tls: true, servername: same.example.com }
        """.trimIndent()

        val result = SubscriptionManager.parseDetailed(yaml, "sub-duplicate")

        assertEquals(1, result.nodes.size)
        assertTrue(result.issues.any { it.code == "node_duplicate" })
    }

    @Test
    fun parsesV2RayOutboundsAndIgnoresInboundRouting() {
        val json = """
            {
              "inbounds": [{"protocol":"dokodemo-door"}],
              "routing": {"rules": []},
              "outbounds": [
                {"tag":"direct","protocol":"direct","settings":{}},
                {"tag":"vmess-reality","protocol":"vmess","settings":{"vnext":[{"address":"vmess.example.com","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000000","alterId":0,"security":"auto"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"www.example.com","publicKey":"public-key","shortId":"short-id"}}},
                {"tag":"trojan","protocol":"trojan","settings":{"servers":[{"address":"trojan.example.com","port":443,"password":"secret"}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"trojan.example.com"},"wsSettings":{"path":"/ws","headers":{"Host":"trojan.example.com"}}}},
                {"tag":"socks","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080,"users":[{"user":"u","pass":"p"}]}]}}
              ]
            }
        """.trimIndent()
        val result = SubscriptionManager.parseDetailed(json, "sub-2")
        assertEquals(SubscriptionFormat.V2RAY_JSON, result.format)
        assertEquals(3, result.nodes.size)
        val reality = result.nodes.first { it.name == "vmess-reality" }
        assertEquals(ProtocolType.VMESS, reality.protocol)
        assertEquals("public-key", reality.realityPublicKey)
        assertEquals("short-id", reality.realityShortId)
        assertEquals(TransportType.WS, result.nodes.first { it.protocol == ProtocolType.TROJAN }.transportType)
        assertTrue(result.issues.none { it.code == "outbound_unsupported" })
    }

    @Test
    fun reportsServerConfigurationWhenOnlyDirectOutboundExists() {
        val json = """
            {
              "inbounds": [{"tag":"proxy-in","protocol":"vless","settings":{"clients":[]}}],
              "outbounds": [{"tag":"direct","protocol":"direct","settings":{}}]
            }
        """.trimIndent()

        val result = SubscriptionManager.parseDetailed(json, "sub-server")

        assertTrue(result.nodes.isEmpty())
        assertTrue(result.issues.any { it.code == "server_config_detected" && it.fatal })
        assertTrue(result.issues.none { it.code == "outbound_unsupported" })
    }

    @Test
    fun reportsMissingProxyOutboundForDirectOnlyClientConfiguration() {
        val json = """
            {"outbounds":[{"tag":"direct","protocol":"direct","settings":{}}]}
        """.trimIndent()

        val result = SubscriptionManager.parseDetailed(json, "sub-direct")

        assertTrue(result.nodes.isEmpty())
        assertTrue(result.issues.any { it.code == "proxy_outbounds_missing" && it.fatal })
    }

    @Test
    fun detectsSingBoxServerConfigurationInsteadOfMisclassifyingItAsV2Ray() {
        val json = """
            {
              "inbounds": [{"type":"hysteria2","listen":"0.0.0.0","listen_port":443}],
              "outbounds": [{"type":"direct","tag":"direct"}]
            }
        """.trimIndent()

        val result = SubscriptionManager.parseDetailed(json, "sub-singbox-server")

        assertEquals(SubscriptionFormat.SING_BOX_JSON, result.format)
        assertTrue(result.nodes.isEmpty())
        assertTrue(result.issues.any { it.code == "server_config_detected" && it.fatal })
        assertTrue(result.issues.none { it.code == "outbound_unsupported" })
    }

    @Test
    fun parsesSingBoxClientOutboundAfterIgnoringDirectOutbound() {
        val json = """
            {
              "inbounds": [{"type":"tun","interface_name":"tun0"}],
              "outbounds": [
                {"type":"direct","tag":"direct"},
                {"type":"hysteria2","tag":"node","server":"node.example.com","server_port":443,"password":"secret","tls":{"enabled":true,"server_name":"node.example.com"}}
              ]
            }
        """.trimIndent()

        val result = SubscriptionManager.parseDetailed(json, "sub-singbox-client")

        assertEquals(SubscriptionFormat.SING_BOX_JSON, result.format)
        assertEquals(1, result.nodes.size)
        assertEquals(ProtocolType.HYSTERIA2, result.nodes.single().protocol)
        assertTrue(result.issues.none { it.code == "outbound_unsupported" })
    }

    @Test
    fun parsesSsrAndMarksCoreUnsupportedWithoutDroppingIt() {
        val payload = "server.example.com:443:origin:aes-256-cfb:plain:${Base64.getEncoder().encodeToString("password".toByteArray())}/?remarks=${Base64.getEncoder().encodeToString("SSR node".toByteArray())}"
        val uri = "ssr://${Base64.getEncoder().withoutPadding().encodeToString(payload.toByteArray())}"
        val result = SubscriptionManager.parseDetailed(uri, "sub-3")
        assertEquals(1, result.nodes.size)
        assertEquals(ProtocolType.SHADOWSOCKSR, result.nodes.single().protocol)
        assertTrue(result.nodes.single().unsupportedReason.contains("not supported"))
    }

    @Test
    fun decodesBase64SubscriptionContent() {
        val json = "{\"outbounds\":[{\"protocol\":\"trojan\",\"tag\":\"encoded\",\"settings\":{\"servers\":[{\"address\":\"node.example.com\",\"port\":443,\"password\":\"secret\"}]}}]}"
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val result = SubscriptionManager.parseDetailed(encoded, "sub-b64")
        assertTrue(result.decodedBase64)
        assertEquals(ProtocolType.TROJAN, result.nodes.single().protocol)
    }

    @Test
    fun stableIdDoesNotChangeWhenDisplayNameChanges() {
        val first = ProxyConfig(name = "one", server = "example.com", port = 443, uuid = "00000000-0000-4000-8000-000000000000")
        val second = first.copy(name = "renamed")
        assertEquals(StableNodeId.forConfig(first, "sub"), StableNodeId.forConfig(second, "sub"))
    }

    @Test
    fun fetchAndParseFallsBackWhenFirstUserAgentReturnsInvalidContent() = runBlocking {
        val calls = mutableListOf<String>()
        SubscriptionManager.setFetcherForTests(object : SubscriptionManager.SubscriptionFetcher {
            override suspend fun fetch(url: String, userAgent: String): String {
                calls += userAgent
                return if (calls.size == 1) "not a subscription" else "{\"outbounds\":[{\"protocol\":\"trojan\",\"tag\":\"node\",\"settings\":{\"servers\":[{\"address\":\"node.example.com\",\"port\":443,\"password\":\"secret\"}]}}]}"
            }
        })
        try {
            val result = SubscriptionManager.fetchAndParse("https://example.com/sub", "sub-4")
            assertEquals(1, result.nodes.size)
            assertTrue(calls.size >= 2)
        } finally {
            SubscriptionManager.setFetcherForTests(null)
        }
    }

    @Test
    fun failedUpdateKeepsPreviousSnapshot() = runBlocking {
        val subscription = Subscription(id = "sub-5", name = "test", url = "https://example.com")
        val previous = ProxyConfig(id = "old", name = "old", server = "old.example.com", port = 443)
        val store = FakeSubscriptionStore(mutableListOf(previous))
        SubscriptionManager.setFetcherForTests(object : SubscriptionManager.SubscriptionFetcher {
            override suspend fun fetch(url: String, userAgent: String): String = "invalid content"
        })
        try {
            runCatching { SubscriptionManager.update(subscription, store) }.onSuccess {
                error("update should fail")
            }
            assertEquals(listOf(previous), store.nodes)
        } finally {
            SubscriptionManager.setFetcherForTests(null)
        }
    }

    @Test
    fun concurrentUpdatesShareOneDownloadAndResult() = runBlocking {
        val subscription = Subscription(id = "sub-dedup", name = "test", url = "https://example.com")
        val store = FakeSubscriptionStore(mutableListOf())
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val response = """
            {"outbounds":[{"protocol":"trojan","tag":"node","settings":{"servers":[{"address":"node.example.com","port":443,"password":"secret"}]}}]}
        """.trimIndent()
        SubscriptionManager.setFetcherForTests(object : SubscriptionManager.SubscriptionFetcher {
            override suspend fun fetch(url: String, userAgent: String): String {
                calls.incrementAndGet()
                fetchStarted.complete(Unit)
                releaseFetch.await()
                return response
            }
        })
        try {
            val first = async { SubscriptionManager.update(subscription, store) }
            fetchStarted.await()
            val second = async { SubscriptionManager.update(subscription, store) }
            releaseFetch.complete(Unit)

            assertEquals(1, first.await())
            assertEquals(1, second.await())
            assertEquals(1, calls.get())
            assertEquals(1, store.nodes.size)
        } finally {
            SubscriptionManager.setFetcherForTests(null)
        }
    }

    @Test
    fun successfulFallbackDoesNotOverwriteMetadataWithEarlierError() = runBlocking {
        val subscription = Subscription(id = "sub-metadata", name = "test", url = "https://example.com")
        val store = FakeSubscriptionStore(mutableListOf())
        val calls = AtomicInteger()
        SubscriptionManager.setFetcherForTests(object : SubscriptionManager.SubscriptionFetcher {
            override suspend fun fetch(url: String, userAgent: String): String {
                if (calls.incrementAndGet() == 1) return "invalid content"
                return """
                    {"outbounds":[{"protocol":"trojan","tag":"node","settings":{"servers":[{"address":"node.example.com","port":443,"password":"secret"}]}}]}
                """.trimIndent()
            }
        })
        try {
            assertEquals(1, SubscriptionManager.update(subscription, store))
            assertEquals("", store.savedSubscription?.lastError)
            assertEquals(1, store.savedSubscription?.nodeCount)
            assertEquals(2, calls.get())
        } finally {
            SubscriptionManager.setFetcherForTests(null)
        }
    }

    private class FakeSubscriptionStore(var nodes: MutableList<ProxyConfig>) : SubscriptionStore {
        var savedSubscription: Subscription? = null

        override fun replaceSubscriptionConfigs(subscription: Subscription, configs: List<ProxyConfig>) {
            nodes = configs.toMutableList()
            savedSubscription = subscription
        }

        override fun saveSubscription(subscription: Subscription) {
            savedSubscription = subscription
        }
    }
}
