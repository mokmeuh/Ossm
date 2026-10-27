package com.ossm.remote.ui.components

import kotlin.math.hypot
import kotlin.math.max

/**
 * 2.3.24 "upper half-disc" Live geometry. The first pointer-down, anywhere on the Live surface,
 * becomes the anchor (logical start `B`). Only the half-plane ABOVE the anchor's horizontal line
 * adds depth: there the target follows the radial anchor-to-finger distance (sideways movement
 * counts too), normalised by the distance from the anchor to the farthest TOP corner of the
 * surface (= 100). On or below the anchor line the target stays `B`.
 */
internal data class LiveAnchorGeometry(
    val anchorX: Float,
    val anchorY: Float,
    val reach: Float,
)

/** Default minimum reach (dp) used when the anchor lands at the very top of the surface. */
internal const val LIVE_ANCHOR_MIN_REACH_DP = 48f

/**
 * Distance from the anchor to the farthest top corner of a `width x height` surface, never below
 * [minReachPx] (and never below 1 px) so an anchor on the top edge cannot divide by zero.
 */
internal fun liveAnchorReach(
    anchorX: Float,
    anchorY: Float,
    widthPx: Float,
    heightPx: Float,
    minReachPx: Float = 1f,
): Float {
    val width = widthPx.coerceAtLeast(1f)
    val height = heightPx.coerceAtLeast(1f)
    val x = anchorX.coerceIn(0f, width)
    val y = anchorY.coerceIn(0f, height)
    val farthestTop = max(hypot(x, y), hypot(width - x, y))
    return farthestTop.coerceAtLeast(minReachPx.coerceAtLeast(1f))
}

internal fun liveAnchorGeometry(
    anchorX: Float,
    anchorY: Float,
    widthPx: Float,
    heightPx: Float,
    minReachPx: Float = 1f,
): LiveAnchorGeometry = LiveAnchorGeometry(
    anchorX = anchorX.coerceIn(0f, widthPx.coerceAtLeast(1f)),
    anchorY = anchorY.coerceIn(0f, heightPx.coerceAtLeast(1f)),
    reach = liveAnchorReach(anchorX, anchorY, widthPx, heightPx, minReachPx),
)

/**
 * Finger position as a fraction of the reach, clamped to 0..1. A finger on or below the anchor's
 * horizontal line contributes nothing (0); above it, the radial distance counts.
 */
internal fun liveAnchorFraction(
    pointerX: Float,
    pointerY: Float,
    geometry: LiveAnchorGeometry,
): Float {
    if (pointerY >= geometry.anchorY) return 0f
    val distance = hypot(pointerX - geometry.anchorX, pointerY - geometry.anchorY)
    return (distance / geometry.reach).coerceIn(0f, 1f)
}

/**
 * Logical Live target for an anchored gesture: `target = base + fraction * (100 - base)`. A fresh
 * touch during the slow return never jumps: the new anchor is the current position and moving up
 * only adds depth beyond it; it never goes below the base.
 */
internal fun liveAnchorTargetPercent(basePercent: Float, fraction: Float): Float {
    val base = basePercent.coerceIn(0f, 100f)
    return (base + fraction.coerceIn(0f, 1f) * (100f - base)).coerceIn(0f, 100f)
}

/**
 * Geometry-only gesture state. It reports the raw desired target; motor motion bounds stay in
 * the session/BLE layers (entry ramp, return ramp, envelope, firmware limits).
 */
internal class LiveAnchorState {
    var valuePercent: Float = 0f
        private set

    var geometry: LiveAnchorGeometry? = null
        private set

    var basePercent: Float = 0f
        private set

    var pointerX: Float = 0f
        private set

    var pointerY: Float = 0f
        private set

    private var active = false
    private var desiredPercent = 0f

    val isActive: Boolean
        get() = active

    val desired: Int
        get() = desiredPercent.toInt().coerceIn(0, 100)

    /** True while the finger is strictly above the anchor line (the active half-disc). */
    val isAboveAnchor: Boolean
        get() = geometry?.let { pointerY < it.anchorY } ?: false

    /**
     * Place a new anchor. [currentPercent] is the current logical position; it becomes the
     * anchor's value so pointer-down never emits a jump.
     */
    fun onDown(
        currentPercent: Float,
        pointerX: Float,
        pointerY: Float,
        widthPx: Float,
        heightPx: Float,
        minReachPx: Float = 1f,
    ): Float {
        active = true
        val anchor = liveAnchorGeometry(pointerX, pointerY, widthPx, heightPx, minReachPx)
        geometry = anchor
        basePercent = currentPercent.coerceIn(0f, 100f)
        this.pointerX = anchor.anchorX
        this.pointerY = anchor.anchorY
        desiredPercent = basePercent
        valuePercent = basePercent
        return valuePercent
    }

    fun onMove(pointerX: Float, pointerY: Float): Float? {
        val anchor = geometry
        if (!active || anchor == null) return null
        this.pointerX = pointerX
        this.pointerY = pointerY
        desiredPercent = liveAnchorTargetPercent(basePercent, liveAnchorFraction(pointerX, pointerY, anchor))
        val next = desiredPercent.coerceIn(0f, 100f)
        if (next == valuePercent) return null
        valuePercent = next
        return valuePercent
    }

    fun endTouch() {
        active = false
        geometry = null
    }

    fun cancelTouch() = endTouch()
}
