package com.tyranor.next.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/**
 * 去除点击涟漪的包裹组件（主题层公共修饰符）。
 *
 * 原先位于 `ui/common`，导致 `theme → ui.common` 反向依赖形成包循环（m6）；
 * 现下沉到 theme 包，依赖保持单向 `ui → theme`。
 */
@Composable
fun WithoutPressIndication(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides NoPressIndication) {
        content()
    }
}

private object NoPressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoPressIndicationNode()

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other === this

    private class NoPressIndicationNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }
}
