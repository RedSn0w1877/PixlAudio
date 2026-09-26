package com.theveloper.pixelplay.ui.glass.utils

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Ported from the Backdrop library's catalog app (utils/ProgressConverter.kt) — maps an
 * unbounded overscroll progress to a rubber-banded one that asymptotically approaches ±1.
 */
fun interface ProgressConverter {

    fun convert(progress: Float): Float

    companion object {

        val Default: ProgressConverter =
            ProgressConverter { progress ->
                (1f - exp(-abs(progress))) * progress.sign
            }
    }
}
