package com.tyranor.next.ui.common.privacy

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.rememberBackgroundImageReady
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.NoRippleButton
import com.tyranor.next.ui.common.NoRippleTextButton
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.BlurCrossfade
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionHaptics
import com.tyranor.next.ui.common.motion.shakeOnTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用锁解锁门：[AppLockController.lockedState] 为 true 时盖在启动器界面上方的进程内覆盖层
 * （不是导航页面，性质同弹窗，因此不设顶部栏）。绘制图案校验通过解锁；
 * 开关允许且设备已录入凭据时，可用系统 BiometricPrompt（指纹/人脸/PIN）快捷解锁。
 *
 * 挂载点：AppScreenScaffold（覆盖全部二级页）与 MainActivity 的内容顶层各一处。
 */
@Composable
fun PrivacyLockGate(modifier: Modifier = Modifier) {
    val locked by AppLockController.lockedState.collectSettingAsState()
    if (!locked) return
    val context = LocalContext.current
    // 取消不困住用户：返回 = 退到后台并保持锁定（背景加载期间同样生效）
    BackHandler {
        AppLocaleController.findActivity(context)?.moveTaskToBack(true)
    }
    // 与页面背景对齐到同一帧：门自带完整背景（含自定义背景图），冷启动时背景图可能
    // 还在异步解码——若不等，门会先带纯色底出现、背景随后弹出。超时兜底保证上锁不被拖延。
    // 注意：安全屏幕（FLAG_SECURE）是窗口级、由 FlagSecureEffect 独立生效，不依赖本门渲染。
    val backgroundReady = rememberBackgroundImageReady()
    if (!backgroundReady) {
        // 等背景期间下层页面已被隐藏（skipDrawWhenHidden），但输入**从锁定成立的第一帧起
        // 就必须拦住**：不能因为背景图还在解码而把点击交给被隐藏的页面控件。
        // 这里用纯色底而不是简化门：图案盘随后与背景同帧出现。
        Box(
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) { detectTapGestures { } },
        )
        return
    }
    PrivacyUnlockGateContent(modifier)
}

