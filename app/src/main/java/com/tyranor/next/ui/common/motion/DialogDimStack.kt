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
    private val applyDim: (W, Float, Float) -> Unit,
) {
    private class Request<W>(
        val owner: Any,
        val window: W,
        var dim: Float,
        var enabled: Boolean,
        var baseDim: Float,
        var scrim: Float,
        var baseScrim: Float,
    )
    private class Mask(val dim: Float, val scrim: Float, val fullAlpha: Float)
    private val requests = ArrayList<Request<W>>()

    fun register(
        owner: Any,
        window: W,
        dim: Float,
        enabled: Boolean,
        baseDim: Float = dim,
        scrim: Float = 0f,
        baseScrim: Float = scrim,
    ) {
        check(requests.none { it.window === window })
        requests.add(Request(owner, window, dim, enabled, baseDim, scrim, baseScrim))
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

    fun refresh() {
        val floors = IdentityHashMap<Any, Mask>()
        for (request in requests) {
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
            // WMS replaces lower native dim with this window's request. Lower drawn masks
            // remain, so let native dim supply exactly the missing amount, with no extra scrim.
            val dim = if (!request.enabled) 0f else if (lower == null) request.dim else missingAlpha(target, lowerScrim)
            // A self-drawn-only source (Material3 sheet) leaves the existing native request
            // alone. It supplies only what is missing from the lower composed output.
            val scrim = if (lower == null) request.scrim else if (request.enabled) 0f else missingAlpha(target, lowerAlpha)
            applyDim(request.window, dim, scrim)
            if ((request.enabled || request.baseScrim > 0f) && isVisible(request.window)) {
                floors[request.owner] = Mask(
                    if (request.enabled) dim else lower?.dim ?: 0f,
                    combine(lowerScrim, scrim),
                    fullAlpha,
                )
            }
        }
    }

    private fun combine(first: Float, second: Float): Float = 1f - (1f - first) * (1f - second)

    private fun missingAlpha(target: Float, existing: Float): Float =
        if (existing >= 1f) 0f else ((target - existing) / (1f - existing)).coerceIn(0f, 1f)
}
