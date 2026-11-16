package com.tyranor.next.ui.common.motion

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.DefaultCameraDistance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * 绘制阶段图层的参数：旋转（绕竖轴）/ 位移 / 缩放、透视距离、枢轴（占图层宽高的比例）与渲染效果；默认值 = 什么都不做。
 * [width] / [height] 是输入（图层当前尺寸，px），其余是输出。
 */
internal class LayerParams {
    var width = 0f
    var height = 0f

    var rotationY = 0f
    var translationX = 0f
    var translationY = 0f
    var scaleX = 1f
    var scaleY = 1f
    var cameraDistance = DefaultCameraDistance
    var pivotFracX = 0.5f
    var pivotFracY = 0.5f
    var renderEffect: RenderEffect? = null

    // 只有所有输出都回到初值才允许绕过这一层（尤其不能丢掉只有褪色的层）。
    internal val isDefault: Boolean
        get() = rotationY == 0f && translationX == 0f && translationY == 0f &&
            scaleX == 1f && scaleY == 1f && cameraDistance == DefaultCameraDistance &&
            pivotFracX == 0.5f && pivotFracY == 0.5f && renderEffect == null

    fun reset() {
        rotationY = 0f
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        cameraDistance = DefaultCameraDistance
        pivotFracX = 0.5f
        pivotFracY = 0.5f
        renderEffect = null
    }
}

/** 一层变换：读取快照状态，把当前这一帧的参数写进 [LayerParams]（调用前已 reset，没有效果就什么也不写）。 */
internal fun interface LayerSource {
    fun compute(p: LayerParams, density: Float)
}

private val NoLayerBlock: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {}

/**
 * 用**绘制阶段**的图层实现一串整页变换（翻折、翻页位移 / 缩放），代替每层一个 `Modifier.graphicsLayer { rotationX/Y、translationX、scaleX … }`。
 *
 * 为什么：Compose 对「位置类图层属性」（缩放 / 位移 / 旋转 / 透视 / 枢轴）有额外的账——属性一变，就要把这个节点**整棵子树**
 * 的包围盒顺着每层变换重新算一遍（`RectManager.insertOrUpdateTransformedNodeSubhierarchy`）。这些变换恰好挂在整页内容上
 * （几百个节点），动画期间每帧一次，真机 debug 包上抽屉打开 / 页面推入的每一帧要多花约 3ms，占主线程 20%~27%
 * （透明度、渲染效果、裁剪不是位置类属性，不触发）。
 * 这里的图层是自己持有的 [GraphicsLayer]：Compose 只看见一个恒等的外层图层，子树不用重算；
 * 旋转 / 位移 / 缩放 / 透视距离 / 枢轴 / 渲染效果仍使用原来的 RenderNode 属性。
 *
 * 运行方式：Compose 的内容绘制失效时才重录内容层；进度更新只改属性，中间层进出时只重录指向子层的绘制指令。
 * 外面包一层恒等的 `graphicsLayer {}`，让 Compose 把这棵子树的显示列表缓存住——
 * 否则别处触发的整窗重绘会让这里每次都重新录制。
 *
 * 取舍：命中测试不再跟着变换（翻折只有十几度，且翻折期间页面总被遮罩 / 新窗口盖住；二级页入场翻折在滑入的前半段才有明显倾角；
 * 主界面切页期间页面本来就不接收触摸）。**只用在整页容器上**：小控件的变换子树本来就小，没有这个开销，也不值得多一层 RenderNode。
 * 录制必须用 DrawScope 里的成员扩展 `GraphicsLayer.record(size) { drawContent() }`（见 [DrawPhaseNode.draw]）。
 *
 * @param sources 从**内到外**的变换层；个数在同一个调用点固定。
 */
internal fun Modifier.drawPhaseLayers(key: Any, vararg sources: LayerSource): Modifier =
    this.graphicsLayer(NoLayerBlock).then(DrawPhaseElement(key, sources))

/**
 * 同 [drawPhaseLayers]，但调用处在它外面已经有自己的（非位置类的）`graphicsLayer`（如透明度层），不再多包一层恒等图层。
 */
internal fun Modifier.drawPhaseLayersInOwnedLayer(key: Any, vararg sources: LayerSource): Modifier =
    this.then(DrawPhaseElement(key, sources))

