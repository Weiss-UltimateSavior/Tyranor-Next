package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color as ComposeColor
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.core.input.PadEditorHost
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按键方案布局编辑器（应用内）。
 *
 * 复用 engine 侧按键层与编辑面板（经 core 门面 [PadEditorHost]，ui 不直接依赖 engine）：
 * 与游戏内编辑同一套渲染、拖拽、改键与新增逻辑，进屏即编辑态、保存即落盘。
 *
 * 本 Activity 声明 `sensorLandscape`：游戏几乎都是横屏，编辑布局在横屏下所见即所得；
 * 退出后应用其余页面仍为竖屏。
 */
class PadLayoutEditActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        setAppScreenContent { PadLayoutEditScreen(profileId) }
    }

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"

        fun createIntent(context: Context, profileId: String): Intent =
            Intent(context, PadLayoutEditActivity::class.java).putExtra(EXTRA_PROFILE_ID, profileId)
    }
}

@Composable
internal fun PadLayoutEditScreen(profileId: String) {
    val ctx = LocalContext.current
    // ProvideAppLocale 会包一层 ContextWrapper，不能直接 as? Activity（会静默失效导致退不出页面）
    val activity = AppLocaleController.findActivity(ctx)
    val savedMessage = stringResource(R.string.pad_layout_edit_saved)
    val invalidMessage = stringResource(R.string.input_settings_profile_import_failed)
    val primary = MaterialTheme.colorScheme.primary.toArgb()

    // 宿主 View 只在首次组合构建；Activity 退出时随视图树回收
    val host = remember(profileId) {
        PadEditorHost.create(
            context = ctx,
            profileId = profileId,
            primaryColor = primary,
            // 主题色实底组件内文字固定白色（项目规范，避免深色模式下 onPrimary 退色）
            onPrimaryColor = Color.WHITE,
            onSaved = {
                Toast.makeText(ctx, savedMessage, Toast.LENGTH_SHORT).show()
                // 保存即完成本页使命，直接退出（编辑面板随视图树回收）
                activity?.finish()
            },
            onCancelled = { activity?.finish() },
        )
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ComposeColor.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.pad_layout_edit_title),
                    background = ComposeColor.Transparent,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            if (host == null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).also {
                            Toast.makeText(context, invalidMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    factory = { host.rootView },
                )
            }
        }
    }
}
