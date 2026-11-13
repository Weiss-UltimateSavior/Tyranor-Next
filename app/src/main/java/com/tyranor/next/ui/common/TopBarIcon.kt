package com.tyranor.next.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.pressSquish
import androidx.compose.animation.animateColorAsState
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionTokens
import com.tyranor.next.ui.common.motion.staggeredEntrance

/**
 * 顶部栏图标的入场次序上限：实际延迟取它与屏幕次序（挂载先后）的较小者，
 * 因此标题先到、多个图标从左到右依次跟上，最多晚 5 个节拍。
 */
private const val TOP_BAR_ICON_ENTRANCE_INDEX = 5

/**
 * 顶部栏图标按钮统一规范（排版对齐游戏页顶部栏）：
 * Image 渲染 + 34dp 触控区 + 6dp 圆角裁剪 + clickable + 4dp 内边距；
 * 左侧自带 2dp 间距，保证相邻图标之间留白统一。
 * tint 由调用方传当前主题色（Material 页面传 MaterialTheme.colorScheme.primary，
 * Miuix 页面传 MiuixTheme.colorScheme.primary）。
 * 全 App 顶部栏右侧操作图标按钮必须使用本组件，禁止自行用 IconButton/Icon 拼装。
 */
@Composable
fun TopBarIcon(
    painter: Painter,
    contentDescription: String?,
    tint: Color,
    stateDescription: String? = null,
    /** 刷新类图标传 true：点击时图标转一圈（带一点回弹，连点每次再转一圈、从当前角度续上），到位后与原样一致。「普通」档不转。 */
    spin: Boolean = false,
    onClick: () -> Unit,
) {
    val spinAngle = remember { Animatable(0f) }
    val spinScope = rememberCoroutineScope()
    // 着色变化（色调轮盘换主题色、调用方切换强调态）：「增强」档弹簧淡变，「普通」档直接切换
    val shownTint = if (MotionMode.enhanced) {
        animateColorAsState(tint, MotionTokens.fadeIn(), label = "topBarIconTint").value
    } else {
        tint
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(shownTint),
        modifier = Modifier
            .then(
                if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription }
                else Modifier,
            )
            .padding(start = 2.dp)
            // 页面入场：紧随标题之后从下方轻轻弹入（仅在页面提供了入场节拍时生效；停稳与原样一致）
            .staggeredEntrance(TOP_BAR_ICON_ENTRANCE_INDEX, distance = TopBarEntranceDistance)
            // Q 弹按压：只改绘制图层，静止时与原样一致
            .pressSquish()
            .size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                onClick = {
                    if (spin && MotionMode.enhanced) {
                        // 目标始终是 360° 的整数倍：每次点击再转一圈；转完无声归零（整圈角度视觉一致，不会跳）
                        spinScope.launch {
                            spinAngle.animateTo(spinAngle.targetValue + 360f, MotionTokens.layout(0.5f))
                            spinAngle.snapTo(0f)
                        }
                    }
                    onClick()
                },
            )
            .padding(4.dp)
            .then(if (spin && MotionMode.enhanced) Modifier.graphicsLayer { rotationZ = spinAngle.value } else Modifier),
    )
}
