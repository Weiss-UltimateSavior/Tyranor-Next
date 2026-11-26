package com.core.engine

import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.tvp.kirikiri2.KrDialogStyle

/** Presents JavaScript dialogs with the host palette and completes each WebView result once. */
open class EngineWebChromeClient(private val activity: Activity) : WebChromeClient() {
    private val pending = mutableMapOf<Dialog, () -> Unit>()

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
        show(url, message, result, acknowledgeOnly = true)

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
        show(url, message, result)

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean =
        show(url, message, result)

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean = show(url, message, result, initialText = defaultValue.orEmpty())

    private fun show(
        url: String,
        message: String,
        result: JsResult,
        acknowledgeOnly: Boolean = false,
        initialText: String? = null,
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            result.cancel()
            return true
        }
        val title = Uri.parse(url).host ?: EngineUiText.get(activity, R.string.engine_prompt)
        val ok = EngineUiText.get(activity, R.string.engine_ok)
        val labels = if (acknowledgeOnly) arrayOf(ok)
            else arrayOf(ok, EngineUiText.get(activity, R.string.engine_cancel))
        val input = initialText?.let { KrDialogStyle.createInput(activity, it, InputType.TYPE_CLASS_TEXT) }
        val dialog = KrDialogStyle.createDialog(activity, title, message, input, labels, true, false) { selected, index ->
            if (pending.remove(selected) == null) return@createDialog
            if (index != 0) result.cancel()
            else if (result is JsPromptResult) result.confirm(input?.text?.toString().orEmpty())
            else result.confirm()
        }
        if (input != null) KrDialogStyle.focusInput(dialog, input)
        pending[dialog] = result::cancel
        // Cancel and host teardown can both dismiss the same window.
        dialog.setOnDismissListener { pending.remove(dialog)?.invoke() }
        dialog.setOnCancelListener { pending.remove(dialog)?.invoke() }
        return try {
            dialog.show()
            true
        } catch (e: RuntimeException) {
            Log.w("EngineWebChromeClient", "Cannot display JavaScript dialog", e)
            pending.remove(dialog)?.invoke()
            true
        }
    }

    fun dismissDialogs() {
        for ((dialog, cancel) in pending.toMap()) {
            pending.remove(dialog)
            cancel()
            dialog.dismiss()
        }
    }
}
