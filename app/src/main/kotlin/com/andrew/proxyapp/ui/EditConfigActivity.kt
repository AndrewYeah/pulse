package com.andrew.proxyapp.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.ProtocolType
import com.andrew.proxyapp.data.ProxyConfig
import com.andrew.proxyapp.data.TransportType
import com.andrew.proxyapp.data.UriParser
import com.andrew.proxyapp.data.NodeValidator
import java.util.Locale

class EditConfigActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private var editingId: String? = null
    private var editingConfig: ProxyConfig? = null
    private var parsedConfig: ProxyConfig? = null

    private lateinit var etUri: TextInputEditText
    private lateinit var etName: TextInputEditText
    private lateinit var spinnerProtocol: android.widget.AutoCompleteTextView
    private lateinit var etServer: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var etUuid: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var layoutUsername: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var etSni: TextInputEditText
    private lateinit var etAlpn: TextInputEditText
    private lateinit var cbInsecure: CompoundButton
    private lateinit var cbTlsEnabled: CompoundButton
    private lateinit var spinnerTransport: android.widget.AutoCompleteTextView
    private lateinit var etPath: TextInputEditText
    private lateinit var etWsHost: TextInputEditText
    private lateinit var etSsMethod: TextInputEditText
    private lateinit var etFlow: TextInputEditText
    private lateinit var etFingerprint: TextInputEditText
    private lateinit var etUpMbps: TextInputEditText
    private lateinit var etDownMbps: TextInputEditText
    private lateinit var etCongestion: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_config)
        configureSystemBars()

        store = ConfigStore.get(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()

        // 下拉菜单
        spinnerProtocol.setAdapter(ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            ProtocolType.entries.map { it.localizedLabel(this) }
        ))
        spinnerProtocol.setOnItemClickListener { _, _, _, _ ->
            val selected = ProtocolType.entries.find { it.localizedLabel(this) == spinnerProtocol.text.toString() }
            if (selected in listOf(ProtocolType.SOCKS5, ProtocolType.HTTP_PROXY, ProtocolType.SHADOWSOCKS, ProtocolType.SHADOWSOCKSR)) {
                cbTlsEnabled.isChecked = false
            }
            updateFieldVisibility()
        }

        spinnerTransport.setAdapter(ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            TransportType.entries.map { it.localizedLabel(this) }
        ))
        spinnerTransport.setOnItemClickListener { _, _, _, _ -> updateFieldVisibility() }
        cbTlsEnabled.setOnCheckedChangeListener { _, _ -> updateFieldVisibility() }

        // 解析按钮
        findViewById<MaterialButton>(R.id.btnParse).setOnClickListener {
            val uri = etUri.text.toString().trim()
            if (uri.isBlank()) {
            Toast.makeText(this, R.string.enter_config_link, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val config = UriParser.parse(uri)
            if (config == null) {
                Toast.makeText(this, R.string.parse_failed, Toast.LENGTH_SHORT).show()
            } else {
                parsedConfig = config
                fillForm(config)
                Toast.makeText(this, R.string.parse_success, Toast.LENGTH_SHORT).show()
            }
        }

        // 保存
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        // 编辑已有配置
        editingId = intent.getStringExtra("config_id")
        if (editingId != null) {
            toolbar.setTitle(R.string.edit_profile_title)
            store.getConfig(editingId!!)?.let { editingConfig = it; fillForm(it) }
        } else {
            toolbar.setTitle(R.string.new_profile_title)
            spinnerProtocol.setText(ProtocolType.HYSTERIA2.localizedLabel(this), false)
            spinnerTransport.setText(TransportType.NONE.localizedLabel(this), false)
            cbTlsEnabled.isChecked = true
        }
        updateFieldVisibility()
    }

    private fun initViews() {
        etUri = findViewById(R.id.etUri)
        etName = findViewById(R.id.etName)
        spinnerProtocol = findViewById(R.id.spinnerProtocol)
        etServer = findViewById(R.id.etServer)
        etPort = findViewById(R.id.etPort)
        etUuid = findViewById(R.id.etUuid)
        etPassword = findViewById(R.id.etPassword)
        val passwordContainer = findViewById<TextInputLayout>(R.id.layoutPassword)
        layoutUsername = TextInputLayout(this).apply {
            hint = getString(R.string.username)
            isHintEnabled = true
            layoutParams = passwordContainer.layoutParams
        }
        etUsername = TextInputEditText(this)
        layoutUsername.addView(etUsername)
        (passwordContainer.parent as? android.view.ViewGroup)?.let { parent ->
            parent.addView(layoutUsername, parent.indexOfChild(passwordContainer))
        }
        etSni = findViewById(R.id.etSni)
        etAlpn = findViewById(R.id.etAlpn)
        cbInsecure = findViewById(R.id.cbInsecure)
        cbTlsEnabled = findViewById(R.id.cbTlsEnabled)
        spinnerTransport = findViewById(R.id.spinnerTransport)
        etPath = findViewById(R.id.etPath)
        etWsHost = findViewById(R.id.etWsHost)
        etSsMethod = findViewById(R.id.etSsMethod)
        etFlow = findViewById(R.id.etFlow)
        etFingerprint = findViewById(R.id.etFingerprint)
        etUpMbps = findViewById(R.id.etUpMbps)
        etDownMbps = findViewById(R.id.etDownMbps)
        etCongestion = findViewById(R.id.etCongestion)
    }

    private fun fillForm(config: ProxyConfig) {
        etUri.setText(config.rawUri)
        etName.setText(config.name)
        spinnerProtocol.setText(config.protocol.localizedLabel(this), false)
        etServer.setText(config.server)
        etPort.setText(String.format(Locale.ROOT, "%d", config.port))
        etUuid.setText(config.uuid)
        etUsername.setText(config.username)
        etPassword.setText(config.password)
        etSni.setText(config.sni)
        etAlpn.setText(config.alpn)
        cbInsecure.isChecked = config.insecure
        cbTlsEnabled.isChecked = config.tlsEnabled
        spinnerTransport.setText(config.transportType.localizedLabel(this), false)
        etPath.setText(config.wsPath)
        etWsHost.setText(config.wsHost)
        etSsMethod.setText(config.ssMethod)
        etFlow.setText(config.flow)
        etFingerprint.setText(config.fingerprint)
        etUpMbps.setText(config.upMbps.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%d", it) }.orEmpty())
        etDownMbps.setText(config.downMbps.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%d", it) }.orEmpty())
        etCongestion.setText(config.congestionControl)
        updateFieldVisibility()
    }

    private fun collectConfig(): ProxyConfig? {
        val server = etServer.text.toString().trim()
        if (server.isBlank()) {
            Toast.makeText(this, R.string.enter_server, Toast.LENGTH_SHORT).show()
            return null
        }

        val port = etPort.text.toString().trim().toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return null
        }

        val protocolDisplay = spinnerProtocol.text.toString()
        val protocol = ProtocolType.entries.find { it.localizedLabel(this) == protocolDisplay }
            ?: ProtocolType.HYSTERIA2

        val transportDisplay = spinnerTransport.text.toString()
        val transport = TransportType.entries.find { it.localizedLabel(this) == transportDisplay }
            ?: TransportType.NONE

        val uuid = etUuid.text.toString().trim()
        val password = etPassword.text.toString().trim()
        if (protocol in listOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TUIC) && uuid.isBlank()) {
            Toast.makeText(this, R.string.protocol_requires_uuid, Toast.LENGTH_SHORT).show(); return null
        }
        if (protocol in listOf(ProtocolType.HYSTERIA2, ProtocolType.TUIC, ProtocolType.ANYTLS, ProtocolType.SHADOWSOCKS, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKSR) && password.isBlank()) {
            Toast.makeText(this, R.string.protocol_requires_password, Toast.LENGTH_SHORT).show(); return null
        }
        if (protocol == ProtocolType.SHADOWSOCKS && etSsMethod.text.isNullOrBlank()) {
            Toast.makeText(this, R.string.enter_ss_method, Toast.LENGTH_SHORT).show(); return null
        }
        return (editingConfig ?: parsedConfig ?: ProxyConfig()).apply {
            name = etName.text.toString().trim().ifBlank { "${protocol.localizedLabel(this@EditConfigActivity)}-$server" }
            this.protocol = protocol; this.server = server; this.port = port
            this.uuid = uuid; this.password = password
            username = etUsername.text.toString().trim()
            sni = etSni.text.toString().trim(); alpn = etAlpn.text.toString().trim()
            insecure = cbInsecure.isChecked; tlsEnabled = cbTlsEnabled.isChecked
            fingerprint = etFingerprint.text.toString().trim().ifBlank { "chrome" }
            transportType = transport; wsPath = etPath.text.toString().trim()
            grpcServiceName = etPath.text.toString().trim(); wsHost = etWsHost.text.toString().trim()
            ssMethod = etSsMethod.text.toString().trim(); flow = etFlow.text.toString().trim()
            upMbps = etUpMbps.text.toString().toIntOrNull() ?: 0
            downMbps = etDownMbps.text.toString().toIntOrNull() ?: 0
            congestionControl = etCongestion.text.toString().trim().ifBlank { "bbr" }
            rawUri = etUri.text.toString().trim()
        }
    }

    private fun save() {
        val config = collectConfig() ?: return
        val issue = NodeValidator.validate(config).firstOrNull { it.fatal }
        if (issue != null) {
            Toast.makeText(this, issue.message, Toast.LENGTH_LONG).show()
            return
        }
        store.saveConfig(config)
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun updateFieldVisibility() {
        val protocol = ProtocolType.entries.find { it.localizedLabel(this) == spinnerProtocol.text.toString() } ?: ProtocolType.HYSTERIA2
        val transport = TransportType.entries.find { it.localizedLabel(this) == spinnerTransport.text.toString() } ?: TransportType.NONE
        findViewById<View>(R.id.layoutUuid).visibility = if (protocol in listOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TUIC)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutPassword).visibility = if (protocol in listOf(ProtocolType.HYSTERIA2, ProtocolType.TUIC, ProtocolType.ANYTLS, ProtocolType.SHADOWSOCKS, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKSR, ProtocolType.SOCKS5, ProtocolType.HTTP_PROXY)) View.VISIBLE else View.GONE
        layoutUsername.visibility = if (protocol in listOf(ProtocolType.SOCKS5, ProtocolType.HTTP_PROXY)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutSsMethod).visibility = if (protocol == ProtocolType.SHADOWSOCKS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutFlow).visibility = if (protocol == ProtocolType.VLESS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutFingerprint).visibility = if (protocol == ProtocolType.VLESS || protocol == ProtocolType.VMESS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hysteriaFields).visibility = if (protocol == ProtocolType.HYSTERIA2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tuicField).visibility = if (protocol == ProtocolType.TUIC) View.VISIBLE else View.GONE
        findViewById<View>(R.id.advancedCard).visibility = if (protocol == ProtocolType.HYSTERIA2 || protocol == ProtocolType.TUIC) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tlsFields).visibility = if (cbTlsEnabled.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.transportFields).visibility = if (transport == TransportType.NONE) View.GONE else View.VISIBLE
    }
}
