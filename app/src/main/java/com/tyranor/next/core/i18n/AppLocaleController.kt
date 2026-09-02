package com.tyranor.next.core.i18n

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
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
