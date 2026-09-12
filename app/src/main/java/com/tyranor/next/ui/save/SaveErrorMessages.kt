package com.tyranor.next.ui.save

import android.content.Context
import com.tyranor.next.R
import com.tyranor.next.core.game.save.GameSaveException
import com.tyranor.next.core.game.save.SaveErrorCode
import com.tyranor.next.core.i18n.AppLocaleController

/**
 * 存档操作异常 → 用户文案映射（P0-6）：core 只抛 [GameSaveException] 类型，
 * 本地化文案统一在 UI 层按 [SaveErrorCode] 组装。
 */
internal fun Throwable.toSaveErrorMessage(context: Context, fallback: String): String {
    if (this !is GameSaveException) return message ?: fallback
    val localized = AppLocaleController.wrap(context)
    return when (code) {
        SaveErrorCode.RESOLVE_SAVE_DIR_FAILED -> localized.getString(R.string.save_error_resolve_actual_dir)
        SaveErrorCode.NO_EXPORTABLE_FILES -> localized.getString(R.string.save_error_no_exportable_files)
        SaveErrorCode.CREATE_EXPORT_ZIP -> localized.getString(R.string.save_error_create_export_zip)
        SaveErrorCode.CREATE_SAVE_DIR -> localized.getString(R.string.save_error_create_save_dir)
        SaveErrorCode.SAVE_DIR_UNAVAILABLE -> localized.getString(R.string.save_error_save_dir_unavailable)
        SaveErrorCode.NO_FILES_IN_ZIP -> localized.getString(R.string.save_error_no_files_in_zip)
        SaveErrorCode.READ_IMPORT_ZIP -> localized.getString(R.string.save_error_read_import_zip)
        SaveErrorCode.DUPLICATE_ZIP_ENTRY ->
            localized.getString(R.string.save_error_duplicate_zip_entry, detail.orEmpty())
        SaveErrorCode.TOO_MANY_ZIP_FILES -> localized.getString(R.string.save_error_too_many_zip_files)
        SaveErrorCode.ILLEGAL_ZIP_PATH ->
            localized.getString(R.string.save_error_illegal_zip_path, detail.orEmpty())
        SaveErrorCode.CREATE_SAVE_DIR_NAMED ->
            localized.getString(R.string.save_error_create_save_dir_named, detail.orEmpty())
        SaveErrorCode.ZIP_TOO_LARGE -> localized.getString(R.string.save_error_zip_too_large)
        SaveErrorCode.CREATE_TEMP_DIR -> localized.getString(R.string.save_error_create_temp_dir)
    }
}
