package com.andrew.proxyapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 应用全局设置（DNS、分流规则等）
 */
data class AppSettings(
    var schemaVersion: Int = CURRENT_SETTINGS_SCHEMA,

    // DNS 设置
    var remoteDns: String = "https://1.1.1.1/dns-query",
    var localDns: String = "https://223.5.5.5/dns-query",
    var dnsStrategy: String = "ipv4_only",

    // 分流设置
    var chinaDirect: Boolean = true,        // 中国大陆直连
    var blockAds: Boolean = true,           // 广告拦截
    var lanDirect: Boolean = true,          // 局域网直连
    var skipCertVerify: Boolean = false,    // 仅用于显式诊断，默认必须验证证书

    // 自定义分流规则
    var customRules: MutableList<RoutingRule> = mutableListOf(),

    // Karing 风格运行与界面设置
    var themeMode: ThemeMode = ThemeMode.LIGHT,
    var routeMode: RouteMode = RouteMode.RULE,
    var routingGroups: MutableList<RoutingGroup> = defaultRoutingGroups(),
    var perAppMode: PerAppMode = PerAppMode.EXCLUDE_SELECTED,
    var selectedPackages: MutableSet<String> = mutableSetOf(),
    var appNodeAssignments: MutableMap<String, String> = mutableMapOf(),
    var lastRuleSetCheck: Long = 0,
    var testUrl: String = "https://www.gstatic.com/generate_204",
    var language: String = "zh-CN",
    var languageSelectionCompleted: Boolean = false,

    // 当前选中的配置 ID
    var activeConfigId: String = "",

    // GitHub Release update notice cache
    var availableUpdateVersion: String = "",
    var availableUpdateUrl: String = ""
)

const val CURRENT_SETTINGS_SCHEMA = 7

object ConfigurationChanges {
    private val _events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()
    fun notifyRuntimeConfigChanged() { _events.tryEmit(Unit) }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class RouteMode { RULE, GLOBAL }
enum class PerAppMode { EXCLUDE_SELECTED, INCLUDE_SELECTED }

data class RoutingGroup(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var enabled: Boolean = true,
    var action: RuleAction = RuleAction.PROXY,
    var ruleSetIds: MutableList<String> = mutableListOf()
)

fun defaultRoutingGroups(): MutableList<RoutingGroup> = mutableListOf(
    RoutingGroup(id = "ads", name = "广告拦截", action = RuleAction.BLOCK, ruleSetIds = mutableListOf("ads")),
    RoutingGroup(id = "apple", name = "Apple", action = RuleAction.DIRECT, ruleSetIds = mutableListOf("apple")),
    RoutingGroup(id = "google", name = "Google", action = RuleAction.PROXY, ruleSetIds = mutableListOf("google")),
    RoutingGroup(id = "telegram", name = "Telegram", action = RuleAction.PROXY, ruleSetIds = mutableListOf("telegram-site", "telegram-ip")),
    RoutingGroup(id = "github", name = "GitHub", action = RuleAction.PROXY, ruleSetIds = mutableListOf("github")),
    RoutingGroup(id = "china", name = "中国大陆", action = RuleAction.DIRECT, ruleSetIds = mutableListOf("geosite-cn", "geoip-cn"))
)

data class RoutingRule(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var matchType: MatchType = MatchType.DOMAIN_SUFFIX,
    var pattern: String = "",
    var action: RuleAction = RuleAction.PROXY,
    var enabled: Boolean = true
)

enum class MatchType(val displayName: String) {
    DOMAIN("完整域名"),
    DOMAIN_SUFFIX("域名后缀"),
    DOMAIN_KEYWORD("域名关键字"),
    IP_CIDR("IP CIDR"),
    GEOSITE("GeoSite"),
    GEOIP("GeoIP")
}

enum class RuleAction(val displayName: String) {
    PROXY("走代理"),
    DIRECT("直连"),
    BLOCK("拒绝")
}

/**
 * 配置存储管理器
 * 使用 SharedPreferences + Gson 持久化
 */
class ConfigStore private constructor(context: Context) : SubscriptionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proxy_configs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val secretCodec = SecretCodec()

