package com.andrew.proxyapp.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubPageThemeSmokeTest {
    @After
    fun restoreSystemTheme() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun generalSettingsLaunchesInLightAndDarkThemes() {
        launchInTheme<GeneralSettingsActivity>(AppCompatDelegate.MODE_NIGHT_NO)
        launchInTheme<GeneralSettingsActivity>(AppCompatDelegate.MODE_NIGHT_YES)
    }

    @Test
    fun configListLaunchesInLightAndDarkThemes() {
        launchInTheme<ConfigListActivity>(AppCompatDelegate.MODE_NIGHT_NO)
        launchInTheme<ConfigListActivity>(AppCompatDelegate.MODE_NIGHT_YES)
    }

    private inline fun <reified T : androidx.fragment.app.FragmentActivity> launchInTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
        ActivityScenario.launch(T::class.java).use { scenario ->
            scenario.onActivity { activity -> check(!activity.isFinishing) }
        }
    }
}
