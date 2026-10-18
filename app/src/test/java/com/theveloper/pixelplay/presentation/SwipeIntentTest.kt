package com.theveloper.pixelplay.presentation

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.presentation.utils.SwipeIntent
import org.junit.jupiter.api.Test

class SwipeIntentTest {
    private val slop = 8f

    @Test
    fun clearlySidewaysIsHorizontal() {
        assertThat(SwipeIntent.isHorizontal(40f, 10f, slop)).isTrue()
        assertThat(SwipeIntent.isHorizontal(-40f, -10f, slop)).isTrue()
    }

    @Test
    fun diagonalOrShortMovementIsNotHorizontal() {
        assertThat(SwipeIntent.isHorizontal(40f, 30f, slop)).isFalse()
        assertThat(SwipeIntent.isHorizontal(5f, 0f, slop)).isFalse()
    }

    @Test
    fun verticalMovementIsLeftToScrolling() {
        assertThat(SwipeIntent.isVertical(5f, 30f, slop)).isTrue()
        assertThat(SwipeIntent.isHorizontal(5f, 30f, slop)).isFalse()
        assertThat(SwipeIntent.isVertical(30f, 5f, slop)).isFalse()
    }

    @Test
    fun commitNeedsTheThreshold() {
        assertThat(SwipeIntent.commitDirection(100f, 64f)).isEqualTo(1)
        assertThat(SwipeIntent.commitDirection(-100f, 64f)).isEqualTo(-1)
        assertThat(SwipeIntent.commitDirection(30f, 64f)).isEqualTo(0)
    }
}