private fun Array<out LayerSource>.sameSources(other: Array<out LayerSource>): Boolean {
    if (this === other) return true
    if (size != other.size) return false
    for (i in indices) if (this[i] !== other[i]) return false
    return true
}

private class DrawPhaseElement(
    private val key: Any,
    private val sources: Array<out LayerSource>,
) : ModifierNodeElement<DrawPhaseNode>() {
    override fun create() = DrawPhaseNode(sources)

    override fun update(node: DrawPhaseNode) {
        node.update(sources)
    }

    override fun equals(other: Any?): Boolean = other is DrawPhaseElement &&
        other.key == key && sources.sameSources(other.sources)
    override fun hashCode(): Int {
        var hash = key.hashCode()
        for (source in sources) hash = 31 * hash + System.identityHashCode(source)
        return hash
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "drawPhaseLayers"
    }
}

// 一个 UI 线程共用一个 apply observer。只有首个绑定和最后一个解绑才 start / stop。
private val threadSourceReads = ThreadLocal.withInitial {
    val handler = Handler(checkNotNull(Looper.myLooper()))
    LayerSourceReads { notify ->
        if (Looper.myLooper() === handler.looper) notify() else handler.post(notify)
    }
}

internal class LayerSourceReads(dispatch: (() -> Unit) -> Unit) {
    private val observer = SnapshotStateObserver(dispatch)
    private var clients = 0
    private val changed: (Binding) -> Unit = { it.deliver() }

    fun bind(listener: () -> Unit): Binding {
        if (clients++ == 0) observer.start()
        return Binding(listener)
    }

    inner class Binding internal constructor(private var listener: (() -> Unit)?) {
        fun observe(read: () -> Unit) {
            // 外层可能是 Compose 的 draw 或组合观察：进度只能记入本绑定。
            Snapshot.withoutReadObservation { observer.observeReads(this, changed, read) }
        }

        internal fun deliver() { listener?.invoke() }

        fun close() {
            if (listener == null) return
            listener = null // clear 之前已经排队的通知也不能交给下一组来源。
            observer.clear(this) // 同一快照重复 observeReads {} 不保证忘掉旧依赖。
            if (--clients == 0) observer.stop()
        }
    }
}

