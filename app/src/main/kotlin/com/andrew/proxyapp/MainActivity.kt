package com.andrew.proxyapp

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.RouteMode
import com.andrew.proxyapp.databinding.ActivityMainBinding
import com.andrew.proxyapp.manager.ProxyManager
import com.andrew.proxyapp.manager.RuntimeController
import com.andrew.proxyapp.service.TunnelService
import com.andrew.proxyapp.ui.ConfigListActivity
import com.andrew.proxyapp.ui.ConnectionsActivity
import com.andrew.proxyapp.ui.DnsActivity
import com.andrew.proxyapp.ui.DiagnosticsActivity
import com.andrew.proxyapp.ui.EditConfigActivity
import com.andrew.proxyapp.ui.GeneralSettingsActivity
import com.andrew.proxyapp.ui.PerAppActivity
import com.andrew.proxyapp.ui.SettingsActivity
import com.andrew.proxyapp.ui.configureSystemBars
import com.andrew.proxyapp.ui.localizedLabel
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { ConfigStore.get(this) }
    private var lastTunnelState: TunnelService.TunnelState? = null

    private val configLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateActiveConfigDisplay()
    }
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) ProxyManager.onPermissionGranted(this)
        else Toast.makeText(this, R.string.vpn_permission_required, Toast.LENGTH_SHORT).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setupUi()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        updateActiveConfigDisplay()
        val mode = store.getSettings().routeMode
        updateModeIndicator(mode, animate = false)
    }

    private fun setupUi() = with(binding) {
        btnToggleProxy.setOnClickListener {
            if (store.getActiveConfig() == null) {
                Toast.makeText(this@MainActivity, R.string.no_profile, Toast.LENGTH_SHORT).show()
                configLauncher.launch(Intent(this@MainActivity, EditConfigActivity::class.java))
            } else if (ProxyManager.isRunning) {
                ProxyManager.stop(this@MainActivity)
            } else {
                val permission = ProxyManager.permissionIntent(this@MainActivity)
                if (permission == null) ProxyManager.start(this@MainActivity) else vpnPermissionLauncher.launch(permission)
            }
        }
        bottomProfile.setOnClickListener { openProfiles() }
        btnConfigManager.setOnClickListener { openProfiles() }
        btnDns.setOnClickListener { startActivity(Intent(this@MainActivity, DnsActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        btnSettingsTop.setOnClickListener { startActivity(Intent(this@MainActivity, GeneralSettingsActivity::class.java)) }
        btnConnections.setOnClickListener { startActivity(Intent(this@MainActivity, ConnectionsActivity::class.java)) }
        btnPerApp.setOnClickListener { startActivity(Intent(this@MainActivity, PerAppActivity::class.java)) }
        btnDiagnostics.setOnClickListener { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) }
        btnRuleMode.setOnClickListener { selectMode(RouteMode.RULE) }
        btnGlobalMode.setOnClickListener { selectMode(RouteMode.GLOBAL) }
        btnDns.contentDescription = getString(R.string.dns_and_routing)
    }

    private fun selectMode(mode: RouteMode) {
        updateModeIndicator(mode, animate = true)
        if (store.getSettings().routeMode != mode) {
            store.updateSettings(notifyRuntime = false) { it.routeMode = mode }
            if (ProxyManager.isRunning) RuntimeController.setMode(mode)
        }
    }

    private fun updateModeIndicator(mode: RouteMode, animate: Boolean) {
        binding.modeGroup.post {
            val groupWidth = binding.modeGroup.width - binding.modeGroup.paddingLeft - binding.modeGroup.paddingRight
            val indicatorWidth = groupWidth / 2
            val params = binding.modeIndicator.layoutParams
            if (params.width != indicatorWidth) {
                params.width = indicatorWidth
                binding.modeIndicator.layoutParams = params
            }
            val target = if (mode == RouteMode.GLOBAL) indicatorWidth.toFloat() else 0f
            if (animate) binding.modeIndicator.animate().translationX(target).setDuration(180).start()
            else binding.modeIndicator.translationX = target
        }
    }

    private fun openProfiles() = configLauncher.launch(Intent(this, ConfigListActivity::class.java))

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ProxyManager.state.collect(::renderTunnelState) }
                launch { RuntimeController.snapshot.collect { snapshot ->
                    binding.tvConnections.text = NumberFormat.getIntegerInstance().format(snapshot.connectionsOut)
                    binding.tvTraffic.text = getString(
                        R.string.upload_download,
                        formatBytes(snapshot.uploadTotal),
                        formatBytes(snapshot.downloadTotal)
                    )
                    binding.tvSpeed.text = getString(
                        R.string.upload_download,
                        "${formatBytes(snapshot.uploadSpeed)}/s",
                        "${formatBytes(snapshot.downloadSpeed)}/s"
                    )
                    binding.tvDuration.text = formatDuration(snapshot.startedAt)
                } }
                launch { RuntimeController.selectedConfigId.collect { id ->
                    if (id.isNotBlank() && store.getSettings().activeConfigId != id) {
                        store.setActiveConfig(id)
                        updateActiveConfigDisplay()
                    }
                } }
            }
        }
    }

    private fun renderTunnelState(state: TunnelService.TunnelState) = with(binding) {
        val (color, enabled) = when (state) {
            TunnelService.TunnelState.STOPPED -> R.color.connection_disconnected to true
            TunnelService.TunnelState.CONNECTING -> R.color.colorPrimary to false
            TunnelService.TunnelState.RUNNING -> R.color.connection_connected to true
            TunnelService.TunnelState.ERROR -> R.color.connection_disconnected to true
        }
        val resolved = ContextCompat.getColor(this@MainActivity, color)
        val tint = ColorStateList.valueOf(resolved)
        connectionButtonContainer.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, R.color.connection_button_surface))
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), resolved)
        }
        btnToggleProxy.imageTintList = tint
        connectionHalo.backgroundTintList = tint
        btnToggleProxy.isEnabled = enabled
        btnToggleProxy.contentDescription = getString(when (state) {
            TunnelService.TunnelState.RUNNING -> R.string.btn_stop
            TunnelService.TunnelState.CONNECTING -> R.string.status_connecting
            else -> R.string.btn_start
        })
        connectionControl.contentDescription = getString(when (state) {
            TunnelService.TunnelState.STOPPED -> R.string.status_stopped
            TunnelService.TunnelState.CONNECTING -> R.string.status_connecting
            TunnelService.TunnelState.RUNNING -> R.string.status_running
            TunnelService.TunnelState.ERROR -> R.string.status_error
        })

        if (lastTunnelState != state) {
            connectionControl.animate().cancel()
            connectionControl.scaleX = 0.94f
            connectionControl.scaleY = 0.94f
            connectionControl.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            connectionHalo.animate()
                .alpha(if (state == TunnelService.TunnelState.CONNECTING) 0.18f else 0.10f)
                .scaleX(if (state == TunnelService.TunnelState.CONNECTING) 1.04f else 1f)
                .scaleY(if (state == TunnelService.TunnelState.CONNECTING) 1.04f else 1f)
                .setDuration(220)
                .start()

            when {
                state == TunnelService.TunnelState.RUNNING && lastTunnelState == TunnelService.TunnelState.CONNECTING ->
                    showConnectionHint(R.string.connection_success)
                state == TunnelService.TunnelState.ERROR && lastTunnelState != null ->
                    showConnectionHint(R.string.connection_failed_hint)
            }
        }
        lastTunnelState = state
    }

    private fun showConnectionHint(message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.connectionControl)
            .show()
    }

    private fun updateActiveConfigDisplay() {
        val config = store.getActiveConfig()
        binding.tvActiveConfig.contentDescription = getString(R.string.current_profile)
        binding.tvActiveConfig.text = config?.name?.ifBlank { config.protocol.localizedLabel(this) } ?: getString(R.string.no_profile)
        binding.tvServerInfo.text = config?.let { "${it.protocol.localizedLabel(this)} · ${it.server}:${it.port}" } ?: getString(R.string.select_profile)
    }

    private fun formatDuration(startedAt: Long): String {
        if (startedAt <= 0) return "00:00:00"
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, elapsed % 3600 / 60, elapsed % 60)
    }

    private fun formatBytes(value: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var number = value.coerceAtLeast(0).toDouble()
        var index = 0
        while (number >= 1024 && index < units.lastIndex) { number /= 1024; index++ }
        return if (index == 0) "${number.toLong()} ${units[index]}" else String.format(Locale.US, "%.1f %s", number, units[index])
    }
}
