package com.andrew.proxyapp.manager

import com.andrew.proxyapp.data.RuntimeErrorCategory
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class ErrorTranslatorTest {
    @Test fun translatesNetworkFailureForChinese() {
        val message = ErrorTranslator.userMessage(RuntimeErrorCategory.NETWORK, "network timeout", Locale.SIMPLIFIED_CHINESE)
        assertTrue(message.contains("网络") || message.contains("超时"))
    }

    @Test fun localizesErrorsForAllSupportedLanguages() {
        val english = ErrorTranslator.userMessage(RuntimeErrorCategory.NETWORK, "network timeout", Locale.ENGLISH)
        listOf("zh", "ru", "fa", "az", "ar").forEach { language ->
            val translated = ErrorTranslator.userMessage(
                RuntimeErrorCategory.NETWORK,
                "network timeout",
                Locale.forLanguageTag(language)
            )
            assertTrue(translated.isNotBlank())
            assertNotEquals(english, translated)
        }
    }
}
