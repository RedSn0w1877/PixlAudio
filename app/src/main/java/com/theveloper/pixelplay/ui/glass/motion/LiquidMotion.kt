package com.theveloper.pixelplay.ui.glass.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.util.fastCoerceIn

/**
 * Central motion tokens for the liquid-glass kit (ported from NexHome) — tune the feel of the whole app here.
 *
 * The language is water: a quick swell under the finger, a slow bouncy overshoot on release,
 * and a liquid follow for anything dragged or anything that trails a finger (highlights, light).
 */
object LiquidMotion {

    /** Press: glass swells up quickly under a finger, like water bulging where it is pushed. */
    val PressSpring: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 320f, visibilityThreshold = 0.001f)

    /** Release: a soft, visible overshoot and a graceful settle back to rest. */
    val ReleaseSpring: SpringSpec<Float> = spring(dampingRatio = 0.34f, stiffness = 150f, visibilityThreshold = 0.001f)

    /** Finger-following motion (touch highlight, light spill position) — trails, never snaps. */
    val GlideSpring: SpringSpec<Offset> =
        spring(dampingRatio = 0.78f, stiffness = 210f, visibilityThreshold = Offset.VisibilityThreshold)

    /** [GlideSpring] for scalar values (e.g. a dial angle following a finger). */
    val GlideSpringFloat: SpringSpec<Float> = spring(dampingRatio = 0.78f, stiffness = 210f, visibilityThreshold = 0.001f)

    /** Light spill rise (~0.45 s): a quick tap is a faint flash, a hold becomes a full shine. */
    val LightBloomSpring: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 55f, visibilityThreshold = 0.001f)

    /** Light spill fade after release — slower than the bloom, so light lingers gracefully. */
    val LightFadeSpring: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 38f, visibilityThreshold = 0.001f)

    /** Sheets / screens appearing: a "lens bloom" with a small overshoot. */
    val EnterSpring: SpringSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 170f, visibilityThreshold = 0.001f)

    /** Non-bouncy press glow (never dips below zero). */
    val GlowSpring: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 260f, visibilityThreshold = 0.001f)

    /**
     * A glass shape flowing into another (the queue's ⋯ circle stretching into its menu pill):
     * quick, with a small liquid overshoot. [EnterSpring] (0.62 / 170) is too slow for a menu.
     */
    val MorphOpenSpring: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 360f, visibilityThreshold = 0.001f)

    /** The morph flowing back: no overshoot, so the pill never dips past the circle it returns to. */
    val MorphCloseSpring: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = 0.001f)

    /** Tile / panel press swell. */
    const val TilePressScale = 1.07f

    /** Button press swell. */
    const val ButtonPressScale = 1.16f

    /** Round orb press swell. */
    const val OrbPressScale = 1.12f

    /** Max extra stretch along the drag axis for jelly surfaces (+8%). */
    const val JellyStretch = 0.08f

    /** tanh initial derivative for the jelly translation toward the finger. */
    const val JellyFollow = 0.06f

    /** Refraction height/amount multiplier at full press ("glass thickens under touch"). */
    const val LensThicken = 1.6f

    // --- DampedDragAnimation (draggable blobs: toggle/slider thumbs, tab indicator, segmented) ---

    /** Blob value follow — underdamped so blobs overshoot when they snap. */
    const val BlobValueDamping = 0.72f
    const val BlobValueStiffness = 560f

    /** Blob press scale springs (X wobblier than Y for an anisotropic jelly feel). */
    val BlobScaleXSpring: SpringSpec<Float> = spring(dampingRatio = 0.36f, stiffness = 230f, visibilityThreshold = 0.001f)
    val BlobScaleYSpring: SpringSpec<Float> = spring(dampingRatio = 0.44f, stiffness = 230f, visibilityThreshold = 0.001f)

    /** Press-progress spring for blobs (lens/shadow fade-in). */
    val BlobPressSpring: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f, visibilityThreshold = 0.001f)

    /** How much bigger than the component-requested pressed scale blobs grow (applied to the delta). */
    const val BlobPressedScaleBoost = 1.15f

    /** Velocity squash multiplier and clamp. */
    const val VelocitySquash = 1.6f
    const val VelocitySquashClamp = 0.32f
}

/**
 * Applies the kit's velocity squash to an already-set scaleX/scaleY: stretched along the motion,
 * thinned across it. [velocity] is a normalized velocity (the catalog divides by 10..50).
 */
fun GraphicsLayerScope.applyVelocitySquash(velocity: Float) {
    val c = LiquidMotion.VelocitySquashClamp
    scaleX /= 1f - (velocity * 0.75f * LiquidMotion.VelocitySquash).fastCoerceIn(-c, c)
    scaleY *= 1f - (velocity * 0.25f * LiquidMotion.VelocitySquash).fastCoerceIn(-c, c)
}
