package com.tyranor.next.ui.common

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.tyranor.next.core.settings.AppSettingsStore
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassBackground
import com.tyranor.next.theme.TyranorNextTheme
import com.tyranor.next.theme.WithoutPressIndication
import com.tyranor.next.ui.common.motion.PageTransitions
import com.tyranor.next.ui.common.motion.ProvideMotionEnvironment
import com.tyranor.next.ui.common.motion.pageTransitionScrim
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag

/**
 * 二级页面统一宿主（P1-4）：收敛各 Activity 重复的
 * 「isDarkEffective → enableEdgeToEdge → 透明系统栏 → 主题包裹 → finish 转场」样板。
 *
 * 用法：Activity 继承 [AppScreenActivity]，在 `onCreate` 中校验入参后调用
 * [setAppScreenContent] 提供正文；页面自身需要的 Miuix 子主题照常在正文内声明。
 */
abstract class AppScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeSaveSyncFeedback()
        // 深色背景（深色外观 / 复古玻璃 / 白雾玻璃）系统栏恒用浅色图标
        applySystemBarStyle(
            AppSettingsStore.isDarkEffective(this) ||
                AppSettingsStore.getAppearanceStyle(this).hasDarkBackdrop,
        )
    }

    /** 统一主题包裹并提供页面正文。 */
    protected fun setAppScreenContent(content: @Composable () -> Unit) {
        setContent {
            // 外观模式/外观风格在页面内切换时（如应用设置页），系统栏图标实时跟随
            val dark = AppThemeColors.isDark || AppThemeColors.hasDarkBackdrop
            SideEffect { applySystemBarStyle(dark) }
            AppScreenScaffold(content = content)
        }
    }

    override fun finish() {
        super.finish()
        // 返回：当前页向右整页退出，上一级从左侧回到原位（侧推的倒放）
        PageTransitions.applyPop(this)
    }
}

/** 应用透明沉浸系统栏与深/浅图标（不透明背景由页面根组件向上延伸）。 */
private fun ComponentActivity.applySystemBarStyle(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
        navigationBarStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        },
    )
}

/**
 * 二级页面统一内容壳：ProvideAppLocale → TyranorNextTheme → 页面根背景
 * （默认=页面背景色 / 玻璃=黑灰渐变，见 [GlassBackground]）→ 去除按压指示；
 * 正文即页面根内容。
 */
@Composable
fun AppScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ProvideAppLocale {
        TyranorNextTheme {
            ProvideMotionEnvironment {
                // pageTransitionScrim：推入下一页时本页随转场压暗，返回时渐亮（静止时透明）
                GlassBackground(modifier = modifier.pageTransitionScrim().skipEntranceOnDrag()) {
                    WithoutPressIndication {
                        content()
                    }
                }
            }
        }
    }
}
