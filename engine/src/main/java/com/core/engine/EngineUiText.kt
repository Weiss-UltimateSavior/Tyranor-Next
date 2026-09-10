package com.core.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import java.util.Locale

/** Resolves launcher-facing engine text without changing the native engine's compatibility locale. */
object EngineUiText {
    @JvmStatic
    fun get(context: Context, resourceId: Int): String {
        if (context == null) return ""
        var languageTag: String? = null
        return try {
            if (context is Activity) {
                val intent = context.intent
                if (intent != null) languageTag = intent.getStringExtra(LaunchContract.UI_LANGUAGE_TAG)
            }
            if (languageTag == null || languageTag.trim().isEmpty()) {
                return context.getString(resourceId)
            }
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(languageTag))
            context.createConfigurationContext(configuration).getString(resourceId)
        } catch (_: Throwable) {
            context.getString(resourceId)
        }
    }

    /**
     * The KRKR shell must run with its bundled Chinese locale, but common dialog chrome belongs
     * to the launcher UI. Translate only exact system labels and leave game-authored text intact.
     */
    @JvmStatic
    fun localizeCommonDialogText(context: Context, text: String?): String {
        if (text == null) return ""
        return when (text.trim()) {
            "确定", "确认", "好" -> get(context, R.string.engine_ok)
            "取消" -> get(context, R.string.engine_cancel)
            "是" -> get(context, R.string.engine_yes)
            "否" -> get(context, R.string.engine_no)
            "提示" -> get(context, R.string.engine_prompt)
            "错误" -> get(context, R.string.engine_error)
            else -> text
        }
    }
}
