package com.tyranor.next.core.game.save

import java.io.IOException

/**
 * 存档操作错误码（P0-6）：核心层只抛出类型化错误，UI 层按 [SaveErrorCode] 映射本地化文案；
 * [GameSaveException.detail] 仅携带需要拼入文案的参数（文件名等），不含任何文案本身。
 */
enum class SaveErrorCode {
    RESOLVE_SAVE_DIR_FAILED,
    NO_EXPORTABLE_FILES,
    CREATE_EXPORT_ZIP,
    CREATE_SAVE_DIR,
    SAVE_DIR_UNAVAILABLE,
    NO_FILES_IN_ZIP,
    READ_IMPORT_ZIP,
    DUPLICATE_ZIP_ENTRY,
    TOO_MANY_ZIP_FILES,
    ILLEGAL_ZIP_PATH,
    CREATE_SAVE_DIR_NAMED,
    ZIP_TOO_LARGE,
    CREATE_TEMP_DIR,
}

class GameSaveException(
    val code: SaveErrorCode,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(code.name, cause)
