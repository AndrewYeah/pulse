package com.andrew.proxyapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.RuleSetManager
import com.andrew.proxyapp.manager.ProxyManager
import com.andrew.proxyapp.manager.RuntimeController
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
    private val store by lazy { ConfigStore.get(this) }
    private lateinit var summary: TextView
    private lateinit var logs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        configureSystemBars()
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        summary = findViewById(R.id.summary); logs = findViewById(R.id.logs)
        renderSummary()
        findViewById<MaterialButton>(R.id.updateRules).setOnClickListener { updateRules(it) }
        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener { RuntimeController.clearLogsNow() }
        findViewById<MaterialButton>(R.id.copyButton).setOnClickListener { view ->
            val text = "${summary.text}\n\n${logs.text}"
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pulse diagnostics", text))
            Snackbar.make(view, R.string.copied, Snackbar.LENGTH_SHORT).show()
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            RuntimeController.logs.collect { logs.text = it.joinToString("\n") }
        } }
        val settings = store.getSettings()
        if (RuleSetManager.isWeeklyCheckDue(settings)) updateRules(findViewById(R.id.updateRules), silent = true)
    }

    private fun renderSummary() {
        val statuses = RuleSetManager.statuses(this)
        summary.text = buildString {
            appendLine("Core: ${Libbox.version()}")
            appendLine("VPN: ${ProxyManager.state.value}")
            appendLine("Rules: ${statuses.size}/${RuleSetManager.descriptors.size}")
            statuses.forEach { appendLine("• ${it.id}: ${if (it.bundled) "bundled" else "updated"} ${it.sha256.take(8)}") }
        }
    }

    private fun updateRules(view: android.view.View, silent: Boolean = false) {
        view.isEnabled = false
        lifecycleScope.launch {
            val result = RuleSetManager.updateAll(this@DiagnosticsActivity) { done, total, _ ->
                runOnUiThread { (view as? MaterialButton)?.text = getString(R.string.progress_count, done, total) }
            }
            view.isEnabled = true
            (view as? MaterialButton)?.setText(R.string.update_rules)
            if (result.isSuccess) {
                store.updateSettings { it.lastRuleSetCheck = System.currentTimeMillis() }
            }
            renderSummary()
            if (!silent || result.isFailure) Snackbar.make(
                view,
                result.fold(
                    { getString(R.string.rules_updated) },
                    { getString(R.string.rules_update_failed, it.message.orEmpty()) }
                ),
                Snackbar.LENGTH_LONG
            ).apply {
                if (result.isSuccess && ProxyManager.isRunning) setAction(R.string.reconnect_now) { ProxyManager.restart(this@DiagnosticsActivity) }
            }.show()
        }
    }
}
