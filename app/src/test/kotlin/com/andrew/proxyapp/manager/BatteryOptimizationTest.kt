package com.andrew.proxyapp.manager

import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationTest {
    @Test fun providesXiaomiBackgroundSteps() {
        val steps = BatteryOptimization.stepsForManufacturer("xiaomi")
        assertTrue(steps.any { it.contains("Autostart") })
        assertTrue(steps.any { it.contains("No restrictions") })
    }

    @Test fun providesGenericStepsForUnknownManufacturer() {
        assertTrue(BatteryOptimization.stepsForManufacturer("unknown").isNotEmpty())
    }
}
