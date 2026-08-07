package com.andrew.proxyapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.andrew.proxyapp.MyApplication
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.AppUpdateInfo
import com.andrew.proxyapp.data.ConfigStore
import com.andrew.proxyapp.data.ThemeMode
import com.andrew.proxyapp.manager.AppUpdateState
import com.andrew.proxyapp.manager.ReleaseCheckResult
import com.andrew.proxyapp.manager.ReleaseUpdateChecker
import com.andrew.proxyapp.manager.RuntimeController
import com.andrew.proxyapp.manager.BatteryOptimization
import kotlinx.coroutines.launch

class GeneralSettingsActivity : AppCompatActivity() {
    private val store by lazy { ConfigStore.get(this) }
    private val appUpdateManager by lazy { (application as MyApplication).appUpdateManager }
    private lateinit var updateRow: View
    private lateinit var updateLabel: TextView
    private lateinit var updateBadge: View
    private var manualCheckInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_settings)
        configureSystemBars()
        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        updateRow = findViewById(R.id.btnCheckUpdate)
        updateLabel = findViewById(R.id.tvCheckUpdate)
        updateBadge = findViewById(R.id.updateBadge)
        updateRow.setOnClickListener { handleUpdateClick() }
        observeUpdateState()
        val settings = store.getSettings()
        val themes = listOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        val languages = listOf(
            getString(R.string.language_chinese),
            getString(R.string.language_english),
            getString(R.string.language_russian),
            getString(R.string.language_persian),
            getString(R.string.language_azerbaijani),
            getString(R.string.language_arabic)
        )
        val languageTags = listOf("zh-CN", "en", "ru", "fa", "az", "ar")
        val currentLanguageIndex = languageTags.indexOf(settings.language).takeIf { it >= 0 } ?: 0
        val themeValue = findViewById<TextView>(R.id.tvThemeValue)
        val languageValue = findViewById<TextView>(R.id.tvLanguageValue)
        val testUrlValue = findViewById<TextView>(R.id.tvTestUrlValue)
        findViewById<View>(R.id.rowTheme).contentDescription = getString(R.string.appearance)
        themeValue.text = themes[themeIndex(settings.themeMode)]
        languageValue.text = languages[currentLanguageIndex]
        testUrlValue.text = settings.testUrl

        findViewById<View>(R.id.rowTheme).setOnClickListener {
            showPulseChoiceSheet(getString(R.string.theme), themes, themeIndex(settings.themeMode)) { which ->
                val selectedTheme = when (which) {
                    1 -> ThemeMode.LIGHT
                    2 -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
                settings.themeMode = selectedTheme
                store.updateSettings(notifyRuntime = false) { it.themeMode = selectedTheme }
                AppCompatDelegate.setDefaultNightMode(
                    when (settings.themeMode) {
                        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
        }
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            showPulseChoiceSheet(getString(R.string.language), languages, currentLanguageIndex) { which ->
                val tag = languageTags.getOrElse(which) { "zh-CN" }
                settings.language = tag
                store.updateSettings(notifyRuntime = false) { it.language = tag }
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
        findViewById<View>(R.id.rowTestUrl).setOnClickListener {
            showTestUrlDialog(settings, testUrlValue)
        }
        findViewById<android.view.View>(R.id.btnBattery).setOnClickListener { showBatteryGuidance() }
        findViewById<android.view.View>(R.id.btnQr).setOnClickListener { showQrTextDialog() }
        findViewById<android.view.View>(R.id.btnLeak).setOnClickListener { showInfo(getString(R.string.proxy_leak_check), getString(R.string.proxy_leak_check_hint)) }
        findViewById<android.view.View>(R.id.btnDnsCheck).setOnClickListener { showInfo(getString(R.string.dns_check), getString(R.string.dns_check_hint)) }
        findViewById<android.view.View>(R.id.btnLatency).setOnClickListener { RuntimeController.testAllNodes(); Snackbar.make(findViewById(R.id.btnLatency), R.string.latency_check, Snackbar.LENGTH_SHORT).show() }
        findViewById<android.view.View>(R.id.btnAbout).setOnClickListener { showInfo(getString(R.string.about), getString(R.string.about_message)) }
    }

    private fun observeUpdateState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appUpdateManager.state.collect(::renderUpdateState)
            }
        }
    }

    private fun renderUpdateState(state: AppUpdateState) {
        val available = when (state) {
            is AppUpdateState.Available -> state.update
            is AppUpdateState.Checking -> state.cachedUpdate
            else -> null
        }
        updateBadge.visibility = if (available == null) View.GONE else View.VISIBLE
        updateRow.contentDescription = if (available == null) {
            getString(R.string.check_for_updates)
        } else {
            getString(R.string.update_available_content_description, available.version)
        }
        if (!manualCheckInProgress) updateLabel.setText(R.string.check_for_updates)
    }

    private fun handleUpdateClick() {
        appUpdateManager.availableUpdateOrNull()?.let {
            showUpdateDialog(it)
            return
        }
        if (manualCheckInProgress) return

        manualCheckInProgress = true
        updateRow.isEnabled = false
        updateLabel.setText(R.string.checking_for_updates)
        lifecycleScope.launch {
            val result = runCatching { appUpdateManager.checkNow().await() }
                .getOrElse { ReleaseCheckResult.Failed(it) }
            manualCheckInProgress = false
            updateRow.isEnabled = true
            renderUpdateState(appUpdateManager.state.value)
            when (result) {
                is ReleaseCheckResult.Available -> showUpdateDialog(result.update)
                ReleaseCheckResult.UpToDate ->
                    Snackbar.make(updateRow, R.string.up_to_date, Snackbar.LENGTH_SHORT).show()
                is ReleaseCheckResult.Failed ->
                    Snackbar.make(updateRow, R.string.update_check_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateDialog(update: AppUpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_download_prompt, update.version))
            .setPositiveButton(R.string.download_update) { _, _ -> openReleasePage(update.releaseUrl) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openReleasePage(url: String) {
        if (!ReleaseUpdateChecker.isValidReleaseUrl(url)) {
            Snackbar.make(updateRow, R.string.release_page_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(updateRow, R.string.release_page_unavailable, Snackbar.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Snackbar.make(updateRow, R.string.release_page_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showBatteryGuidance() {
        val guidance = BatteryOptimization.guidance(this)
        val status = if (guidance.isIgnoringOptimizations) {
            getString(R.string.battery_unrestricted)
        } else {
            getString(R.string.battery_optimized)
        }
        val message = buildString {
            append(status).append("\n\n")
            guidance.steps.forEach { append("• ").append(it).append('\n') }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_guidance_title)
            .setMessage(message.trim())
            .setPositiveButton(R.string.battery_open_settings) { _, _ -> BatteryOptimization.openSystemSettings(this) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQrTextDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = content.findViewById<TextInputEditText>(R.id.dialogInput).apply {
            gravity = Gravity.TOP or Gravity.START
            hint = getString(R.string.text_to_qr)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
        }
        AlertDialog.Builder(this).setTitle(R.string.text_to_qr).setView(content).setPositiveButton(R.string.save) { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", input.text))
            Snackbar.make(findViewById(R.id.btnQr), R.string.qr_copied, Snackbar.LENGTH_SHORT).show()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun themeIndex(themeMode: ThemeMode) = when (themeMode) {
        ThemeMode.LIGHT -> 1
        ThemeMode.DARK -> 2
        ThemeMode.SYSTEM -> 0
    }

    private fun showTestUrlDialog(settings: com.andrew.proxyapp.data.AppSettings, value: TextView) {
        val content = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = content.findViewById<TextInputEditText>(R.id.dialogInput).apply {
            setText(settings.testUrl)
            setSelection(text?.length ?: 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.test_url)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.testUrl = input.text?.toString()?.trim().orEmpty()
                store.updateSettings(notifyRuntime = false) { it.testUrl = settings.testUrl }
                value.text = settings.testUrl
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInfo(title: String, message: String) = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.cancel, null).show()
}
