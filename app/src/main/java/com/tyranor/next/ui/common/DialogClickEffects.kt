package com.tyranor.next.ui.common

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.TextColor

/** 无涟漪/按压反馈占位：容器内组件的点击效果统一失效。 */
object NoIndication : IndicationNodeFactory {
    private val node = object : Modifier.Node() {}
    override fun create(interactionSource: InteractionSource): DelegatableNode = node
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 弹窗文本按钮：共用无涟漪的按钮反馈；取消位由弹窗决定何时交付点击动作。 */
@Composable
fun DialogTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 可用态文字色；破坏性动作（删除/清除）传 `MaterialTheme.colorScheme.error`。 */
    contentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val dialogExit = LocalDialogExit.current
    val targetColor = if (enabled) {
        contentColor
    } else {
        TextColor.copy(alpha = 0.38f)
    }
    NoRippleActionText(text, modifier, enabled, targetColor, verticalPadding = 10.dp, colorLabel = "dialogButtonColor") {
        // 取消类按钮：弹窗先平滑退场再执行（增强动效档，见 AppAlertDialog）
        if (dialogExit != null) dialogExit.closeThen(onClick) else onClick()
    }
}
