package com.andrew.proxyapp.data

enum class NodeSort { NAME, PROTOCOL, LATENCY, UPDATED }

data class NodeQuery(
    val text: String = "",
    val protocol: ProtocolType? = null,
    val subscriptionId: String? = null,
    val sort: NodeSort = NodeSort.NAME
)

object NodeQueryEngine {
    fun apply(
        configs: Iterable<ProxyConfig>,
        query: NodeQuery,
        latencies: Map<String, NodeLatency> = emptyMap()
    ): List<ProxyConfig> {
        val text = query.text.trim()
        return configs.asSequence()
            .filter { text.isBlank() || it.name.contains(text, true) || it.server.contains(text, true) || it.protocol.displayName.contains(text, true) }
            .filter { query.protocol == null || it.protocol == query.protocol }
            .filter { query.subscriptionId == null || it.subscriptionId == query.subscriptionId }
            .sortedWith(compareBy<ProxyConfig> {
                when (query.sort) {
                    NodeSort.NAME -> it.name.lowercase()
                    NodeSort.PROTOCOL -> it.protocol.displayName.lowercase()
                    NodeSort.LATENCY -> latencies[it.id]?.delayMs ?: Int.MAX_VALUE
                    NodeSort.UPDATED -> -it.lastTestedAt
                }
            }.thenBy { it.server.lowercase() })
            .toList()
    }
}
