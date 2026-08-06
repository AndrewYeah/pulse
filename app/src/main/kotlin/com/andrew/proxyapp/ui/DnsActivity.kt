package com.andrew.proxyapp.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import java.net.URI

class DnsActivity : AppCompatActivity() {
    private val store by lazy { ConfigStore.get(this) }
    private val strategies = listOf("ipv4_only", "prefer_ipv4", "ipv4_and_ipv6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns)
        configureSystemBars()
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val settings = store.getSettings()
        val remoteValue = findViewById<TextView>(R.id.tvRemoteDnsValue)
        val localValue = findViewById<TextView>(R.id.tvLocalDnsValue)
        val strategyValue = findViewById<TextView>(R.id.tvDnsStrategyValue)
        remoteValue.text = settings.remoteDns
        localValue.text = settings.localDns
        strategyValue.text = settings.dnsStrategy

        findViewById<View>(R.id.rowRemoteDns).setOnClickListener {
            showDnsEditor(R.string.proxy_dns, settings.remoteDns) { value ->
                if (!isValidDns(value)) return@showDnsEditor false
                settings.remoteDns = value
                store.updateSettings { it.remoteDns = value }
                remoteValue.text = value
                true
            }
        }
        findViewById<View>(R.id.rowLocalDns).setOnClickListener {
            showDnsEditor(R.string.direct_dns, settings.localDns) { value ->
                if (!isValidDns(value)) return@showDnsEditor false
                settings.localDns = value
                store.updateSettings { it.localDns = value }
                localValue.text = value
                true
            }
        }
        findViewById<View>(R.id.rowDnsStrategy).setOnClickListener {
            val selected = strategies.indexOf(settings.dnsStrategy).coerceAtLeast(0)
            showPulseChoiceSheet(getString(R.string.dns_strategy), strategies, selected) { which ->
                settings.dnsStrategy = strategies[which]
                store.updateSettings { it.dnsStrategy = settings.dnsStrategy }
                strategyValue.text = settings.dnsStrategy
            }
        }
    }

    private fun showDnsEditor(title: Int, initial: String, onSave: (String) -> Boolean) {
        val content = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val inputLayout = content.findViewById<TextInputLayout>(R.id.dialogInputLayout)
        val input = content.findViewById<TextInputEditText>(R.id.dialogInput).apply {
            setText(initial)
            setSelection(text?.length ?: 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                if (onSave(value)) {
                    dialog.dismiss()
                } else {
                    inputLayout.error = getString(R.string.invalid_dns_address)
                }
            }
        }
        dialog.show()
    }

    private fun isValidDns(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("https", "tls", "quic", "h3", "udp", "tcp") &&
            !uri.host.isNullOrBlank()
    }.getOrDefault(false)

}
