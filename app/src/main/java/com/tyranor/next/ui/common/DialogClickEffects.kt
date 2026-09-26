package com.tyranor.next.ui.common

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentShape

/**
 * 弹窗点击反馈规范（AGENT.md「弹窗点击反馈统一规范」）：
 * 弹窗内的所有按钮与组件不得使用任何点击/按压反馈效果（涟漪、缩放、高亮等）。
 */

/** 无涟漪/按压反馈占位：容器内组件的点击效果统一失效。 */
object NoIndication : IndicationNodeFactory {
    private val node = object : Modifier.Node() {}
    override fun create(interactionSource: InteractionSource): DelegatableNode = node
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 弹窗文本按钮：无涟漪/按压效果（`indication = null`），颜色随可用态变化。 */
@Composable
fun DialogTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = Modifier
            .clip(AppComponentShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
