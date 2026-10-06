package com.tyranor.next.ui.settings

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.engine.external.RtpImportRejection
import com.tyranor.next.core.i18n.AppLocaleController

/**
 * RTP 导入拒绝原因 → 本地化文案（core → ui 错误协议，见 AGENT.md）。
 *
 * core 侧 [RtpImportRejection] 只带类型与数值，文案在这里组装；带数值的分支给出实际值与
 * 上限，用户据此判断是包损坏、格式不对，还是被安全上限拦下。
 */
internal fun RtpImportRejection.userMessage(context: Context): String {
    val localized = AppLocaleController.wrap(context)
    return when (this) {
        is RtpImportRejection.EntryCountExceeded ->
            localized.getString(R.string.engine_settings_rpgm_rtp_reject_entry_count, count, limit)

        is RtpImportRejection.EntrySizeExceeded ->
            localized.getString(
                R.string.engine_settings_rpgm_rtp_reject_entry_size,
                formatBytes(size),
                formatBytes(limit),
            )

        is RtpImportRejection.TotalSizeExceeded ->
            localized.getString(
                R.string.engine_settings_rpgm_rtp_reject_total_size,
                formatBytes(size),
                formatBytes(limit),
            )

        is RtpImportRejection.DirectoryCreateFailed ->
            localized.getString(R.string.engine_settings_rpgm_rtp_reject_mkdir, name)
    }
}

/** 字节数的人类可读形式（MiB/GiB 取整，避免长串数字）。 */
private fun formatBytes(bytes: Long): String {
    val mib = 1024.0 * 1024.0
    val gib = mib * 1024.0
    return when {
        bytes >= gib -> String.format(java.util.Locale.US, "%.1f GiB", bytes / gib)
        bytes >= mib -> String.format(java.util.Locale.US, "%.0f MiB", bytes / mib)
        else -> "$bytes B"
    }
}
