package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.core.privacy.AppLockController
import com.tyranor.next.core.privacy.AppLockCredentialStore
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.ArrowPreference
import com.tyranor.next.ui.common.OverlayDropdownPreference
import com.tyranor.next.ui.common.SwitchPreference
import com.tyranor.next.ui.common.collectSettingAsState
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.pressSquishGroup
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.privacy.PrivacyLockSetupGate
import com.tyranor.next.ui.common.privacy.isDeviceCredentialUsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

/** 隐私设置：防止截图 / 隐藏通知内容 / 应用锁。 */
class PrivacySettingsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            PrivacySettingsScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, PrivacySettingsActivity::class.java)
    }
}

/**
 * 隐私设置页：窗口级效果即时落盘（FLAG_SECURE / 模糊在挂载点即时读取），
 * 应用锁的图案凭据由设置门拉起绘制，开关只在凭据保存成功后落盘。
 */
@Composable
internal fun PrivacySettingsScreen() {
    val ctx = LocalContext.current
    val preventScreenshot by AppSettingsStore.privacyPreventScreenshotState.collectSettingAsState()
    val hideNotification by AppSettingsStore.privacyHideNotificationState.collectSettingAsState()
    val appLockEnabled by AppSettingsStore.privacyAppLockEnabledState.collectSettingAsState()
    val allowBiometric by AppSettingsStore.privacyAppLockBiometricState.collectSettingAsState()
    val timeoutIndex by AppSettingsStore.privacyAppLockTimeoutIndexState.collectSettingAsState()

    LaunchedEffect(Unit) {
        // 本页可能先于 Application 的启动初始化被组合（如进程恢复直达），主动加载持久化值
        withContext(Dispatchers.IO) { AppSettingsStore.initPrivacySettings(ctx) }
    }

    // 设备凭据可用性（弱生物识别或锁屏凭据）：设备未设锁屏密码时隐藏快捷解锁开关。
    // 无 key：按本页进入时的快照判定——在本页打开系统设置录入/删除锁屏凭据后需重新进入本页才会刷新
    val credentialUsable = remember { isDeviceCredentialUsable(ctx) }
    // 下拉选项顺序必须与 storageIndex 对齐（PrivacyLockTimeout.ordered 即 0..3 档）
    val timeoutLabels = listOf(
        stringResource(R.string.privacy_timeout_immediate),
        stringResource(R.string.privacy_timeout_one_minute),
        stringResource(R.string.privacy_timeout_five_minutes),
        stringResource(R.string.privacy_timeout_fifteen_minutes),
    )

    // 应用锁设置门（绘制图案 → 确认 → 保存并启用 / 修改图案）
    var showSetupGate by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MiuixSettingsTheme {
            ProvideEntrance {
                MiuixScaffold(
                    modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
                    containerColor = ComposeColor.Transparent,
                    contentWindowInsets = WindowInsets(0.dp),
                    topBar = { PrivacySettingsTopBar() },
                ) { innerPadding ->
                    LazyColumn(
                        // 顶栏透明：列表整体垫在顶栏下方（持久 padding），避免滚动时内容穿过顶栏
                        modifier = Modifier.fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            MiuixCard(
                                modifier = Modifier.pressSquishGroup().staggeredEntrance(0).fillMaxWidth(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    SwitchPreference(
                                        title = stringResource(R.string.privacy_secure_screen_title),
                                        summary = stringResource(R.string.privacy_secure_screen_summary),
                                        checked = preventScreenshot,
                                        onCheckedChange = { checked ->
                                            AppSettingsStore.setPrivacyPreventScreenshot(ctx, checked)
                                        },
                                    )
                                }
                            }
                        }

                        item {
                            MiuixCard(
                                modifier = Modifier.pressSquishGroup().staggeredEntrance(1).fillMaxWidth(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    SwitchPreference(
                                        title = stringResource(R.string.privacy_hide_notification_title),
                                        summary = stringResource(R.string.privacy_hide_notification_summary),
                                        checked = hideNotification,
                                        onCheckedChange = { checked ->
                                            AppSettingsStore.setPrivacyHideNotification(ctx, checked)
                                        },
                                    )
                                }
                            }
                        }

                        item {
                            MiuixCard(
                                modifier = Modifier.pressSquishGroup().staggeredEntrance(2).fillMaxWidth(),
                                cornerRadius = AppComponentCornerRadius,
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    SwitchPreference(
                                        title = stringResource(R.string.privacy_app_lock_title),
                                        summary = stringResource(R.string.privacy_app_lock_summary),
                                        checked = appLockEnabled,
                                        onCheckedChange = { checked ->
                                            when {
                                                checked -> {
                                                    // 已有图案凭据直接启用；否则拉起设置门，确认后再落开关
                                                    if (AppLockCredentialStore.hasPattern(ctx)) {
                                                        AppSettingsStore.setPrivacyAppLockEnabled(ctx, true)
                                                        AppLockController.resetTimer()
                                                    } else {
                                                        showSetupGate = true
                                                    }
                                                }
                                                else -> AppLockController.clearPatternAndDisable(ctx)
                                            }
                                        },
                                    )
                                    if (appLockEnabled) {
                                        OverlayDropdownPreference(
                                            items = timeoutLabels,
                                            selectedIndex = timeoutIndex,
                                            title = stringResource(R.string.privacy_app_lock_timeout_title),
                                            summary = stringResource(R.string.privacy_app_lock_timeout_summary),
                                            onSelectedIndexChange = { index ->
                                                AppSettingsStore.setPrivacyAppLockTimeoutIndex(ctx, index)
                                            },
                                        )
                                        if (credentialUsable) {
                                            SwitchPreference(
                                                title = stringResource(R.string.privacy_app_lock_biometric_title),
                                                summary = stringResource(R.string.privacy_app_lock_biometric_summary),
                                                checked = allowBiometric,
                                                onCheckedChange = { checked ->
                                                    AppSettingsStore.setPrivacyAppLockBiometric(ctx, checked)
                                                },
                                            )
                                        }
                                        ArrowPreference(
                                            title = stringResource(R.string.privacy_app_lock_change_pattern),
                                            summary = stringResource(R.string.privacy_app_lock_change_pattern_summary),
                                            onClick = { showSetupGate = true },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 应用锁设置门：盖在页面（含顶栏）之上的进程内覆盖层；取消时开关保持原状
        if (showSetupGate) {
            PrivacyLockSetupGate(
                onCompleted = { showSetupGate = false },
                onCancelled = { showSetupGate = false },
            )
        }
    }
}

@Composable
private fun PrivacySettingsTopBar() {
    AppSecondaryTopBar(
        title = stringResource(R.string.privacy_settings_title),
        background = ComposeColor.Transparent,
        contentColor = PageTextColor,
    )
}
