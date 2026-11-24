package com.tyranor.next.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
internal class MainOverlayEntry(
    val id: Long,
    initialContent: @Composable () -> Unit,
) {
    var content by mutableStateOf(initialContent)
}

@Stable
internal class MainOverlayHost {
    private var nextId = 1L
    private val entries = mutableStateListOf<MainOverlayEntry>()
    val stack: List<MainOverlayEntry> get() = entries
    val active: Boolean get() = entries.isNotEmpty()

    internal fun allocate(initialContent: @Composable () -> Unit): MainOverlayEntry =
        MainOverlayEntry(nextId++, initialContent)

    internal fun attach(entry: MainOverlayEntry) {
        if (entries.none { it.id == entry.id }) entries.add(entry)
    }

    internal fun detach(entry: MainOverlayEntry) {
        entries.removeAll { it.id == entry.id }
    }
}

internal val LocalMainOverlayHost = staticCompositionLocalOf<MainOverlayHost?> { null }

/** 将全屏交互覆盖层挂载到主界面根窗口顶层，与底层页面共享同一 Window 与帧时钟。 */
@Composable
internal fun MainOverlayPortal(content: @Composable () -> Unit) {
    val host = LocalMainOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val entry = remember(host) { host.allocate(content) }
    SideEffect { entry.content = content }
    DisposableEffect(host, entry) {
        host.attach(entry)
        onDispose { host.detach(entry) }
    }
}