@Composable
private fun PrivacyUnlockGateContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val localizedContext = remember(context) { AppLocaleController.wrap(context) }
    val scope = rememberCoroutineScope()
    // 失败计数驱动横摇与图案盘复位（shakeOnTrigger / resetKey 的共享 trigger）
    var failCount by remember { mutableIntStateOf(0) }
    // 连续「凭据读取失败」的次数：与普通图案错误分开计数，达到阈值才给「清除应用锁」入口
    var unreadableFailures by remember { mutableIntStateOf(0) }
    var resetConfirming by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(GateHint(localizedContext.getString(R.string.privacy_gate_hint_draw), false)) }
    // 凭据存在但当前读不出来（Keystore 瞬时异常）：给出设备凭据恢复路径，图案仍可重试
    val credentialUnreadable by AppLockController.credentialUnreadableState.collectSettingAsState()
    val showReset = credentialUnreadable && unreadableFailures >= CREDENTIAL_RESET_FAILURE_THRESHOLD
    LaunchedEffect(credentialUnreadable) {
        // 凭据重新可读（例如本次校验能读到、只是图案不对）：清空连续失败计数
        if (!credentialUnreadable) unreadableFailures = 0
    }
    LaunchedEffect(showReset) {
        if (!showReset) resetConfirming = false
    }

    val onPattern: (List<Int>) -> Unit = { pattern ->
        // 校验 + 解锁统一经控制器门面（UI 不直接触碰凭据存储）
        if (AppLockController.verifyAndUnlock(context, pattern)) {
            MotionHaptics.perform(MotionHaptics.Kind.Confirm)
            hint = GateHint(localizedContext.getString(R.string.privacy_gate_hint_draw), false)
        } else {
            failCount += 1
            // 读取失败不是「图案错误」：控制器已在本次校验中更新状态（同步），直接读当前值
            val unreadable = AppLockController.credentialUnreadableState.value
            if (unreadable) unreadableFailures += 1 else unreadableFailures = 0
            hint = GateHint(
                localizedContext.getString(
                    if (unreadable) R.string.privacy_gate_credential_unreadable
                    else R.string.privacy_gate_hint_wrong,
                ),
                true,
            )
        }
    }

    GateSurface(modifier) {
        GateColumn(title = stringResource(R.string.privacy_gate_title)) {
            GatePatternSection(failCount = failCount, hint = hint, onPatternEntered = onPattern)
            DeviceCredentialSection(
                forceShow = credentialUnreadable,
                onAuthError = { message ->
                    failCount += 1
                    hint = GateHint(message, true)
                },
            )
            CredentialResetSection(
                visible = showReset,
                confirming = resetConfirming,
                busy = resetting,
                onRequestConfirm = { resetConfirming = true },
                onCancel = { resetConfirming = false },
                onConfirm = {
                    if (!resetting) {
                        resetting = true
                        scope.launch {
                            // 清除凭据是阻塞的 Keystore + commit：放到后台线程。清除成功时控制器
                            // 在后台解锁、门随 lockedState 变 false 离开组合；协程若因此被取消，
                            // 清除要么没写、要么已经写完，不会留下半套。
                            val cleared = withContext(Dispatchers.IO) {
                                AppLockController.clearPatternAndDisable(context)
                            }
                            if (cleared) {
                                MotionHaptics.perform(MotionHaptics.Kind.Confirm)
                            } else {
                                // 清除失败：控制器保持锁定与开关，给出可重试提示
                                resetting = false
                                hint = GateHint(
                                    localizedContext.getString(R.string.privacy_gate_reset_failed),
                                    true,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 应用锁设置门（复用解锁门的图案盘与外观）：首次绘制 → 再次确认 → 保存并启用；
 * 两次不一致回退到首次绘制。由隐私设置页在「启用应用锁 / 修改图案」时拉起。
 */
@Composable
fun PrivacyLockSetupGate(
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val localizedContext = remember(context) { AppLocaleController.wrap(context) }
    val scope = rememberCoroutineScope()
    var failCount by remember { mutableIntStateOf(0) }
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    // 保存进行中：凭据写入是阻塞 IO，期间不再接受新的图案（避免重复提交）
    var saving by remember { mutableStateOf(false) }
    // null = 按当前阶段显示默认提示；错误时覆盖
    var hint by remember { mutableStateOf<GateHint?>(null) }

    // 保存进行中禁止取消 / 返回：落盘完成前关掉门会让保存回调与门状态错拍
    BackHandler { if (!saving) onCancelled() }

    val drawHint = stringResource(R.string.privacy_gate_setup_draw)
    val confirmHint = stringResource(R.string.privacy_gate_setup_confirm)

    val onPattern: (List<Int>) -> Unit = { pattern ->
        val first = firstPattern
        when {
            first == null -> {
                firstPattern = pattern
                hint = null
            }
            pattern == first && !saving -> {
                saving = true
                scope.launch {
                    // Keystore 加密 + commit 是阻塞落盘，必须在后台线程执行
                    val saved = withContext(Dispatchers.IO) {
                        AppLockController.savePatternAndEnable(context, pattern)
                    }
                    saving = false
                    if (saved) {
                        MotionHaptics.perform(MotionHaptics.Kind.Confirm)
                        onCompleted()
                    } else {
                        // Keystore 瞬时异常导致保存失败：回退到首次绘制，凭据保持不变
                        firstPattern = null
                        failCount += 1
                        hint = GateHint(localizedContext.getString(R.string.privacy_gate_setup_save_failed), true)
                    }
                }
            }
            pattern != first -> {
                firstPattern = null
                failCount += 1
                hint = GateHint(localizedContext.getString(R.string.privacy_gate_setup_mismatch), true)
            }
        }
    }

    GateSurface(modifier) {
        GateColumn(title = stringResource(R.string.privacy_gate_setup_title)) {
            GatePatternSection(
                failCount = failCount,
                hint = hint ?: GateHint(if (firstPattern == null) drawHint else confirmHint, false),
                enabled = !saving,
                onPatternEntered = onPattern,
            )
            NoRippleTextButton(
                text = stringResource(R.string.common_cancel),
                enabled = !saving,
                onClick = onCancelled,
            )
        }
    }
}

/**
 * 门共用的背景 + 居中内容列。
 *
 * 触摸与无障碍都必须**整屏拦截**：Compose 命中测试只认有输入处理的节点——若根层没有
 * 手势处理器，锁定时门中央图案盘以外的区域点击会穿透到被锁页面（可隔门点游戏卡、
 * 返回箭头、设置行）。因此根层挂全屏点击消费（先例：`AppAlertDialog` / `AppSidePanel`
 * 的 `pointerInput(Unit) { detectTapGestures { } }`）；无障碍屏蔽由挂载点对页面内容做
 * `clearAndSetSemantics`，门自身子树（图案盘 / 按钮）保持 TalkBack 可达。
 *
 * 背景与页面完全一致（`GlassBackground`，含自定义背景图与粒子）。为避免背景重复绘制
 * （空闲功耗翻倍），两个挂载点都在锁定期间用 `skipDrawWhenHidden` 跳过整块页面
 * （含根背景与粒子）——门是唯一绘制的一层。
 */
@Composable
private fun GateSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            // 整屏消费点击：防止穿透到下层被锁页面（门的子节点仍保留无障碍语义，
            // 图案盘与按钮 TalkBack 可达；屏蔽下层靠挂载点对页面内容清语义）
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // 门自带完整背景（含自定义背景图与粒子）：锁定时页面内容已被隐藏
        // （见挂载点），背景只画这一份。
        GlassBackground { }
        content()
    }
}

@Composable
private fun GateColumn(
    title: String,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            // 矮横屏 / 大字体下内容可能超过窗口（256dp 图案盘 + 提示 + 按钮），
            // 允许滚动，避免提示与设备凭据按钮被裁到屏幕外；内容放得下时布局不变。
            // 图案盘自己消费手势，从盘面起手的拖动不会滚走。
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PageTextColor,
            )
            content()
        }
    }
}

/** 图案盘 + 下方提示文案：失败时整块横摇并复位图案。 */
@Composable
private fun GatePatternSection(
    failCount: Int,
    hint: GateHint,
    enabled: Boolean = true,
    onPatternEntered: (List<Int>) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .shakeOnTrigger(failCount)
                .padding(top = 4.dp),
        ) {
            PatternPad(
                resetKey = failCount,
                modifier = Modifier.size(256.dp),
                enabled = enabled,
                contentDescription = stringResource(R.string.privacy_gate_pattern_desc),
                onPatternEntered = onPatternEntered,
            )
        }
        BlurCrossfade(targetState = hint) { value ->
            Text(
                value.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (value.isError) MaterialTheme.colorScheme.error else PageSecondaryTextColor,
            )
        }
    }
}

/**
 * 设备凭据快捷解锁：开关允许且设备可用（弱生物识别或锁屏凭据任一）时显示按钮，
 * 经系统 BiometricPrompt 验证（API 28/29 的凭据回退由库内部处理）。
 *
 * @param forceShow 凭据读取失败时强制显示：此时图案无法校验，设备凭据是唯一的恢复路径，
 *   即使「设备凭据快捷解锁」开关关闭也要给出口（否则 Keystore 密钥失效会把应用永久锁死）。
 */
@Composable
private fun DeviceCredentialSection(
    onAuthError: (String) -> Unit,
    forceShow: Boolean = false,
) {
    val allowBiometric by AppSettingsStore.privacyAppLockBiometricState.collectSettingAsState()
    val context = LocalContext.current
    val localizedContext = remember(context) { AppLocaleController.wrap(context) }
    val fragmentActivity = remember(context) { AppLocaleController.findActivity(context) as? FragmentActivity }
    val credentialUsable = remember(fragmentActivity) {
        fragmentActivity != null && isDeviceCredentialUsable(fragmentActivity)
    }
    if ((!allowBiometric && !forceShow) || !credentialUsable) return

    val prompt = remember(fragmentActivity) {
        val activity = fragmentActivity ?: return@remember null
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    MotionHaptics.perform(MotionHaptics.Kind.Confirm)
                    AppLockController.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户主动取消 / 选了「使用图案」负按钮：静默留在锁门，不算失败
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        return
                    }
                    onAuthError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // 生物识别未匹配：系统对话框会自动重试，这里只补一次拒绝触觉
                    MotionHaptics.perform(MotionHaptics.Kind.Reject)
                }
            },
        )
    }

    val authenticate: () -> Unit = {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(localizedContext.getString(R.string.privacy_gate_device_title))
            .setDescription(localizedContext.getString(R.string.privacy_gate_device_description))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt?.authenticate(info)
    }
    NoRippleButton(
        text = stringResource(R.string.privacy_gate_device_unlock),
        onClick = authenticate,
    )
}

