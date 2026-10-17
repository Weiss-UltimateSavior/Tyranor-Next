package com.tyranor.next.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.tyranor.next.ui.common.DialogTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.ImmutableFontImport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 字体导入单实现（m18）：统一的显示名解析 + 扩展名白名单校验 + 复制到私有字体目录。
 * 全局字体设置与单游戏覆盖共用，禁止各页面再复制平行实现。
 */
internal object FontImport {
    /** 导入字体文件到 `filesDir/fonts`；扩展名非法或 IO 失败返回 null。 */
    suspend fun importToPrivate(context: Context, uri: Uri): String? {
        val coroutine = currentCoroutineContext()
        return runInterruptible(Dispatchers.IO) { importBlocking(context, uri) { coroutine.ensureActive() } }
    }

    private fun importBlocking(context: Context, uri: Uri, checkCancelled: () -> Unit): String? = try {
        val displayName = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
        val name = (displayName ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
        val dir = java.io.File(context.filesDir, "fonts")
        if (dir.canonicalFile != java.io.File(context.filesDir.canonicalFile, "fonts")) return null
        ImmutableFontImport.import(
            directory = dir,
            displayName = name,
            input = { context.contentResolver.openInputStream(uri) },
            checkCancelled = checkCancelled,
            validate = { android.graphics.Typeface.createFromFile(it) != null },
        )
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/**
 * 字体选择行（m19）：Miuix ArrowPreference + 统一弹窗（「跟随/内置」项 + 「选择字体文件」项）。
 * 全局字体与单游戏覆盖共用同一组件，仅 [followLabel] 与值展示方式不同。
 *
 * @param value 当前生效字体名（或“跟随全局（xx）”文案）
 * @param followLabel 弹窗第一项文案：全局页用「使用内置字体」，单游戏页用「跟随全局」
 * @param valueInSummary true 时值显示在标题下方（单游戏覆盖版的摘要形态），false 显示在行尾
 */
@Composable
internal fun FontPreference(
    label: String,
    value: String,
    followLabel: String,
    onFollow: () -> Unit,
    onPick: () -> Unit,
    valueInSummary: Boolean = false,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    ArrowPreference(
        title = label,
        summary = if (valueInSummary) value else null,
        endActions = {
            if (!valueInSummary) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        enabled = enabled,
        onClick = { if (enabled) open = true },
    )
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppNavItem(
                        title = followLabel,
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        onFollow()
                        open = false
                    }
                    AppNavItem(
                        title = stringResource(R.string.engine_settings_select_font_file),
                        leadingIcon = R.drawable.ic_font_bookmark,
                        containerColor = DialogItemSurface,
                        indication = null,
                    ) {
                        open = false
                        onPick()
                    }
                }
            },
            confirmButton = { DialogTextButton(text = stringResource(R.string.common_cancel), onClick = { open = false }) },
        )
    }
}
