package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.settings.AppSettingsStore

/** Provides the app-selected locale to Compose content and resource lookups. */
@Composable
fun ProvideAppLocale(content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    AppSettingsStore.initLanguage(baseContext)
    val language = AppSettingsStore.languageState.value
    val localizedContext = remember(baseContext, language) {
        AppLocaleController.wrap(baseContext, language)
    }
    val localizedConfiguration = remember(localizedContext, language) {
        Configuration(localizedContext.resources.configuration)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        // stringResource() reads LocalResources rather than LocalContext. Providing
        // the localized Resources here keeps every Compose screen in sync with the
        // app-selected language, including sheets and dialogs opened later.
        LocalResources provides localizedContext.resources,
        content = content,
    )
}
