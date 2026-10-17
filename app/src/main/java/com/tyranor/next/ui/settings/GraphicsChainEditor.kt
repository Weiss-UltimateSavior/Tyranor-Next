package com.tyranor.next.ui.settings

import androidx.compose.foundation.background
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
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.NoRippleTextButton
import kotlinx.coroutines.launch
import java.util.UUID

/** Transient keys distinguish duplicate nodes without adding UI identity to persisted settings. */
private data class EditorNode(val key: String, val node: GraphicsNode)

@Composable
internal fun GraphicsChainEditor(chain: List<GraphicsNode>, enabled: Boolean, onChange: (List<GraphicsNode>) -> Unit) {
    val labels = graphicsFilterOptions().toMap()
    var entries by remember(chain) { mutableStateOf(chain.map { EditorNode(UUID.randomUUID().toString(), it) }) }
    val originalKeys = remember(chain) { entries.map { it.key } }
    var dragging by remember(chain) { mutableStateOf<String?>(null) }
    var offset by remember(chain) { mutableFloatStateOf(0f) }
    val heights = remember(chain) { mutableMapOf<String, Int>() }
    var editing by remember(chain, enabled) { mutableStateOf<String?>(null) }
    val currentOnChange by rememberUpdatedState(onChange)
    val scope = rememberCoroutineScope()
    fun commit(next: List<EditorNode>) { currentOnChange(next.map { it.node }) }
    Column {
        if (chain.isEmpty()) {
            Text(stringResource(R.string.graphics_chain_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        entries.forEachIndexed { index, entry ->
            key(entry.key) {
                val node = entry.node
                val reveal = remember { BringIntoViewRequester() }
                val moving = dragging == entry.key
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .onSizeChanged { heights[entry.key] = it.height }
                        .bringIntoViewRequester(reveal)
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = if (moving) offset else 0f }
                        .then(if (moving) Modifier.clip(AppComponentShape).background(NavWhite).glassBorder() else Modifier)
                        .pointerInput(entry.key, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = entry.key; offset = 0f },
                                onDragEnd = { commit(entries); dragging = null; offset = 0f },
                                onDragCancel = {
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
                    if (GraphicsFilterCatalog.adjustable(node.id)) {
                        GraphicsNodeAction(R.drawable.ic_graphics_parameters, R.string.graphics_filter_parameters,
                            enabled && dragging == null) { editing = entry.key }
                    }
                    GraphicsNodeAction(R.drawable.ic_graphics_delete, R.string.graphics_chain_remove, enabled && dragging == null) {
                        commit(entries.filter { it.key != entry.key })
                    }
                    GraphicsNodeAction(R.drawable.ic_graphics_up, R.string.graphics_chain_up, enabled && index > 0 && dragging == null) {
                        val position = entries.indexOfFirst { it.key == entry.key }
                        if (position > 0) commit(entries.toMutableList().apply { add(position - 1, removeAt(position)) })
                    }
                    GraphicsNodeAction(R.drawable.ic_graphics_down, R.string.graphics_chain_down,
                        enabled && index < entries.lastIndex && dragging == null) {
                        val position = entries.indexOfFirst { it.key == entry.key }
                        if (position >= 0 && position < entries.lastIndex)
                            commit(entries.toMutableList().apply { add(position + 1, removeAt(position)) })
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
    entries.firstOrNull { it.key == editing }?.let { entry ->
        GraphicsNodeParameters(entry.node, onDismiss = { editing = null }) { value ->
            commit(entries.map { if (it.key == entry.key) it.copy(node = value) else it })
            editing = null
        }
    }
}

@Composable
private fun GraphicsNodeAction(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(label)
    Box(Modifier.size(40.dp).clickable(enabled = enabled,
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp),
            tint = TextColor.copy(alpha = if (enabled) 1f else 0.38f))
    }
}
