package com.ronan.qmusicwatch.playback

import kotlin.math.abs

/**
 * Chooses the useful component of a rotary event.
 *
 * Most crowns report their movement as [vertical], but a few watch launchers
 * rotate the input device and expose it as [horizontal].  Prefer the axis with
 * the larger absolute movement so a zero vertical component does not silently
 * discard a real crown turn.
 */
internal fun rotaryScrollDelta(vertical: Float, horizontal: Float): Float = when {
    !vertical.isFinite() && !horizontal.isFinite() -> 0f
    abs(vertical) >= abs(horizontal) && vertical.isFinite() -> vertical
    horizontal.isFinite() -> horizontal
    else -> 0f
}

/**
 * Maps a crown delta to a volume step.  Negative rotary movement is kept as
 * volume-up to preserve the direction used by the previous watch build.
 */
internal fun rotaryVolumeDirection(delta: Float): Int? = when {
    !delta.isFinite() || delta == 0f -> null
    delta < 0f -> 1
    else -> -1
}