/**
 * 凭据持续不可读时的恢复入口（不依赖设备凭据）：连续若干次校验都读不到凭据后显示，
 * 二次确认后清除凭据并关闭应用锁。该状态通常由换机迁移 / 系统重置导致 Keystore 密钥
 * 永久失效——此时任何人都无法解锁，密钥永久失效时重置不降低安全性；若只是瞬时读取失败，
 * 图案重试或设备凭据仍可正常解锁，入口会在凭据重新可读后自动消失。
 *
 * 确认流程留在门内，不用 `AppAlertDialog`：门是覆盖在页面上的特殊全屏层，弹窗是独立 Window、
 * 层级在门之上，且宿主的 `DismissOnAppLockEffect` 会在 locked 时立即把它收起；内联确认最稳定。
 */
@Composable
private fun CredentialResetSection(
    visible: Boolean,
    confirming: Boolean,
    busy: Boolean,
    onRequestConfirm: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ExpandVisibility(visible = visible) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.privacy_gate_reset_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = PageSecondaryTextColor,
                textAlign = TextAlign.Center,
            )
            BlurCrossfade(targetState = confirming) { nowConfirming ->
                if (nowConfirming) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.privacy_gate_reset_confirm_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NoRippleTextButton(
                                text = stringResource(R.string.common_cancel),
                                enabled = !busy,
                                onClick = onCancel,
                            )
                            NoRippleTextButton(
                                text = stringResource(R.string.privacy_gate_reset_confirm),
                                enabled = !busy,
                                contentColor = MaterialTheme.colorScheme.error,
                                onClick = onConfirm,
                            )
                        }
                    }
                } else {
                    NoRippleTextButton(
                        text = stringResource(R.string.privacy_gate_reset_action),
                        enabled = !busy,
                        onClick = onRequestConfirm,
                    )
                }
            }
        }
    }
}

/** 连续多少次「凭据读取失败」后开放「清除应用锁」恢复入口。 */
private const val CREDENTIAL_RESET_FAILURE_THRESHOLD = 3

/** 设备凭据可用性：弱生物识别或锁屏凭据任一可用（API 30- 按两路分别判定）。 */
internal fun isDeviceCredentialUsable(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } else {
        @Suppress("DEPRECATION") // API 28/29 只有无参版本；组合档判定在更低版本按两路拆分
        manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS ||
            (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.isDeviceSecure == true
    }
}

/** 门内提示文案（横摇与文案替换的共享载荷）：相等时不重播替换。 */
private data class GateHint(
    val text: String,
    val isError: Boolean,
)
