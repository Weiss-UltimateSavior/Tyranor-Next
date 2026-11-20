package com.tyranor.next.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import org.junit.Test

class NoIndicationTest {
    @Test
    fun multipleControlsCanDelegateIndicationsWithoutSharingNodes() {
        val sharedSource = MutableInteractionSource()
        // 模拟多个分类标签；即使交互源相同，也必须允许不同控件同时持有反馈节点。
        val sources = listOf(sharedSource, sharedSource, MutableInteractionSource())
        sources.map { source ->
            IndicationOwner(NoIndication.create(source))
        }
    }

    private class IndicationOwner(indication: DelegatableNode) : DelegatingNode() {
        init {
            delegate(indication)
        }
    }
}
