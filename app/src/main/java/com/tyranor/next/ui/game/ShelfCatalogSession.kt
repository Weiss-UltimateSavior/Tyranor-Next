package com.tyranor.next.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * 藏书阁开合会话的纯逻辑状态机（含单调递增的打开序号 [openSequence]）。
 *
 * 点选 / 合上藏书阁后要演一段退场动画，覆盖层在动画放完、交付收尾（[dismiss]）之前一直都在。退场期间触摸已经递给下面的书架
 * （见 `MainOverlayShareTouches`），用户可能这时立刻再长按书架想重新进藏书阁——长按成立（约 0.4 秒）时恰好踩在退场收尾前后：
 * 此刻 [isOpen] 还是 true，若当成「已经开着」丢掉，或让随后到来的收尾把它关掉，这次长按就被吞了（用户感觉是「没反应，松手再长按才行」）。所以：
 * - [requestOpen]：没开着就直接开；旧覆盖层还没收尾时只登记要重开的锚点；
 * - [dismiss]：旧覆盖层交付收尾时，有登记就立刻接着开新一轮（[openSequence] +1，宿主拿序号当 `key`，旧实例整个销毁换新实例），
 *   携带过期序号的收尾回调一律忽略，不会把刚重开的覆盖层误关。
 *
 * 不让新旧两个覆盖层同时存在：它们共用同一个 `ShelfCatalogStage`（`release()`、退场打断登记都在上面），
 * 旧实例晚到的收尾会清掉新实例刚登记的状态，所以重开一定等旧实例交付之后。
 */
internal class ShelfCatalogSession(
    initialOpen: Boolean = false,
    initialSequence: Long = if (initialOpen) 1L else 0L,
    initialAnchorUri: String? = null,
    initialPendingAnchorUri: String? = null,
) {
    var isOpen: Boolean = initialOpen
        private set
    var openSequence: Long = initialSequence
        private set
    var anchorUri: String? = if (initialOpen) initialAnchorUri else null
        private set
    var pendingAnchorUri: String? = if (initialOpen) initialPendingAnchorUri else null
        private set

    /**
     * 书架上的长按要进藏书阁。
     *
     * @return true：这次直接打开了（调用方此刻记下锚点）；false：旧覆盖层还在收尾，已登记，等它交付后由 [dismiss] 返回锚点重开。
     */
    fun requestOpen(anchorUri: String): Boolean {
        if (!isOpen) {
            isOpen = true
            openSequence += 1L
            this.anchorUri = anchorUri
            pendingAnchorUri = null
            return true
        }
        pendingAnchorUri = anchorUri
        return false
    }

    /**
     * 覆盖层交付收尾。
     *
     * @return 需要立刻重开的锚点；null 表示不重开（已经关闭，或这是旧实例晚到的过期回调）。
     */
    fun dismiss(sequence: Long): String? {
        if (!isOpen || sequence != openSequence) return null
        val reopenAnchor = pendingAnchorUri
        pendingAnchorUri = null
        if (reopenAnchor == null) {
            isOpen = false
            anchorUri = null
            return null
        }
        anchorUri = reopenAnchor
        openSequence += 1L
        return reopenAnchor
    }

    /** 外部原因（换了卡片风格、游戏库清空）直接关掉，并丢弃登记的重开。@return 是否真的从开着变成关着。 */
    fun closeNow(): Boolean {
        pendingAnchorUri = null
        anchorUri = null
        if (!isOpen) return false
        isOpen = false
        return true
    }
}

/**
 * [ShelfCatalogSession] 的 Compose 宿主状态：把打开态、序号与锚点同步成可观察状态，并随界面重建保存。
 *
 * 若退场期间已登记重开锚点（[pendingAnchorUri]）随后发生配置变更或进程重建，旧退场覆盖层已随重建销毁、
 * 不会再回调 `onDismiss`；因此 [Saver] 恢复时直接把待重开锚点晋升为新一轮打开会话（`openSequence + 1`），
 * 保证重建后无需手动 `dismiss` 即可自动以新锚点打开。
 */
@Stable
internal class ShelfCatalogSessionState(
    initialOpen: Boolean = false,
    initialSequence: Long = if (initialOpen) 1L else 0L,
    initialAnchorUri: String? = null,
    initialPendingAnchorUri: String? = null,
) {
    private val session = ShelfCatalogSession(
        initialOpen = initialOpen,
        initialSequence = initialSequence,
        initialAnchorUri = initialAnchorUri,
        initialPendingAnchorUri = initialPendingAnchorUri,
    )
    var isOpen by mutableStateOf(session.isOpen)
        private set
    var openSequence by mutableLongStateOf(session.openSequence)
        private set
    var anchorUri by mutableStateOf(session.anchorUri)
        private set
    val pendingAnchorUri: String? get() = session.pendingAnchorUri

    private fun sync() {
        isOpen = session.isOpen
        openSequence = session.openSequence
        anchorUri = session.anchorUri
    }

    fun requestOpen(anchorUri: String): Boolean = session.requestOpen(anchorUri).also { sync() }

    fun dismiss(sequence: Long): String? = session.dismiss(sequence).also { sync() }

    fun closeNow() {
        if (session.closeNow()) sync()
    }

    companion object {
        val Saver = listSaver<ShelfCatalogSessionState, Any?>(
            save = { listOf(it.isOpen, it.openSequence, it.anchorUri, it.pendingAnchorUri) },
            restore = { saved ->
                val savedOpen = saved.getOrNull(0) as? Boolean ?: false
                val savedSequence = (saved.getOrNull(1) as? Number)?.toLong() ?: 0L
                val savedAnchor = saved.getOrNull(2) as? String
                val savedPending = saved.getOrNull(3) as? String
                if (savedOpen && savedPending != null) {
                    // 旧退场实例已随重建销毁，直接消费待重开锚点晋升为新一轮会话，恢复后即自动以新锚点打开
                    ShelfCatalogSessionState(
                        initialOpen = true,
                        initialSequence = savedSequence + 1L,
                        initialAnchorUri = savedPending,
                        initialPendingAnchorUri = null,
                    )
                } else {
                    ShelfCatalogSessionState(
                        initialOpen = savedOpen,
                        initialSequence = savedSequence,
                        initialAnchorUri = savedAnchor,
                        initialPendingAnchorUri = null,
                    )
                }
            },
        )
    }
}

@Composable
internal fun rememberShelfCatalogSessionState(): ShelfCatalogSessionState =
    rememberSaveable(saver = ShelfCatalogSessionState.Saver) { ShelfCatalogSessionState() }
