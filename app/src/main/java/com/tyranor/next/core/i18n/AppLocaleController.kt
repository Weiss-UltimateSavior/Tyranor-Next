package com.tyranor.next.core.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.tyranor.next.core.settings.AppSettingsStore
import java.util.Locale

/** App 内语言控制器：默认中文，可切换日文/英文或跟随系统。 */
object AppLocaleController {

    fun wrap(context: Context): Context =
        wrap(context, AppSettingsStore.getLanguage(context))

    fun wrap(context: Context, language: String): Context {
        val locale = localeOf(language) ?: return context
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val localizedContext = context.createConfigurationContext(config)
        return object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = localizedContext.assets
            override fun getResources(): Resources = localizedContext.resources
        }
    }

    fun localeOf(language: String): Locale? = when (language) {
        AppSettingsStore.LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
        AppSettingsStore.LANGUAGE_JA -> Locale.JAPANESE
        AppSettingsStore.LANGUAGE_EN -> Locale.ENGLISH
        else -> null
    }

    tailrec fun findActivity(context: Context?): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}

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
