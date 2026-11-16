package com.tyranor.next.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.NoticeDotRed
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.BlurVisibility
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.breathing
import com.tyranor.next.ui.common.motion.pressSquishGroup
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单条功能入口卡：一张 MiuixCard 只承载一条 ArrowPreference。
 *
 * 每个入口独立成卡，避免多个不相关功能挤在同一张卡里；卡片间距由外层列表统一提供，
 * 多行同类设置仍由 `EngineCard` 这类分组卡承载。设置页与关于应用页共用本组件。
 *
 * 调用方需处于 [com.tyranor.next.theme.MiuixSettingsTheme]（或其上级主题）内。
 */
@Composable
internal fun SettingsEntryCard(
    title: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    showBadge: Boolean = false,
    /** 该入口对应的操作正在进行（如检查更新）：图标呼吸并在开始 / 结束时弹一下。 */
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    MiuixCard(
        modifier = modifier.pressSquishGroup().fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ArrowPreference(
                title = title,
                startAction = { SettingsItemIcon(iconRes, showBadge = showBadge, busy = busy) },
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun SettingsItemIcon(@DrawableRes iconRes: Int, showBadge: Boolean = false, busy: Boolean = false) {
    Box(modifier = Modifier.padding(end = 6.dp).size(24.dp)) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            // 左侧图标统一跟随主题色调（Miuix 页面取 MiuixTheme 的 primary）
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary),
            modifier = Modifier.bounceOnChange(busy, depth = 0.18f).breathing(busy).fillMaxSize(),
        )
        // 红点提醒：固定亮红，不随主题色变化；显隐执行内容转场，不改变图标的布局尺寸
        BlurVisibility(
            visible = showBadge,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 5.dp, y = (-5).dp)
                .size(8.dp),
        ) {
            Box(Modifier.fillMaxSize().background(NoticeDotRed, CircleShape))
        }
    }
}
