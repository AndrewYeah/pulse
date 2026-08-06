package com.andrew.proxyapp.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class RuleSetDescriptor(
    val id: String,
    val fileName: String,
    val url: String,
    val bundledSha256: String,
    val source: String = "KaringX/karing-ruleset"
)

data class RuleSetStatus(
    val id: String,
    val path: String,
    val sha256: String,
    val updatedAt: Long,
    val bundled: Boolean
)

object RuleSetManager {
    private const val PREFS = "rule_set_state"
    private const val RULE_DIR = "rules"
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_RULE_SET_BYTES = 32L * 1024 * 1024
    private val updateMutex = Mutex()

    val descriptors = listOf(
        rule("ads", "ads.srs", "geosite/category-ads-all.srs", "08ca3a220378f8192437af48f91ab73ff750087dd84cafa625cc45ea29524cf1"),
        rule("apple", "apple.srs", "geosite/apple.srs", "456d259b7f6283e728b01a4b6940df8fc17eedfe7d2e3efa8a87c3d19858a9d4"),
        rule("google", "google.srs", "geosite/google.srs", "458084f14a27716a14ffcb93bf712ed7688b2446cb256d4004e78633c1e07f15"),
        rule("telegram-site", "telegram-site.srs", "geosite/telegram.srs", "b594e7f4376e6c2b5ebe0c088572223c1889efb7dc65b271f5762de408b1f531"),
        rule("telegram-ip", "telegram-ip.srs", "geoip/telegram.srs", "c5ae208004b0752baee307348ef820b9fe9e8aa3a14556f54af68791b6d4030b"),
        rule("github", "github.srs", "geosite/github.srs", "0a52c35eb64720d6b1f5cc457d33460347d3213ab55369165f0b9064d3f9b1bc"),
        rule("geosite-cn", "geosite-cn.srs", "geosite/cn.srs", "fc0d1839eaf81236e02e76c3de0786642bdad201ed62666afa32e9fdd3e277bc"),
        rule("geoip-cn", "geoip-cn.srs", "geoip/cn.srs", "e5ec6882395b67965bfe68516ecdab6f1af6837b64854c4c40161e74d260275c")
    )

    private fun rule(id: String, fileName: String, remotePath: String, sha: String) = RuleSetDescriptor(
        id,
        fileName,
        "https://raw.githubusercontent.com/KaringX/karing-ruleset/sing/$remotePath",
        sha
    )

    fun prepare(context: Context): Map<String, String> {
        val directory = File(context.noBackupFilesDir, RULE_DIR).apply { mkdirs() }
        descriptors.forEach { descriptor ->
            val target = File(directory, descriptor.fileName)
            if (!target.exists() || target.length() < 8) {
                context.assets.open("rules/${descriptor.fileName}").use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
        return descriptors.associate { it.id to File(directory, it.fileName).absolutePath }
    }

    fun statuses(context: Context): List<RuleSetStatus> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paths = prepare(context)
        return descriptors.map { descriptor ->
            val file = File(paths.getValue(descriptor.id))
            val hash = sha256(file)
            RuleSetStatus(
                descriptor.id,
                file.absolutePath,
                hash,
                prefs.getLong("${descriptor.id}_updated", 0),
                hash == descriptor.bundledSha256
            )
        }
    }

    suspend fun updateAll(
        context: Context,
        onProgress: (completed: Int, total: Int, id: String) -> Unit = { _, _, _ -> }
    ): Result<List<RuleSetStatus>> = withContext(Dispatchers.IO) {
        updateMutex.withLock { runCatching {
            val paths = prepare(context)
            val downloads = descriptors.map { descriptor ->
                val target = File(paths.getValue(descriptor.id))
                val temporary = File(target.parentFile, "${target.name}.download")
                temporary.delete()
                download(descriptor.url, temporary)
                require(temporary.length() >= 8 && temporary.inputStream().use { input ->
                    input.read() == 'S'.code && input.read() == 'R'.code && input.read() == 'S'.code
                }) { "${descriptor.id}: invalid SRS file" }
                Triple(descriptor, target, temporary)
            }
            val replaced = mutableListOf<Pair<File, File>>()
            val hashes = mutableMapOf<String, String>()
            try {
                downloads.forEachIndexed { index, (descriptor, target, temporary) ->
                    val newHash = sha256(temporary)
                    val backup = File(target.parentFile, "${target.name}.bak")
                    backup.delete()
                    if (target.exists() && !target.renameTo(backup)) error("${descriptor.id}: backup failed")
                    if (!temporary.renameTo(target)) {
                        backup.renameTo(target)
                        error("${descriptor.id}: replace failed")
                    }
                    replaced += target to backup
                    hashes[descriptor.id] = newHash
                    onProgress(index + 1, descriptors.size, descriptor.id)
                }
                replaced.forEach { (_, backup) -> backup.delete() }
                val now = System.currentTimeMillis()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    hashes.forEach { (id, hash) ->
                        putString("${id}_sha256", hash).putLong("${id}_updated", now)
                    }
                }
            } catch (error: Throwable) {
                replaced.asReversed().forEach { (target, backup) ->
                    target.delete()
                    backup.renameTo(target)
                }
                throw error
            } finally {
                downloads.forEach { (_, _, temporary) -> temporary.delete() }
            }
            statuses(context)
        } }
    }

    fun isWeeklyCheckDue(settings: AppSettings, now: Long = System.currentTimeMillis()): Boolean =
        now - settings.lastRuleSetCheck >= WEEK_MS

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Pulse/1.0")
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong !in (MAX_RULE_SET_BYTES + 1)..Long.MAX_VALUE) { "rule set is too large" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_RULE_SET_BYTES) { "rule set is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