    companion object {
        private const val KEY_CONFIGS = "proxy_config_list"
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_SUBSCRIPTIONS = "subscription_list"
        private const val KEY_DESIRED_RUNNING = "runtime_desired_running"
        private const val KEY_START_FAILURES = "runtime_start_failures"
        private const val KEY_LAST_RUNTIME_ERROR = "runtime_last_error"
        private const val MAX_START_FAILURES = 3
        private const val START_FAILURE_WINDOW_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: ConfigStore? = null

        fun get(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context.applicationContext).also { instance = it }
            }
    }

    // ========== ProxyConfig 管理 ==========

    @Synchronized
    fun getAllConfigs(): MutableList<ProxyConfig> {
        val json = readSecureString(KEY_CONFIGS, "[]")
        val type = object : TypeToken<MutableList<ProxyConfig>>() {}.type
        val list: MutableList<ProxyConfig> = parseOrRecover(KEY_CONFIGS, json) {
            gson.fromJson<MutableList<ProxyConfig>>(it, type) ?: mutableListOf()
        }
        val replacements = list.mapNotNull { config ->
            if (config.subscriptionId.isBlank()) return@mapNotNull null
            val stable = StableNodeId.forConfig(config, config.subscriptionId)
            if (stable != config.id) config.id to stable else null
        }.toMap()
        if (replacements.isNotEmpty()) {
            list.forEach { config -> replacements[config.id]?.let { config.id = it } }
            val settingsJson = readSecureString(KEY_SETTINGS, "")
            if (settingsJson.isNotBlank()) {
                val settings = runCatching { gson.fromJson(settingsJson, AppSettings::class.java) }
                    .getOrNull() ?: AppSettings()
                settings.activeConfigId = replacements[settings.activeConfigId] ?: settings.activeConfigId
                settings.appNodeAssignments = settings.appNodeAssignments.mapValues { (_, id) -> replacements[id] ?: id }.toMutableMap()
                prefs.edit { putString(KEY_SETTINGS, secretCodec.encrypt(gson.toJson(settings))) }
            }
        }
        val deduplicated = list.distinctBy { it.id }.toMutableList()
        if (replacements.isNotEmpty() || deduplicated.size != list.size) {
            prefs.edit { putString(KEY_CONFIGS, secretCodec.encrypt(gson.toJson(deduplicated))) }
        }
        return deduplicated
    }

    fun getConfig(id: String): ProxyConfig? = getAllConfigs().find { it.id == id }

    @Synchronized
    fun saveConfig(config: ProxyConfig) {
        val list = getAllConfigs()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        writeConfigs(list)
        ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    @Synchronized
    fun deleteConfig(id: String) {
        val list = getAllConfigs().filter { it.id != id }.toMutableList()
        writeConfigs(list)
        val settings = getSettings()
        val assignmentsChanged = settings.appNodeAssignments.entries.removeAll { it.value == id }
        val activeChanged = settings.activeConfigId == id
        if (activeChanged) settings.activeConfigId = ""
        if (activeChanged || assignmentsChanged) {
            saveSettings(settings, notifyRuntime = false)
        }
        ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    fun getActiveConfig(): ProxyConfig? {
        val settings = getSettings()
        if (settings.activeConfigId.isBlank()) return getAllConfigs().firstOrNull()
        return getConfig(settings.activeConfigId) ?: getAllConfigs().firstOrNull()
    }

    @Synchronized
    fun setActiveConfig(id: String) {
        val settings = getSettings()
        settings.activeConfigId = id
        saveSettings(settings, notifyRuntime = false)
    }

    // ========== AppSettings 管理 ==========

    @Synchronized
    fun getSettings(): AppSettings {
        val json = readSecureString(KEY_SETTINGS, gson.toJson(AppSettings()))
        val settings = parseOrRecover(KEY_SETTINGS, json) {
            gson.fromJson(it, AppSettings::class.java) ?: AppSettings()
        }
        var changed = false
        if (settings.schemaVersion < 2) {
            // v1 的 remoteDns 实际走直连；v2 将其明确拆成代理 DNS 与直连 DNS。
            settings.remoteDns = "https://1.1.1.1/dns-query"
            if (settings.localDns == "223.5.5.5") settings.localDns = "https://223.5.5.5/dns-query"
            changed = true
        }
        if (settings.schemaVersion < 5) {
            settings.skipCertVerify = false
            changed = true
        }
        if (settings.schemaVersion < 6) {
            // Existing installations already have a language preference; do not show onboarding after upgrade.
            settings.languageSelectionCompleted = true
            changed = true
        }
        if (settings.schemaVersion < 7) {
            settings.availableUpdateVersion = ""
            settings.availableUpdateUrl = ""
            changed = true
        }
        if (settings.schemaVersion < CURRENT_SETTINGS_SCHEMA) {
            settings.schemaVersion = CURRENT_SETTINGS_SCHEMA
            changed = true
        }
        if (settings.routingGroups.isEmpty()) {
            settings.routingGroups = defaultRoutingGroups()
            changed = true
        }
        if (changed) {
            saveSettings(settings, notifyRuntime = false)
        }
        return settings
    }

    @Synchronized
    fun saveSettings(settings: AppSettings, notifyRuntime: Boolean = true) {
        prefs.edit { putString(KEY_SETTINGS, secretCodec.encrypt(gson.toJson(settings))) }
        if (notifyRuntime) ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    @Synchronized
    fun updateSettings(notifyRuntime: Boolean = true, update: (AppSettings) -> Unit): AppSettings {
        val settings = getSettings()
        update(settings)
        saveSettings(settings, notifyRuntime)
        return settings
    }

    @Synchronized
    fun getAvailableUpdate(): AppUpdateInfo? {
        val settings = getSettings()
        if (settings.availableUpdateVersion.isBlank() || settings.availableUpdateUrl.isBlank()) return null
        return AppUpdateInfo(
            version = settings.availableUpdateVersion,
            releaseUrl = settings.availableUpdateUrl
        )
    }

    @Synchronized
    fun saveAvailableUpdate(update: AppUpdateInfo) {
        updateSettings(notifyRuntime = false) {
            it.availableUpdateVersion = update.version
            it.availableUpdateUrl = update.releaseUrl
        }
    }

    @Synchronized
    fun clearAvailableUpdate() {
        val settings = getSettings()
        if (settings.availableUpdateVersion.isBlank() && settings.availableUpdateUrl.isBlank()) return
        settings.availableUpdateVersion = ""
        settings.availableUpdateUrl = ""
        saveSettings(settings, notifyRuntime = false)
    }

    // ========== Subscription 管理 ==========

    fun getSubscriptions(): MutableList<Subscription> {
        val json = readSecureString(KEY_SUBSCRIPTIONS, "[]")
        val type = object : TypeToken<MutableList<Subscription>>() {}.type
        return parseOrRecover(KEY_SUBSCRIPTIONS, json) {
            gson.fromJson<MutableList<Subscription>>(it, type) ?: mutableListOf()
        }
    }

    fun getSubscription(id: String): Subscription? = getSubscriptions().find { it.id == id }

    @Synchronized
    override fun saveSubscription(subscription: Subscription) {
        val list = getSubscriptions()
        val idx = list.indexOfFirst { it.id == subscription.id }
        if (idx >= 0) list[idx] = subscription else list.add(subscription)
        prefs.edit { putString(KEY_SUBSCRIPTIONS, secretCodec.encrypt(gson.toJson(list))) }
    }

    @Synchronized
    fun deleteSubscription(id: String) {
        // 同时删除该订阅导入的所有节点
        deleteConfigsBySubscription(id)
        val list = getSubscriptions().filter { it.id != id }.toMutableList()
        prefs.edit { putString(KEY_SUBSCRIPTIONS, secretCodec.encrypt(gson.toJson(list))) }
        ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    /** 删除指定订阅关联的所有节点配置 */
    @Synchronized
    fun deleteConfigsBySubscription(subscriptionId: String) {
        val current = getAllConfigs()
        val removedIds = current.filter { it.subscriptionId == subscriptionId }.mapTo(mutableSetOf()) { it.id }
        val list = current.filter { it.subscriptionId != subscriptionId }.toMutableList()
        writeConfigs(list)
        removeStaleConfigReferences(removedIds, list.firstOrNull()?.id)
        ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    /** 获取指定订阅关联的节点列表 */
    fun getConfigsBySubscription(subscriptionId: String): List<ProxyConfig> =
        getAllConfigs().filter { it.subscriptionId == subscriptionId }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun replaceSubscriptionConfigs(subscription: Subscription, configs: List<ProxyConfig>) {
        require(configs.isNotEmpty()) { "subscription update produced no valid nodes" }
        val retained = getAllConfigs().filter { it.subscriptionId != subscription.id }.toMutableList()
        val previousIds = getAllConfigs().filter { it.subscriptionId == subscription.id }.mapTo(mutableSetOf()) { it.id }
        retained.addAll(configs)
        val subscriptions = getSubscriptions()
        val index = subscriptions.indexOfFirst { it.id == subscription.id }
        if (index >= 0) subscriptions[index] = subscription else subscriptions.add(subscription)
        prefs.edit(commit = true) {
            putString(KEY_CONFIGS, secretCodec.encrypt(gson.toJson(retained)))
            putString(KEY_SUBSCRIPTIONS, secretCodec.encrypt(gson.toJson(subscriptions)))
        }
        removeStaleConfigReferences(previousIds - configs.mapTo(mutableSetOf()) { it.id }, configs.firstOrNull()?.id)
        ConfigurationChanges.notifyRuntimeConfigChanged()
    }

    // ========== Runtime lifecycle persistence ==========

    fun desiredRunning(): Boolean = prefs.getBoolean(KEY_DESIRED_RUNNING, false)

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun setDesiredRunning(value: Boolean) {
        prefs.edit(commit = true) { putBoolean(KEY_DESIRED_RUNNING, value) }
    }

    /** Records recent failed starts so a broken configuration cannot crash-loop forever. */
    @Synchronized
    fun recordStartFailure(now: Long = System.currentTimeMillis()): Int {
        val type = object : TypeToken<MutableList<Long>>() {}.type
        val values: MutableList<Long> = gson.fromJson(
            prefs.getString(KEY_START_FAILURES, "[]") ?: "[]", type
        ) ?: mutableListOf()
        values.removeAll { now - it > START_FAILURE_WINDOW_MS }
        values.add(now)
        prefs.edit { putString(KEY_START_FAILURES, gson.toJson(values)) }
        return values.size
    }

    @Synchronized
    fun clearStartFailures() {
        prefs.edit { remove(KEY_START_FAILURES) }
    }

    fun shouldSuppressAutoRestart(now: Long = System.currentTimeMillis()): Boolean {
        val type = object : TypeToken<List<Long>>() {}.type
        val values: List<Long> = gson.fromJson(
            prefs.getString(KEY_START_FAILURES, "[]") ?: "[]", type
        ) ?: emptyList()
        return values.count { now - it <= START_FAILURE_WINDOW_MS } >= MAX_START_FAILURES
    }

    fun lastRuntimeError(): String? = prefs.getString(KEY_LAST_RUNTIME_ERROR, null)

    fun setLastRuntimeError(message: String?) {
        prefs.edit { putString(KEY_LAST_RUNTIME_ERROR, message) }
    }

    private fun writeConfigs(configs: List<ProxyConfig>) {
        prefs.edit { putString(KEY_CONFIGS, secretCodec.encrypt(gson.toJson(configs))) }
    }

    private fun removeStaleConfigReferences(removedIds: Set<String>, fallbackId: String?) {
        if (removedIds.isEmpty()) return
        updateSettings(notifyRuntime = false) { settings ->
            if (settings.activeConfigId in removedIds) settings.activeConfigId = fallbackId.orEmpty()
            settings.appNodeAssignments.entries.removeAll { it.value in removedIds }
        }
    }

    private fun readSecureString(key: String, fallback: String): String {
        val stored = prefs.getString(key, null) ?: return fallback
        if (!stored.startsWith(SecretCodec.PREFIX)) {
            prefs.edit { putString(key, secretCodec.encrypt(stored)) }
            return stored
        }
        return runCatching { secretCodec.decrypt(stored) }.getOrElse {
            prefs.edit { remove(key) }
            fallback
        }
    }

    private inline fun <T> parseOrRecover(key: String, raw: String, parse: (String) -> T): T {
        return try {
            parse(raw)
        } catch (_: JsonSyntaxException) {
            prefs.edit { remove(key) }
            parse(if (key == KEY_SETTINGS) gson.toJson(AppSettings()) else "[]")
        } catch (_: IllegalArgumentException) {
            prefs.edit { remove(key) }
            parse(if (key == KEY_SETTINGS) gson.toJson(AppSettings()) else "[]")
        }
    }

}

private class SecretCodec {
    companion object {
        const val PREFIX = "enc:v1:"
        private const val KEY_ALIAS = "pulse-config-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
    @Volatile private var cachedKey: SecretKey? = null

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$PREFIX$iv:$encrypted"
    }

    fun decrypt(value: String): String {
        val parts = value.removePrefix(PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "invalid encrypted preference" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) { cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        val generated = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
        cachedKey = generated
        return generated
        }
    }
}