private class DrawPhaseNode(
    private var sources: Array<out LayerSource>,
) : Modifier.Node(), DrawModifierNode {
    // 来源替换只需重新订阅。若让 Element.update 自动 invalidateDraw，整页内容也会重录。
    override val shouldAutoInvalidate: Boolean get() = false

    private class Slot {
        val params = LayerParams()
        var layer: GraphicsLayer? = null
        var recordedChild: GraphicsLayer? = null
        var wasDefault = true
    }

    private var slots = Array(sources.size) { Slot() }
    private var drawSize = Size.Unspecified
    private var pixelsPerDp = 0f
    private val intSize get() = IntSize(drawSize.width.toInt(), drawSize.height.toInt())

    private val refresh = ::refreshLayers
    private var binding: LayerSourceReads.Binding? = null

    private fun bindSources() {
        val old = binding
        // 先取得新绑定，再关闭旧绑定：替换唯一使用者时也不重启共享观察器。
        binding = if (sources.isEmpty()) null else threadSourceReads.get()!!.bind(refresh)
        old?.close()
    }

    // observeReads 不内联。复用回调，在块外累计 changed，避免每次进度通知都分配闭包和 BooleanRef。
    private val readSources: () -> Unit = {
        for (i in sources.indices) {
            val p = slots[i].params
            p.reset()
            p.width = drawSize.width
            p.height = drawSize.height
            sources[i].compute(p, pixelsPerDp)
        }
    }

    private fun layerFor(slot: Slot): GraphicsLayer =
        slot.layer ?: requireGraphicsContext().createGraphicsLayer().also { slot.layer = it }

    private fun releaseLayers() {
        val ctx = requireGraphicsContext()
        for (slot in slots) {
            slot.layer?.let(ctx::releaseGraphicsLayer)
            slot.layer = null
            slot.recordedChild = null
            slot.wasDefault = true
            slot.params.reset()
        }
        drawSize = Size.Unspecified
    }

    override fun onAttach() {
        // ReusableContentHost 复用时可能保留外层显示列表，它引用的自有图层已在 detach 时释放。
        // 即使来源和尺寸没变，也要重录这个引用，并在首次 draw 按当前进度重新订阅。
        drawSize = Size.Unspecified
        bindSources()
        invalidateDraw()
    }

    override fun onDetach() {
        binding?.close()
        binding = null
        releaseLayers()
    }

    fun update(newSources: Array<out LayerSource>) {
        val replaced = !sources.sameSources(newSources)
        sources = newSources
        if (isAttached && replaced) bindSources()
        if (slots.size != sources.size) {
            if (isAttached) {
                releaseLayers()
                invalidateDraw()
            }
            slots = Array(sources.size) { Slot() }
        } else {
            refreshLayers()
        }
    }

    /** 进度（快照状态）变了：更新图层后向此刻承载节点的 View 请求一帧；不重新录制内容。 */
    private fun refreshLayers() {
        if (isAttached && drawSize != Size.Unspecified && applyParams()) requireView().invalidate()
    }

    /** 进度读取只记在本节点的观察里。返回是否有属性或绘制链发生变化。 */
    private fun applyParams(): Boolean {
        checkNotNull(binding).observe(readSources)
        var changed = false
        var inner: GraphicsLayer? = null
        for (i in slots.indices) {
            val slot = slots[i]
            val atRest = slot.params.isDefault
            // 两端始终不换：最内层拥有内容，最外层已被 Compose 的显示列表引用。
            // 中间的恒等层不进绘制链；用过的图层保留到 detach，反复开合时不反复分配 RenderNode。
            if (atRest && i != 0 && i != slots.lastIndex) continue
            val layer = layerFor(slot)
            if (!atRest || !slot.wasDefault) {
                if (writeTo(layer, slot.params)) changed = true
            }
            slot.wasDefault = atRest
            val child = inner
            if (child != null && (child !== slot.recordedChild || layer.size != intSize)) {
                // 这里只画一个子层引用，不读页面内容，也不需要 ContentDrawScope。
                layer.record(requireDensity(), requireLayoutDirection(), intSize) { drawLayer(child) }
                slot.recordedChild = child
                changed = true
            }
            inner = layer
        }
        return changed
    }

    private fun writeTo(layer: GraphicsLayer, p: LayerParams): Boolean {
        var changed = false
        if (layer.rotationY != p.rotationY) { layer.rotationY = p.rotationY; changed = true }
        if (layer.translationX != p.translationX) { layer.translationX = p.translationX; changed = true }
        if (layer.translationY != p.translationY) { layer.translationY = p.translationY; changed = true }
        if (layer.scaleX != p.scaleX) { layer.scaleX = p.scaleX; changed = true }
        if (layer.scaleY != p.scaleY) { layer.scaleY = p.scaleY; changed = true }
        if (layer.cameraDistance != p.cameraDistance) { layer.cameraDistance = p.cameraDistance; changed = true }
        if (layer.renderEffect !== p.renderEffect) { layer.renderEffect = p.renderEffect; changed = true }
        val pivot = if (p.pivotFracX == 0.5f && p.pivotFracY == 0.5f) {
            Offset.Unspecified
        } else {
            Offset(p.pivotFracX * drawSize.width, p.pivotFracY * drawSize.height)
        }
        if (layer.pivotOffset != pivot) { layer.pivotOffset = pivot; changed = true }
        return changed
    }

    override fun ContentDrawScope.draw() {
        if (slots.isEmpty()) {
            drawContent()
            return
        }
        val inputsChanged = drawSize != size || pixelsPerDp != density
        drawSize = size
        pixelsPerDp = density
        // 必须用 DrawScope 里的成员扩展 GraphicsLayer.record(size) { }：它把本作用域的画布临时切到图层上，里面的 drawContent() 才会画进图层
        // （带 density / layoutDirection 的 GraphicsLayer.record 用的是图层自己的作用域，drawContent() 会直接画到外面、图层是空的）
        layerFor(slots.first()).record(intSize) { this@draw.drawContent() }
        if (inputsChanged) applyParams()
        drawLayer(checkNotNull(slots.last().layer))
    }
}
