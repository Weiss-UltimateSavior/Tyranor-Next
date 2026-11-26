package com.tyranor.next.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex

@Stable
internal class MainOverlayEntry(
    val id: Long,
    private val content: State<@Composable () -> Unit>,
) {
    /** 覆盖层正在收场、要把触摸一并交给它下面的页面（由 [MainOverlayShareTouches] 设置，宿主据此挂 [shareTouchesWithSiblings]）。 */
    var sharesTouches by mutableStateOf(false)

    @Composable
    fun Content() {
        CompositionLocalProvider(LocalMainOverlayEntry provides this) {
            content.value.invoke()
        }
    }
}

@Stable
internal class MainOverlayHost {
    private var nextId = 1L
    private val entries = mutableStateListOf<MainOverlayEntry>()
    val stack: List<MainOverlayEntry> get() = entries
    val active: Boolean get() = entries.isNotEmpty()

    internal fun allocate(content: State<@Composable () -> Unit>): MainOverlayEntry =
        MainOverlayEntry(nextId++, content)

    internal fun attach(entry: MainOverlayEntry) {
        if (entries.none { it.id == entry.id }) entries.add(entry)
    }

    internal fun detach(entry: MainOverlayEntry) {
        entries.removeAll { it.id == entry.id }
    }
}

internal val LocalMainOverlayHost = staticCompositionLocalOf<MainOverlayHost?> { null }

/** 覆盖层内容读取自己所在的挂载条目（不经宿主挂载、直接内联组合时为 null）。 */
private val LocalMainOverlayEntry = compositionLocalOf<MainOverlayEntry?> { null }

/** 在主界面根容器顶层渲染所有已挂载的全屏覆盖层，并在退场期间按 [MainOverlayEntry.sharesTouches] 共享触摸给下层页面。 */
@Composable
internal fun MainOverlayLayer(host: MainOverlayHost) {
    if (!host.active) return
    host.stack.forEach { entry ->
        key(entry.id) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(10f)
                    // 覆盖层收场期间把触摸一并交给下面的页面（见 MainOverlayShareTouches）
                    .then(if (entry.sharesTouches) Modifier.shareTouchesWithSiblings() else Modifier),
            ) {
                entry.Content()
            }
        }
    }
}

/** 将全屏交互覆盖层挂载到主界面根窗口顶层，与底层页面共享同一 Window 与帧时钟。 */
@Composable
internal fun MainOverlayPortal(content: @Composable () -> Unit) {
    val host = LocalMainOverlayHost.current
    if (host == null) {
        content()
        return
    }
    key(host) {
        val currentContent = rememberUpdatedState(content)
        val entry = remember { host.allocate(currentContent) }
        DisposableEffect(entry) {
            host.attach(entry)
            onDispose { host.detach(entry) }
        }
    }
}

/**
 * 覆盖层收场期间让手指碰到的下层页面照常收到触摸。
 *
 * 把覆盖层子树的布局尺寸报成 0 并不能挡住或放行命中测试：没有裁剪图层时，命中测试只按各个带触摸输入的节点自己的范围判断，
 * 零尺寸的父节点拦不住子孙节点，覆盖层照样把手指吃掉。所以改由宿主在覆盖层这一层挂 [shareTouchesWithSiblings]：
 * 覆盖层照常被命中，同时同一次触摸继续交给压在它下面的同级（页面）。覆盖层里自己的控件须在 [enabled] 期间不再响应。
 * 离开组合或 [enabled] 变回 false 时立即取消共享。
 */
@Composable
internal fun MainOverlayShareTouches(enabled: Boolean) {
    val entry = LocalMainOverlayEntry.current ?: return
    DisposableEffect(entry, enabled) {
        entry.sharesTouches = enabled
        onDispose { entry.sharesTouches = false }
    }
}

/** 本节点被命中时，触摸同时交给它下面的同级节点（Compose 的 `sharePointerInputWithSiblings`），自己不消费任何事件。 */
internal fun Modifier.shareTouchesWithSiblings(): Modifier = this then ShareTouchesWithSiblingsElement

private data object ShareTouchesWithSiblingsElement : ModifierNodeElement<ShareTouchesWithSiblingsNode>() {
    override fun create() = ShareTouchesWithSiblingsNode()

    override fun update(node: ShareTouchesWithSiblingsNode) = Unit
}

private class ShareTouchesWithSiblingsNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit

    override fun onCancelPointerInput() = Unit

    override fun sharePointerInputWithSiblings(): Boolean = true
}
