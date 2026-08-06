package com.andrew.proxyapp.ui

import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andrew.proxyapp.R

/** Applies Android 15 edge-to-edge insets without letting content sit under system bars. */
@Suppress("DEPRECATION")
fun AppCompatActivity.configureSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    window.statusBarColor = ContextCompat.getColor(this, R.color.background)
    window.navigationBarColor = ContextCompat.getColor(this, R.color.surface)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isDark
        isAppearanceLightNavigationBars = !isDark
    }

    val content = findViewById<View>(android.R.id.content) ?: return
    val initialLeft = content.paddingLeft
    val initialTop = content.paddingTop
    val initialRight = content.paddingRight
    val initialBottom = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(content)
}
