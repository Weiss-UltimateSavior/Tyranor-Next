package com.tyranor.next.ui.settings

import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.ExpandVisibility
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionHaptics
import androidx.compose.ui.layout.layoutId
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tyranor.next.R
import com.tyranor.next.core.settings.GraphicsFilterCatalog
import com.tyranor.next.core.settings.GraphicsNode
import com.tyranor.next.core.settings.GraphicsSettingsCodec
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.NoRippleTextButton
import kotlinx.coroutines.launch
import java.util.UUID

/** Transient keys distinguish duplicate nodes without adding UI identity to persisted settings. */
private data class EditorNode(val key: String, val node: GraphicsNode)

/**
 * 编辑滤镜链的顺序与节点参数；临时身份区分重复滤镜，不写入持久化设置。
 * 长按拖拽先预览顺序，松手提交，取消恢复原顺序；拖拽期间禁用相互冲突的节点操作。
 */
@Composable
internal fun GraphicsChainEditor(chain: List<GraphicsNode>, enabled: Boolean, onChange: (List<GraphicsNode>) -> Unit) {
    val labels = graphicsFilterOptions().toMap()
    var entries by remember(chain) { mutableStateOf(chain.map { EditorNode(UUID.randomUUID().toString(), it) }) }
    val originalKeys = remember(chain) { entries.map { it.key } }
    var dragging by remember(chain) { mutableStateOf<String?>(null) }
    var offset by remember(chain) { mutableFloatStateOf(0f) }
    var heights = remember(chain) { mutableMapOf<String, Int>() }
    var editingScale by remember(chain, enabled) { mutableStateOf<String?>(null) }
    var editingParameters by remember(chain, enabled) { mutableStateOf<String?>(null) }
    val currentOnChange by rememberUpdatedState(onChange)
    val scope = rememberCoroutineScope()
    // 动效身份（仅「增强」档用于组合 key / 让位动画）：提交前后的同一节点沿用同一身份，
    // 临时 key 与拖拽逻辑保持原样（每次提交仍重建）
    val motionIds = remember { ChainMotionIds() }
    val motionKeys = remember(chain) { motionIds.assign(entries) }
    // 首次组合之后才出现的行（新增 / 换链）从小、透明处 Q 弹长出；页面首屏的行交给页面入场
    val editorShown = remember { booleanArrayOf(false) }
    SideEffect { editorShown[0] = true }
    val enhanced = MotionMode.enhanced
    val dividerAlpha = graphicsChainActionAlpha(enabled).line
    fun commit(next: List<EditorNode>) {
        motionIds.committed = next
        currentOnChange(next.map { it.node })
    }
    Column {
        ExpandVisibility(chain.isEmpty()) {
            Text(stringResource(R.string.graphics_chain_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        GraphicsChainStack(
            pinned = dragging?.let { motionKeys[it] },
            onMeasured = { id, height ->
                entries.firstOrNull { motionKeys[it.key] == id }?.let { heights[it.key] = height }
            },
        ) {
        entries.forEachIndexed { index, entry ->
            val motionKey = motionKeys[entry.key] ?: entry.key
            key(if (enhanced) motionKey else entry.key) {
                val node = entry.node
                val reveal = remember { BringIntoViewRequester() }
                val moving = dragging == entry.key
                val rowMotion = rememberGraphicsChainRowMotion(editorShown[0], moving, scope)
                Row(
                    modifier = Modifier
                        .then(if (enhanced) Modifier.layoutId(motionKey) else Modifier)
                        .fillMaxWidth()
                        .onSizeChanged { heights[entry.key] = it.height }
                        .bringIntoViewRequester(reveal)
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = if (moving) offset else 0f }
                        .then(rowMotion?.layer(moving) ?: Modifier)
                        .then(if (moving) Modifier.clip(AppComponentShape).background(NavWhite).glassBorder() else Modifier)
                        .pointerInput(entry.key, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragging = entry.key; offset = 0f
                                    MotionHaptics.perform(MotionHaptics.Kind.LongPress)
                                },
                                onDragEnd = { rowMotion?.settle(offset); commit(entries); dragging = null; offset = 0f },
                                onDragCancel = {
                                    rowMotion?.settle(offset)
                                    entries = entries.sortedBy { original -> chain.indices.firstOrNull {
                                        // Cancel restores the original keyed order, including identical nodes.
                                        original.key == originalKeys[it]
                                    } ?: Int.MAX_VALUE }
                                    dragging = null; offset = 0f
                                },
                                onDrag = { change, distance ->
                                    change.consume()
                                    offset += distance.y
                                    var position = entries.indexOfFirst { it.key == entry.key }
                                    while (position >= 0) {
                                        val direction = if (offset > 0) 1 else -1
                                        val target = position + direction
                                        if (target !in entries.indices) break
                                        val ownHeight = heights[entry.key] ?: break
                                        val otherHeight = heights[entries[target].key] ?: break
                                        if (kotlin.math.abs(offset) <= (ownHeight + otherHeight) / 2f) break
                                        entries = entries.toMutableList().apply { add(target, removeAt(position)) }
                                        MotionHaptics.perform(MotionHaptics.Kind.Tick)
                                        offset -= direction * otherHeight
                                        position = target
                                        scope.launch { reveal.bringIntoView() }
                                    }
                                },
                            )
                        }.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels.getValue(node.id), style = MaterialTheme.typography.titleMedium,
                        color = TextColor.copy(alpha = if (node.enabled) 1f else 0.38f),
                        modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (GraphicsFilterCatalog.scaleAdjustable(node.id)) {
                            GraphicsNodeAction(
                                R.drawable.ic_graphics_scale,
                                R.string.graphics_filter_scale_settings,
                                enabled && dragging == null
                            ) { editingScale = entry.key }
                        }
                        if (GraphicsFilterCatalog.parametersAdjustable(node.id)) {
                            GraphicsNodeAction(
                                R.drawable.ic_graphics_parameters,
                                R.string.graphics_filter_parameters,
                                enabled && dragging == null
                            ) { editingParameters = entry.key }
                        }
                        GraphicsNodeAction(
                            R.drawable.ic_graphics_delete,
                            R.string.graphics_chain_remove,
                            enabled && dragging == null
                        ) {
                            commit(entries.filter { it.key != entry.key })
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .width(1.dp)
                                .height(20.dp)
                                .background(TextColor.copy(alpha = dividerAlpha))
                        )
                        GraphicsNodeAction(
                            R.drawable.ic_graphics_up,
                            R.string.graphics_chain_up,
                            enabled && index > 0 && dragging == null
                        ) {
                            val position = entries.indexOfFirst { it.key == entry.key }
                            if (position > 0) commit(entries.toMutableList().apply { add(position - 1, removeAt(position)) })
                        }
                        GraphicsNodeAction(
                            R.drawable.ic_graphics_down,
                            R.string.graphics_chain_down,
                            enabled && index < entries.lastIndex && dragging == null
                        ) {
                            val position = entries.indexOfFirst { it.key == entry.key }
                            if (position >= 0 && position < entries.lastIndex)
                                commit(entries.toMutableList().apply { add(position + 1, removeAt(position)) })
                        }
                    }
                }
            }
        }
        }
        GraphicsFilterPicker(labels.toList(), enabled = enabled && dragging == null && chain.size < GraphicsSettingsCodec.MAX_NODES) {
            currentOnChange(chain + GraphicsNode(it))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            NoRippleTextButton(stringResource(R.string.graphics_chain_clear), enabled = enabled && dragging == null && chain.isNotEmpty()) {
                currentOnChange(emptyList())
            }
        }
        Text(stringResource(R.string.graphics_chain_limit, GraphicsSettingsCodec.MAX_NODES),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    entries.firstOrNull { it.key == editingScale }?.let { entry ->
        GraphicsNodeScale(entry.node, onDismiss = { editingScale = null }) { value ->
            commit(entries.map { if (it.key == entry.key) it.copy(node = value) else it })
            editingScale = null
        }
    }
    entries.firstOrNull { it.key == editingParameters }?.let { entry ->
        GraphicsNodeParameters(entry.node, onDismiss = { editingParameters = null }) { value ->
            commit(entries.map { if (it.key == entry.key) it.copy(node = value) else it })
            editingParameters = null
        }
    }
}

/** 节点工具按钮统一承载禁用态和无涟漪点击，按压形变与静止描边分别遵循动效和外观设置。 */
@Composable
private fun GraphicsNodeAction(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(label)
    // 可用 ↔ 不可用（拖拽开始 / 结束、移到首尾、切换继承）：描边与图标透明度弹簧渐变，「普通」档直接切换
    val alpha = graphicsChainActionAlpha(enabled)
    Box(
        modifier = Modifier
            .pressSquish(enabled = enabled)
            .size(40.dp)
            .clip(AppComponentShape)
            // 默认风格描边取动态常量 TextColor（符合 AGENTS 规范，使用 Color.kt 动态常量）；
            // 玻璃风格的 0.5dp 发丝描边由 glassBorder 负责，两种风格不叠加。
            .then(
                if (AppThemeColors.isGlass) Modifier
                else Modifier.border(1.dp, TextColor.copy(alpha = alpha.line), AppComponentShape)
            )
            .glassBorder(AppComponentShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = TextColor.copy(alpha = alpha.icon)
        )
    }
}

/**
 * 动效身份：滤镜链每次提交都会重建临时 key（拖拽 / 取消逻辑依赖它，保持原样），这里另给「同一个节点」
 * 一个跨提交不变的组合身份，使上移 / 下移 / 删除 / 新增时其余行保留组合并平滑让位。不写入持久化。
 */
private class ChainMotionIds {
    /** 编辑器自己最近一次提交的有序列表（旧临时 key）：新链与之逐项相同时按位置继承身份。 */
    var committed: List<EditorNode>? = null
    private var ids: Map<String, String> = emptyMap()
    private var last: List<EditorNode> = emptyList()

    /** 为新链的临时 key 分配动效身份；外部变化（新增 / 清空 / 换链）按节点内容逐个认领旧身份。 */
    fun assign(entries: List<EditorNode>): Map<String, String> {
        val pending = committed
        committed = null
        val next = HashMap<String, String>(entries.size)
        if (pending != null && pending.size == entries.size && pending.indices.all { pending[it].node == entries[it].node }) {
            entries.forEachIndexed { i, entry -> next[entry.key] = ids[pending[i].key] ?: entry.key }
        } else {
            val pool = last.toMutableList()
            entries.forEach { entry ->
                val i = pool.indexOfFirst { it.node == entry.node }
                next[entry.key] = if (i >= 0) ids[pool.removeAt(i).key] ?: entry.key else entry.key
            }
        }
        ids = next
        last = entries
        return next
    }
}
