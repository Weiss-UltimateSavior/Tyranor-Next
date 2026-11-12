package com.tyranor.next.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 组件圆角统一入口：数值由应用设置「应用圆角调整」统一控制（5..50dp，十档），
 * 与外观风格无关——默认、复古玻璃与白雾玻璃共用同一个圆角。
 * 所有卡片/条目/弹窗/抽屉等「组件级」圆角必须引用本文件，不再散落字面量。
 * 例外：顶栏图标 6dp（TopBarIcon）、搜索框胶囊（CircleShape）、液态玻璃导航 16dp（规范豁免）、
 * 液态玻璃 · 透镜底栏胶囊（[AppNavCapsuleShape]）、悬浮按钮导航圆形（CircleShape）。
 */
val AppComponentShape: Shape
    get() = RoundedCornerShape(AppThemeColors.appCornerRadius.dp)

/**
 * 液态玻璃 · 透镜底栏（`ui/common/glass/`）的胶囊轮廓：圆角 = 半高
 * （64dp 栏体 → 32dp，56dp 透镜 → 28dp）。
 *
 * 源实现使用 capsule 库的 `ContinuousCapsule`；本项目锁定 Backdrop 1.0.2，其 `lens` 只接受
 * `CornerBasedShape`，因此用标准胶囊等价替代（分析报告 §7.2 路线 B 的已记录差异 D1）。
 * 该豁免仅适用于透镜档底栏的「栏体 / 副本行 / 移动透镜」三处，其余组件不得援引。
 */
val AppNavCapsuleShape: Shape = RoundedCornerShape(percent = 50)

/** Miuix 组件（MiuixCard 等）以 Dp 接收圆角，使用本值保证与 [AppComponentShape] 同源。 */
val AppComponentCornerRadius: Dp
    get() = AppThemeColors.appCornerRadius.dp

/** 底部抽屉顶部圆角（仅上方两角），与 [AppComponentShape] 同源。 */
val AppSheetTopShape: Shape
    get() = RoundedCornerShape(
        topStart = AppThemeColors.appCornerRadius.dp,
        topEnd = AppThemeColors.appCornerRadius.dp,
    )
