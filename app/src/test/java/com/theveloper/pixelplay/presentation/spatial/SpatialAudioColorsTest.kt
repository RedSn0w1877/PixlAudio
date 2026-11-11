package com.theveloper.pixelplay.presentation.spatial

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The colours of the player's Spatial audio button and of the sheet's rows ([rememberSpatialButtonColors],
 * [rememberSpatialChoiceFill]). In the full player both wear the album's palette, which fades over about 40 frames
 * after every skip, so new colours arrive on every frame of a fade: they must show at once, like those of Lyrics and
 * Ask Taizo beside the button, and only the swap between off and on (or a change of selection) may animate.
 *
 * The real composables run on Compose's runtime alone, with no Android and no UI: a recomposer, a composition without
 * nodes, and a frame clock the test advances one 16 ms frame at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpatialAudioColorsTest {

    @Test
    fun `the button takes each frame's chip colours at once, off and on, and a new colour starts no animation`() = runTest {
        val frames = Frames(this)
        var emphasized by mutableStateOf(false)
        var chip by mutableStateOf(chipAt(0))
        var onChip by mutableStateOf(onChipAt(0))
        lateinit var colors: SpatialButtonColors
        frames.setContent {
            colors = rememberSpatialButtonColors(
                emphasized = emphasized,
                animate = true,
                containerColor = chip,
                contentColor = onChip,
            )
        }
        assertEquals(chipAt(0), colors.container())
        assertEquals(onChipAt(0), colors.content())

        // Off, a skip: the album colours fade, and the player passes new chip colours on every frame.
        for (i in 1..FADE_FRAMES) {
            chip = chipAt(i)
            onChip = onChipAt(i)
            frames.frame()
            assertEquals(chipAt(i), colors.container(), "the fill on frame $i of the fade")
            assertEquals(onChipAt(i), colors.content(), "the icon and the label on frame $i of the fade")
        }
        assertFalse(frames.waitingForFrame, "a new colour started an animation")

        // A profile is picked: the swap animates, and ends on the swapped chip colours.
        emphasized = true
        frames.frames(6)
        val midway = colors.container()
        assertNotEquals(chipAt(FADE_FRAMES), midway, "the swap jumped instead of animating")
        assertNotEquals(onChipAt(FADE_FRAMES), midway, "the swap jumped instead of animating")
        frames.frames(15)
        assertEquals(onChipAt(FADE_FRAMES), colors.container())
        assertEquals(chipAt(FADE_FRAMES).copy(alpha = 1f), colors.content())
        assertFalse(frames.waitingForFrame, "the swap never ended")

        // On, the next skip: again at once.
        for (i in FADE_FRAMES + 1..2 * FADE_FRAMES) {
            chip = chipAt(i)
            onChip = onChipAt(i)
            frames.frame()
            assertEquals(onChipAt(i), colors.container(), "the fill on frame $i of the fade")
            assertEquals(chipAt(i).copy(alpha = 1f), colors.content(), "the icon and the label on frame $i of the fade")
        }
        assertFalse(frames.waitingForFrame, "a new colour started an animation")
        frames.dispose()
    }

    @Test
    fun `before the stored state is shown the button's swap lands in one step, after that it animates`() = runTest {
        val frames = Frames(this)
        var emphasized by mutableStateOf(false)
        var animate by mutableStateOf(false)
        lateinit var colors: SpatialButtonColors
        frames.setContent {
            colors = rememberSpatialButtonColors(
                emphasized = emphasized,
                animate = animate,
                containerColor = chipAt(0),
                contentColor = onChipAt(0),
            )
        }

        // The stored profile has been read: it appears in place, with no colour in between.
        emphasized = true
        frames.frame()
        val first = colors.container()
        assertTrue(first == chipAt(0) || first == onChipAt(0), "a colour in between: the swap animated")
        frames.frame()
        assertEquals(onChipAt(0), colors.container())
        assertEquals(chipAt(0).copy(alpha = 1f), colors.content())
        assertFalse(frames.waitingForFrame)

        // Shown: from now on the swap animates.
        animate = true
        frames.frame()
        emphasized = false
        frames.frames(6)
        assertNotEquals(chipAt(0), colors.container(), "the swap jumped instead of animating")
        assertNotEquals(onChipAt(0), colors.container(), "the swap jumped instead of animating")
        frames.frames(15)
        assertEquals(chipAt(0), colors.container())
        assertEquals(onChipAt(0), colors.content())
        frames.dispose()
    }

    @Test
    fun `a sheet row takes each frame's colours at once and only a change of selection animates`() = runTest {
        val frames = Frames(this)
        var selected by mutableStateOf(false)
        var surfaceHigh by mutableStateOf(surfaceHighAt(0))
        var secondary by mutableStateOf(secondaryContainerAt(0))
        lateinit var fill: ColorProducer
        frames.setContent {
            fill = rememberSpatialChoiceFill(
                selected = selected,
                unselectedColor = surfaceHigh,
                selectedColor = secondary,
            )
        }
        assertEquals(surfaceHighAt(0), fill())

        // The song changes while the sheet is open: the scheme fades, a new one on every frame.
        for (i in 1..FADE_FRAMES) {
            surfaceHigh = surfaceHighAt(i)
            secondary = secondaryContainerAt(i)
            frames.frame()
            assertEquals(surfaceHighAt(i), fill(), "the fill on frame $i of the fade")
        }
        assertFalse(frames.waitingForFrame, "a new colour started an animation")

        // The row is picked: the selection animates, and ends on the selected colour.
        selected = true
        frames.frames(6)
        val midway = fill()
        assertNotEquals(surfaceHighAt(FADE_FRAMES), midway, "the selection jumped instead of animating")
        assertNotEquals(secondaryContainerAt(FADE_FRAMES), midway, "the selection jumped instead of animating")
        frames.frames(15)
        assertEquals(secondaryContainerAt(FADE_FRAMES), fill())
        assertFalse(frames.waitingForFrame, "the selection never ended")

        // Selected, the next song: again at once.
        for (i in FADE_FRAMES + 1..2 * FADE_FRAMES) {
            surfaceHigh = surfaceHighAt(i)
            secondary = secondaryContainerAt(i)
            frames.frame()
            assertEquals(secondaryContainerAt(i), fill(), "the fill on frame $i of the fade")
        }
        assertFalse(frames.waitingForFrame, "a new colour started an animation")
        frames.dispose()
    }

    @Test
    fun `the selected row shows as selected when the sheet opens, without animating`() = runTest {
        val frames = Frames(this)
        lateinit var fill: ColorProducer
        frames.setContent {
            fill = rememberSpatialChoiceFill(
                selected = true,
                unselectedColor = surfaceHighAt(0),
                selectedColor = secondaryContainerAt(0),
            )
        }
        assertEquals(secondaryContainerAt(0), fill())
        assertFalse(frames.waitingForFrame)
        frames.dispose()
    }

    // Colours in the shape the player passes them: a translucent chip behind an opaque content colour, and a sheet
    // scheme. Every frame of a fade gives a different one.
    private fun chipAt(frame: Int) = Color(red = 0.10f + frame / 100f, green = 0.55f, blue = 0.90f - frame / 100f, alpha = 0.8f)
    private fun onChipAt(frame: Int) = Color(red = 0.90f - frame / 100f, green = 0.25f, blue = 0.10f + frame / 100f)
    private fun surfaceHighAt(frame: Int) = Color(red = 0.20f + frame / 120f, green = 0.22f, blue = 0.24f)
    private fun secondaryContainerAt(frame: Int) = Color(red = 0.30f, green = 0.70f - frame / 120f, blue = 0.50f)

    /**
     * A composition without nodes on a frame clock the test drives. Its effects and animations run on the test's
     * scheduler; snapshot writes reach it when the next frame runs.
     */
    private class Frames(private val test: TestScope) {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(test.backgroundScope.coroutineContext)
        private val composition = Composition(NoNodes(), recomposer)
        private var nanos = 1_000_000_000L

        init {
            test.backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            test.runCurrent()
        }

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent(content)
            test.runCurrent()
        }

        /** One 16 ms frame: running animations step, what changed recomposes, and the effects that started run. */
        fun frame() {
            Snapshot.sendApplyNotifications()
            test.runCurrent()
            clock.sendFrame(nanos)
            nanos += FRAME_NANOS
            test.runCurrent()
        }

        fun frames(count: Int) = repeat(count) { frame() }

        /** Something waits for the next frame: an animation is running, or a change waits to be recomposed. */
        val waitingForFrame: Boolean
            get() {
                Snapshot.sendApplyNotifications()
                test.runCurrent()
                return clock.hasAwaiters
            }

        fun dispose() {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        /** About the length of the player's album-colour fade (PlayerSchemeScopes.kt). */
        const val FADE_FRAMES = 40
        const val FRAME_NANOS = 16_000_000L
    }
}
