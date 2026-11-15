package com.tyranor.next.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val language by AppSettingsStore.languageState.collectAsState()
    // 以平台当前的窗口配置为底（MainActivity 自行处理尺寸 / 方向 / 密度变化、不再重建，
    // 小窗或折叠屏尺寸变化时 isTabletScreen() 等读取的 LocalConfiguration 必须随之更新），只叠加应用所选语言；
    // 本地化 Context（及其 Resources）同样要随窗口配置重新生成，避免密度变化后资源仍用旧值。
    val baseConfiguration = LocalConfiguration.current
    val localizedContext = remember(baseContext, language, baseConfiguration) {
        AppLocaleController.wrap(baseContext, language)
    }
    val localizedConfiguration = remember(baseConfiguration, localizedContext, language) {
        Configuration(baseConfiguration).apply {
            setLocales(localizedContext.resources.configuration.locales)
        }
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
