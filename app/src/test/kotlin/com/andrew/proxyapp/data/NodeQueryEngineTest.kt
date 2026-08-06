package com.andrew.proxyapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeQueryEngineTest {
    @Test
    fun filtersByProtocolAndSortsLatency() {
        val nodes = listOf(
            ProxyConfig(id = "a", name = "slow", protocol = ProtocolType.VLESS, server = "a", port = 443),
            ProxyConfig(id = "b", name = "fast", protocol = ProtocolType.VLESS, server = "b", port = 443),
            ProxyConfig(id = "c", name = "other", protocol = ProtocolType.TROJAN, server = "c", port = 443, password = "p")
        )
        val result = NodeQueryEngine.apply(
            nodes,
            NodeQuery(protocol = ProtocolType.VLESS, sort = NodeSort.LATENCY),
            mapOf("a" to NodeLatency("a", 200, 1), "b" to NodeLatency("b", 20, 1))
        )
        assertEquals(listOf("b", "a"), result.map { it.id })
    }
}
