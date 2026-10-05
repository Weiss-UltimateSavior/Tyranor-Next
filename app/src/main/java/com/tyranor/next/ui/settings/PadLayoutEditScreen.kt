package com.tyranor.next.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
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
import androidx.core.view.doOnLayout
import com.core.engine.EngineThemeColors
import com.core.input.InputSink
import com.core.input.PadProfile
import com.core.input.VirtualPadEditPanel
import com.core.input.VirtualPadView
import com.tyranor.next.R
import com.tyranor.next.core.input.InputRemapRepository
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按键方案布局编辑器（应用内）。
 *
 * 复用 engine 的 [VirtualPadView] 与 [VirtualPadEditPanel]：与游戏内编辑同一套渲染、
 * 拖拽、改键与新增逻辑，只把按键出口换成预览占位（[PreviewSink] 不产生输出），
 * 因此设置页里改出来的布局与游戏内所见一致。
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

/** 编辑期的按键出口占位：预览不向任何引擎发送输入。 */
private object PreviewSink : InputSink {
    override fun send(key: Int, down: Boolean) = Unit
    override fun releaseAll() = Unit
}

@Composable
internal fun PadLayoutEditScreen(profileId: String) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val profile = remember(profileId) { InputRemapRepository.readProfile(ctx, profileId) }

    val palette = EngineThemeColors.Palette(
        primary = MaterialTheme.colorScheme.primary.toArgb(),
        onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb(),
        card = MaterialTheme.colorScheme.surface.toArgb(),
        text = MaterialTheme.colorScheme.onSurface.toArgb(),
        textMuted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    )
    val savedMessage = stringResource(R.string.pad_layout_edit_saved)

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
            if (profile == null) {
                // 方案文件缺失/损坏：仅提示，返回由用户操作（组合期直接 finish 会打断重建）
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).also {
                            Toast.makeText(
                                context,
                                context.getString(R.string.input_settings_profile_import_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    factory = { context ->
                        // 面板与 pad 互相引用：先建 pad，listener 经局部变量回调面板刷新
                        var panel: VirtualPadEditPanel? = null
                        FrameLayout(context).apply {
                            setBackgroundColor(Color.BLACK)
                            val pad = VirtualPadView(context, PreviewSink)
                            pad.theme = VirtualPadView.PadTheme(palette.primary, palette.onPrimary)
                            pad.profile = profile
                            pad.setPadVisible(true)
                            pad.listener = object : VirtualPadView.Listener {
                                override fun onEditStarted() = Unit
                                override fun onEditEnded() = Unit
                                override fun onSelectionChanged(info: VirtualPadView.SelectionInfo?) {
                                    panel?.refresh()
                                }

                                override fun onProfileCommitted(committed: PadProfile) {
                                    InputRemapRepository.writeProfile(context, committed)
                                }

                                override fun onPadVisibilityChanged(visible: Boolean) = Unit
                            }
                            addView(
                                pad,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            panel = VirtualPadEditPanel.attach(
                                parent = this,
                                context = context,
                                pad = pad,
                                theme = palette,
                                onSave = {
                                    pad.commitEdit()
                                    Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                                    activity?.finish()
                                },
                                onCancel = {
                                    pad.cancelEdit()
                                    activity?.finish()
                                },
                            )
                            // 进屏即编辑态（拖拽/选中可用；FAB 不参与预览）。
                            // 必须等首次布局完成：元素矩形在 onSizeChanged 里计算，
                            // 提前进入会拿到空元素表、无法选中。
                            pad.doOnLayout { pad.enterEditMode() }
                        }
                    },
                )
            }
        }
    }
}
