package com.tyranor.next.ui.cover

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoIndication
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.rememberLastNonNull
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 凭据确认结论：成功保存并关闭弹窗；失败在弹窗内展示文案（文案由调用方按既有映射组装）。 */
internal sealed interface CredentialDialogOutcome {
    data object Saved : CredentialDialogOutcome
    data class Failed(val message: String) : CredentialDialogOutcome
}

/**
 * 纯映射（JVM 可测）：粘贴凭据清洗 = 首尾 trim + 一行 filterNot 去内部全部空白
 * （BK-20：含换行/空格的粘贴 token 不再原样通过校验入库）。
 */
internal fun cleanCredentialToken(token: String): String =
    token.trim().filterNot { it.isWhitespace() }

/**
 * 凭据粘贴弹窗（VNDB token 与 Bangumi 个人令牌共享，抽自原先两份近 300 行的复制实现）：
 * 弹窗结构、凭据掩码/密码键盘/autofill 身份、输入区点击反馈禁用、校验转圈与
 * statusText（含 statusIsError 区分）保持一致；差异由调用方参数化——文案键、
 * 凭据存取与「校验 + 保存」回调。调用方约定：
 * - [confirmToken] 在 IO 线程执行，完成校验与入库；`CancellationException` 必须原样上抛。
 *   传入 token 已由 [cleanCredentialToken] 清洗（首尾 trim + 去内部全部空白，BK-20），
 *   调用方无需重复处理。
 * - [readStoredToken] / [hasStoredCredential] / [clearCredential] 在主线程调用
 *   （初值读取与既有实现一致；解除授权为本地操作）。readStoredToken / hasStoredCredential
 *   为 Keystore 解密读取，毫秒级开销、有意留在主线程（BK-01 契约注记）；真机出现可感
 *   卡顿 / StrictMode 违规时按「分点下沉 IO」统一下沉（弹窗初值并入下方 IO 回显协程、
 *   组合期读点改异步预取），不逐点散改；clearCredential 保持主线程（本地 prefs 清除）。
 */
@Composable
internal fun CredentialTokenDialog(
    titleRes: Int,
    instructionsRes: Int,
    missingRes: Int,
    savedRes: Int,
    readStoredToken: (Context) -> String,
    hasStoredCredential: (Context) -> Boolean,
    clearCredential: (Context) -> Unit,
    confirmToken: suspend (Context, String) -> CredentialDialogOutcome,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 凭据不落 Activity SavedState（rememberSaveable 会把原文写进系统 Bundle，可被 dumpsys 提取）
    var token by remember { mutableStateOf(readStoredToken(context)) }
    var tokenVisible by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    // 校验/权限结果反馈在弹窗文本中（错误时保留弹窗提示）
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = stringResource(titleRes)

    fun confirm() {
        if (verifying) return
        // 点击时点读 state 最新值并清洗（BK-20）：不消费组合期快照——快照 val 可能滞后于
        // 输入（快速点击时 recomposition 尚未执行），导致旧值被校验/入库
        val cleaned = cleanCredentialToken(token)
        if (cleaned.isEmpty()) {
            // 非组合期回调的文案解析沿用两弹窗既有的 AppLocaleController.wrap 口径
            statusText = AppLocaleController.wrap(context).getString(missingRes)
            statusIsError = true
            return
        }
        verifying = true
        statusText = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    confirmToken(context, cleaned)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // 防御性兜底：调用方回调自身已做类型化映射，此处只接未预期异常
                    CredentialDialogOutcome.Failed(
                        AppLocaleController.wrap(context).getString(R.string.play_error_unknown),
                    )
                }
            }
            verifying = false
            when (outcome) {
                is CredentialDialogOutcome.Saved -> {
                    AppNotice.makeText(
                        context,
                        AppLocaleController.wrap(context).getString(savedRes),
                        Toast.LENGTH_SHORT,
                        AppNotice.Kind.Success,
                    ).show()
                    onDismiss()
                }
                is CredentialDialogOutcome.Failed -> {
                    statusIsError = true
                    statusText = outcome.message
                }
            }
        }
    }

    AppAlertDialog(
        // 验证期间（verifying）遮罩点击/返回键与取消钮一致禁用（门禁四面之一，等待语义见
        // dismissButton 段注释，BK-56）
        onDismissRequest = { if (!verifying) onDismiss() },
        dismissEnabled = !verifying,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(instructionsRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 输入区内禁用全部点击/按压反馈（AGENTS.md 弹窗点击反馈统一规范）
                CompositionLocalProvider(LocalIndication provides NoIndication) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppSearchField(
                            query = token,
                            onQueryChange = { token = it },
                            onSearch = { confirm() },
                            imeAction = ImeAction.Done,
                            leadingIcon = painterResource(R.drawable.ic_cover_source),
                            iconContentDescription = title,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            // 凭据字段：掩码 + 密码键盘 + Password autofill 身份（两弹窗同约定）
                            visualTransformation = if (tokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardType = KeyboardType.Password,
                            autofillContentType = ContentType.Password,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { tokenVisible = !tokenVisible }
                                .padding(6.dp),
                        ) {
                            BlurCrossfade(targetState = tokenVisible, label = "tokenVisibilityIcon") { visible ->
                                Icon(
                                    painter = painterResource(R.drawable.ic_visibility),
                                    contentDescription = stringResource(
                                        if (visible) R.string.auth_token_hide else R.string.auth_token_show,
                                    ),
                                    tint = if (visible) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
                ExpandVisibility(visible = verifying, label = "credentialVerifyProgress") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
                val currentStatus = statusText?.takeIf { it.isNotBlank() }?.let { it to statusIsError }
                val shownStatus = rememberLastNonNull(currentStatus)
                ExpandVisibility(visible = currentStatus != null, label = "credentialStatusExpand") {
                    BlurCrossfade(targetState = shownStatus, label = "credentialStatusText") { banner ->
                        if (banner != null) {
                            Text(
                                banner.first,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (banner.second) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogTextButton(
                text = stringResource(R.string.common_confirm),
                enabled = !verifying,
                onClick = { confirm() },
            )
        },
        dismissButton = {
            // 等待语义（BK-56 注释固化）：验证期间（verifying）取消钮与遮罩/返回一致禁用
            // （confirmButton 同禁）——底层校验为阻塞 IO、单请求最坏 ~35s（connect 15s +
            // read 20s，HttpJson 超时），无中途取消途径；保持与状态写入弹窗（PlayStatusDialog
            // 写入期同口径）一致，防半保存状态。非验证期间纯取消恒可用；已存凭据时并列
            // 提供「清空」。仅以已存凭据判定：未保存的输入不再把「取消」漂移成「清空」、
            // 也不会误清已存凭据。
            DialogTextButton(
                text = stringResource(R.string.common_cancel),
                enabled = !verifying,
                onClick = onDismiss,
            )
            if (hasStoredCredential(context)) {
                DialogTextButton(
                    text = stringResource(R.string.auth_token_clear),
                    enabled = !verifying,
                    // 破坏性动作用 error 色（DialogTextButton 约定；改动即对齐）
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        clearCredential(context)
                        onDismiss()
                    },
                )
            }
        },
    )
}
