package com.theveloper.pixelplay.data.songtap.ring

import androidx.compose.runtime.Immutable

/** The 200 slot bits of one ring code (0 = small dot, 1 = big dot, index = slot id) and a serial that changes with every new code. */
@Immutable
class RingCodeSlots(val bits: ByteArray, val serial: Int) {
    override fun equals(other: Any?): Boolean = other is RingCodeSlots && other.serial == serial && other.bits === bits
    override fun hashCode(): Int = serial
}
