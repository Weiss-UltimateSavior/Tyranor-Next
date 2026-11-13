package com.tyranor.next.ui.common.motion

import java.util.IdentityHashMap

/**
 * Android picks the topmost dimming window, including one requesting zero dim.
 * Inherit the still-visible lower window's mask during a nested dialog, without deepening it.
 * Requests retain their own dim, so removing a lower window restores the remaining window's mask.
 * Owners and windows are identities; all calls belong to the UI thread.
 */
internal class DialogDimStack<W : Any>(
    private val isVisible: (W) -> Boolean,
    private val applyPanelDim: (W, Float) -> Unit = { _, _ -> },
    private val applyDim: (W, Float, Float, Boolean) -> Unit,
) {
    private class Request<W>(
        val owner: Any,
        val window: W,
        var dim: Float,
        var enabled: Boolean,
        var baseDim: Float,
        var scrim: Float,
        var baseScrim: Float,
        var released: Boolean = false,
    )
    private class Mask(val dim: Float, val scrim: Float, val fullAlpha: Float)
    private class Resolved<W>(
        val request: Request<W>,
        val dim: Float,
        val scrim: Float,
        val target: Float,
        val visible: Boolean,
    )
    private class UpperMask(var nativeDim: Float? = null, var scrim: Float = 0f, var hasSource: Boolean = false)
    private val requests = ArrayList<Request<W>>()

    fun register(
        owner: Any,
        window: W,
        dim: Float,
        enabled: Boolean,
        baseDim: Float = dim,
        scrim: Float = 0f,
        baseScrim: Float = scrim,
        released: Boolean = false,
    ) {
        check(requests.none { it.window === window })
        requests.add(Request(owner, window, dim, enabled, baseDim, scrim, baseScrim, released))
        refresh()
    }

    fun update(
        window: W,
        dim: Float,
        enabled: Boolean,
        baseDim: Float = dim,
        scrim: Float = 0f,
        baseScrim: Float = scrim,
    ) {
        val request = requests.firstOrNull { it.window === window } ?: return
        request.dim = dim
        request.enabled = enabled
        request.baseDim = baseDim
        request.scrim = scrim
        request.baseScrim = baseScrim
        refresh()
    }

    /** A newly attached dialog is above the windows that were already attached. */
    fun attached(window: W) {
        val index = requests.indexOfFirst { it.window === window }
        if (index < 0) return
        requests.add(requests.removeAt(index))
        refresh()
    }

    fun remove(window: W) {
        requests.removeAll { it.window === window }
        refresh()
    }

    /** Nested exits immediately return dim ownership to a visible lower mask. */
    fun beginExit(window: W): Boolean {
        val index = requests.indexOfFirst { it.window === window }
        if (index < 0) return false
        val request = requests[index]
        if (request.released) return true
        if ((0 until index).none { requests[it].owner === request.owner && contributesFloor(requests[it]) }) return false
        request.released = true
        refresh()
        return true
    }

    /** Explicit release is persistent until restore, including later raw progress updates. */
    fun release(window: W) {
        val request = requests.firstOrNull { it.window === window } ?: return
        if (request.released) return
        request.released = true
        refresh()
    }

    /** Reopening uses the latest raw request without changing actual attachment order. */
    fun restore(window: W) {
        val request = requests.firstOrNull { it.window === window } ?: return
        if (!request.released) return
        request.released = false
        refresh()
    }

    fun refresh() {
        val floors = IdentityHashMap<Any, Mask>()
        val highest = IdentityHashMap<Any, Resolved<W>>()
        val resolved = ArrayList<Resolved<W>>(requests.size)
        // Keep the existing root allocation and its Window update order. Moving a drawn
        // mask to native dim crosses two independently committed rendering transactions.
        for (request in requests) {
            if (request.released) {
                applyDim(request.window, 0f, 0f, false)
                resolved.add(Resolved(request, 0f, 0f, 0f, isVisible(request.window)))
                continue
            }
            val lower = floors[request.owner]
            val lowerScrim = lower?.scrim ?: 0f
            val lowerAlpha = combine(lower?.dim ?: 0f, lowerScrim)
            val ownAlpha = combine(if (request.enabled) request.dim else 0f, request.scrim)
            val ownFullAlpha = combine(if (request.enabled) request.baseDim else 0f, request.baseScrim)
            val fullAlpha = lower?.fullAlpha ?: ownFullAlpha
            // A fully open upper window also holds a darker inherited mask (e.g. crop over
            // an enhanced dialog). Its own fade controls handoff, rather than its theme's alpha.
            val inherited = if (ownFullAlpha <= 0f) 0f else fullAlpha * (ownAlpha / ownFullAlpha).coerceIn(0f, 1f)
            val target = if (lower == null) ownAlpha else maxOf(lowerAlpha, inherited)
            // Native dim replaces lower native requests, while lower drawn masks remain.
            val dim = if (!request.enabled) 0f else if (lower == null) request.dim else missingAlpha(target, lowerScrim)
            val scrim = if (lower == null) request.scrim else if (request.enabled) 0f else missingAlpha(target, lowerAlpha)
            applyDim(request.window, dim, scrim, request.enabled)
            val current = Resolved(request, dim, scrim, target, isVisible(request.window))
            resolved.add(current)
            if (contributesFloor(request)) {
                highest[request.owner] = current
                floors[request.owner] = Mask(
                    if (request.enabled) dim else lower?.dim ?: 0f,
                    combine(lowerScrim, scrim),
                    fullAlpha,
                )
            }
        }
        // A Window's own scrim is behind its panel. Add only the panel-local amount missing
        // from masks above it; neither root scrims nor native eligibility need to move.
        val upperMasks = IdentityHashMap<Any, UpperMask>()
        for (current in resolved.asReversed()) {
            val request = current.request
            val upper = upperMasks.getOrPut(request.owner) { UpperMask() }
            val alpha = if (current.visible && upper.hasSource) {
                val external = combine(upper.nativeDim ?: 0f, upper.scrim)
                missingAlpha(highest[request.owner]?.target ?: 0f, external)
            } else 0f
            applyPanelDim(request.window, alpha)
            if (current.visible && !request.released) {
                // Descending order selects only the highest actual native request.
                if (request.enabled && upper.nativeDim == null) upper.nativeDim = current.dim
                upper.scrim = combine(upper.scrim, current.scrim)
                if (request.enabled || request.baseScrim > 0f) upper.hasSource = true
            }
        }
    }

    private fun contributesFloor(request: Request<W>): Boolean =
        !request.released && (request.enabled || request.baseScrim > 0f) && isVisible(request.window)

    private fun combine(first: Float, second: Float): Float = 1f - (1f - first) * (1f - second)

    private fun missingAlpha(target: Float, existing: Float): Float =
        if (existing >= 1f) 0f else ((target - existing) / (1f - existing)).coerceIn(0f, 1f)
}
