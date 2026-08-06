package com.andrew.proxyapp.ui

import android.content.Context
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.MatchType
import com.andrew.proxyapp.data.ProtocolType
import com.andrew.proxyapp.data.RuleAction
import com.andrew.proxyapp.data.RoutingGroup
import com.andrew.proxyapp.data.Subscription
import com.andrew.proxyapp.data.TransportType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun ProtocolType.localizedLabel(context: Context): String = context.getString(
    when (this) {
        ProtocolType.VLESS -> R.string.protocol_vless
        ProtocolType.VMESS -> R.string.protocol_vmess
        ProtocolType.HYSTERIA2 -> R.string.protocol_hysteria2
        ProtocolType.TUIC -> R.string.protocol_tuic
        ProtocolType.ANYTLS -> R.string.protocol_anytls
        ProtocolType.SHADOWSOCKS -> R.string.protocol_shadowsocks
        ProtocolType.TROJAN -> R.string.protocol_trojan
        ProtocolType.SOCKS5 -> R.string.protocol_socks5
        ProtocolType.HTTP_PROXY -> R.string.protocol_http
        ProtocolType.SHADOWSOCKSR -> R.string.protocol_shadowsocksr
    }
)

fun TransportType.localizedLabel(context: Context): String = context.getString(
    when (this) {
        TransportType.NONE -> R.string.transport_none
        TransportType.WS -> R.string.transport_websocket
        TransportType.GRPC -> R.string.transport_grpc
        TransportType.HTTP -> R.string.transport_http
        TransportType.HTTPUPGRADE -> R.string.transport_httpupgrade
    }
)

fun MatchType.localizedLabel(context: Context): String = context.getString(
    when (this) {
        MatchType.DOMAIN -> R.string.match_domain
        MatchType.DOMAIN_SUFFIX -> R.string.match_domain_suffix
        MatchType.DOMAIN_KEYWORD -> R.string.match_domain_keyword
        MatchType.IP_CIDR -> R.string.match_ip_cidr
        MatchType.GEOSITE -> R.string.match_geosite
        MatchType.GEOIP -> R.string.match_geoip
    }
)

fun RuleAction.localizedLabel(context: Context): String = context.getString(
    when (this) {
        RuleAction.PROXY -> R.string.action_proxy
        RuleAction.DIRECT -> R.string.action_direct
        RuleAction.BLOCK -> R.string.action_block
    }
)

fun RoutingGroup.localizedName(context: Context): String = when (id) {
    "ads" -> context.getString(R.string.routing_group_ads)
    "apple" -> context.getString(R.string.routing_group_apple)
    "google" -> context.getString(R.string.routing_group_google)
    "telegram" -> context.getString(R.string.routing_group_telegram)
    "github" -> context.getString(R.string.routing_group_github)
    "china" -> context.getString(R.string.routing_group_china)
    else -> name
}

fun Subscription.localizedSummary(context: Context): String =
    context.resources.getQuantityString(R.plurals.subscription_nodes_count, nodeCount, nodeCount)

fun Subscription.localizedLastUpdated(context: Context): String {
    if (lastUpdated <= 0) return context.getString(R.string.subscription_not_updated)
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(lastUpdated))
    return context.getString(R.string.subscription_updated_at, formatted)
}
