package com.tyranor.next.core.i18n

import com.tyranor.next.core.settings.AppSettingsStore
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleControllerTest {
    @Test fun selectedAppLanguageOverridesTheEngineOrSystemLocale() {
        val system = Locale.GERMAN
        assertEquals("zh-CN", AppLocaleController.engineUiLanguageTag(AppSettingsStore.LANGUAGE_ZH, system))
        assertEquals("en", AppLocaleController.engineUiLanguageTag(AppSettingsStore.LANGUAGE_EN, system))
        assertEquals("ja", AppLocaleController.engineUiLanguageTag(AppSettingsStore.LANGUAGE_JA, system))
        assertEquals("de", AppLocaleController.engineUiLanguageTag(AppSettingsStore.LANGUAGE_SYSTEM, system))
    }
}
