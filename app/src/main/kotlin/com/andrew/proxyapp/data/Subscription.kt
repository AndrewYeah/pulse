package com.andrew.proxyapp.data

/**
 * 机场订阅数据模型
 */
data class Subscription(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var url: String = "",
    var lastUpdated: Long = 0,
    var nodeCount: Int = 0,
    var lastAttempt: Long = 0,
    var lastError: String = ""
)
