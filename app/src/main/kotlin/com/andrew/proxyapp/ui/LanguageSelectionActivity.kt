package com.andrew.proxyapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.andrew.proxyapp.MainActivity
import com.andrew.proxyapp.R
import com.andrew.proxyapp.data.ConfigStore

/** First-run language choice. The screen intentionally stays in English. */
class LanguageSelectionActivity : AppCompatActivity() {
    private val store by lazy { ConfigStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_selection)
        configureSystemBars()

        val languageTags = resources.getStringArray(R.array.supported_language_tags)
        val options = listOf(
            R.id.optionEnglish to "English",
            R.id.optionChinese to "Simplified Chinese",
            R.id.optionRussian to "Russian",
            R.id.optionPersian to "Persian",
            R.id.optionAzerbaijani to "Azerbaijani",
            R.id.optionArabic to "Arabic"
        )
        val radioGroup = findViewById<android.widget.RadioGroup>(R.id.languageOptions)
        val suggestedTag = currentLanguageTag(languageTags)
        val suggestedIndex = languageTags.indexOf(suggestedTag).coerceAtLeast(0)
        radioGroup.check(options[suggestedIndex].first)

        findViewById<android.view.View>(R.id.btnContinue).setOnClickListener {
            val selectedIndex = options.indexOfFirst { it.first == radioGroup.checkedRadioButtonId }
                .takeIf { it >= 0 } ?: 0
            val selectedTag = languageTags[selectedIndex]
            store.updateSettings(notifyRuntime = false) {
                it.language = selectedTag
                it.languageSelectionCompleted = true
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
    }

    private fun currentLanguageTag(tags: Array<String>): String {
        val systemLanguage = resources.configuration.locales[0]?.language.orEmpty()
        return tags.firstOrNull { it.substringBefore('-') == systemLanguage } ?: "en"
    }
}
