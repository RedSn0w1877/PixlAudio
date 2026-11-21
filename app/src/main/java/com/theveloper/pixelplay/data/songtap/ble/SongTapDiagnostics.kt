package com.theveloper.pixelplay.data.songtap.ble

import android.os.Build
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.songtap.SongTapTuning
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line of the diagnostics log: [tMs] is on the monotonic clock. */
@Immutable
data class DiagEvent(val tMs: Long, val text: String)

/** What the diagnostics screen shows right now. Integers only; dB values are whole dB (the signal in 1/256 dB is rounded). */
@Immutable
data class DiagSnapshot(
    val status: BleStatus = BleStatus.IDLE,
    val role: String = "-",
    val peersSeen: Int = 0,
    /** The peer the detector is following, as "#3 sending", or empty. */
    val focus: String = "",
    val rssi: Int = 0,
    val smoothed: Int = 0,
    val baseline: Int = 0,
    /** Smoothed minus baseline, whole dB: a tap is a jump of at least [SongTapTuning.JUMP_DB]. */
    val jump: Int = 0,
    /** How long the jump has held, ms; -1 when there is none. */
    val heldMs: Long = -1,
    val near: Boolean = false,
    val connection: String = "-",
    /** Milliseconds since this phone's last jerk, -1 when none; same for the peer's. */
    val ownJerkAgeMs: Long = -1,
    val peerJerkAgeMs: Long = -1,
    val detector: String = "idle",
    val lastFire: String = "-",
    val samples: Int = 0,
    /** Which gate the followed phone is in: "strict near" (may connect, may beam), "list only" (shown in the picker, nothing else), "outside" or "none". */
    val gate: String = "none",
    val mayConnect: Boolean = false,
    val listed: Boolean = false,
    /** Receiver: the sender's offer is already fetched and checked, ready for the tap. */
    val offerInHand: Boolean = false,
    /** The short name it advertised and the full name from its Name characteristic. */
    val names: String = "",
)

/**
 * The numbers behind the tap detector, for the person calibrating it on two real phones: Developer mode >
 * Song Tap diagnostics. The log keeps the last [EVENT_CAPACITY] events and the snapshot is published only
 * while that screen is open, at most [PUBLISH_EVERY_MS] apart, so a normal session pays for a few integer
 * writes per signal reading and nothing else. A small ring of recent readings (preallocated, no per-reading
 * allocation) goes into "Copy log" so the signal can be read off at 0, 1, 3 and 10 cm.
 */
@Singleton
class SongTapDiagnostics @Inject constructor() {

    private val lock = Any()
    @Volatile private var enabled = false
    private var lastPublishMs = 0L

    private val _snapshot = MutableStateFlow(DiagSnapshot())
    val snapshot: StateFlow<DiagSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    private val ringT = LongArray(RING)
    private val ringRssi = IntArray(RING)
    private val ringSmoothed = IntArray(RING)
    private val ringBaseline = IntArray(RING)
    private var ringCount = 0
    private var ringNext = 0
    private var sampleTotal = 0

    /** The diagnostics screen is open: publish snapshots. */
    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun isEnabled(): Boolean = enabled

    fun log(tMs: Long, text: String) {
        synchronized(lock) {
            val next = ArrayList<DiagEvent>(EVENT_CAPACITY)
            val old = _events.value
            val from = if (old.size >= EVENT_CAPACITY) old.size - EVENT_CAPACITY + 1 else 0
            for (i in from until old.size) next.add(old[i])
            next.add(DiagEvent(tMs, text))
            _events.value = next
        }
    }

    /** One signal reading of the peer the detector follows. */
    fun sample(tMs: Long, rssi: Int, smoothedDbm: Int, baselineDbm: Int) {
        synchronized(lock) {
            ringT[ringNext] = tMs
            ringRssi[ringNext] = rssi
            ringSmoothed[ringNext] = smoothedDbm
            ringBaseline[ringNext] = baselineDbm
            ringNext = (ringNext + 1) % RING
            if (ringCount < RING) ringCount++
            sampleTotal++
        }
    }

    fun sampleCount(): Int = synchronized(lock) { sampleTotal }

    /** Publishes [snap] if the screen is open and it has been a moment since the last one. */
    fun publish(tMs: Long, snap: () -> DiagSnapshot) {
        if (!enabled) return
        if (tMs - lastPublishMs < PUBLISH_EVERY_MS) return
        lastPublishMs = tMs
        _snapshot.value = snap()
    }

    fun publishNow(snap: DiagSnapshot) {
        if (enabled) _snapshot.value = snap
    }

    fun clear() {
        synchronized(lock) {
            _events.value = emptyList()
            ringCount = 0
            ringNext = 0
            sampleTotal = 0
        }
        _snapshot.value = DiagSnapshot()
    }

    /** Everything worth pasting into a message: the phone, the tuning, the last events and the recent signal. */
    fun copyLog(): String = synchronized(lock) {
        val sb = StringBuilder(4096)
        sb.append("Song Tap diagnostics\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        val s = _snapshot.value
        sb.append("gate: ").append(s.gate).append(", may connect ").append(s.mayConnect).append(", listed ").append(s.listed)
            .append(", offer in hand ").append(s.offerInHand).append(", names ").append(s.names).append(NL)
        sb.append("status ").append(s.status).append(", role ").append(s.role).append(", peers ").append(s.peersSeen).append('\n')
        sb.append("tuning: baselineShift=").append(SongTapTuning.BASELINE_SHIFT)
            .append(" fastShift=").append(SongTapTuning.FAST_SHIFT)
            .append(" jumpDb=").append(SongTapTuning.JUMP_DB)
            .append(" freezeDb=").append(SongTapTuning.FREEZE_DB)
            .append(" holdMs=").append(SongTapTuning.HOLD_MS)
            .append(" dwellMs=").append(SongTapTuning.DWELL_MS)
            .append(" impulseWindowMs=").append(SongTapTuning.IMPULSE_WINDOW_MS)
            .append(" cooldownMs=").append(SongTapTuning.COOLDOWN_MS)
            .append(" nearEnter=").append(SongTapTuning.NEAR_ENTER_DBM)
            .append(" nearExit=").append(SongTapTuning.NEAR_EXIT_DBM)
            .append(" listEnter=").append(SongTapTuning.LIST_ENTER_DBM)
            .append(" listExit=").append(SongTapTuning.LIST_EXIT_DBM)
            .append(" closeHoldDbm=").append(SongTapTuning.CLOSE_HOLD_DBM)
            .append(" closeHoldMs=").append(SongTapTuning.CLOSE_HOLD_MS)
            .append(" advertTxPower=").append(BleTuning.ADVERT_TX_POWER)
            .append(" jerkMilliG=").append(SongTapTuning.JERK_MILLI_G).append('\n')
        sb.append("last events (ms since boot, oldest first):\n")
        for (e in _events.value) sb.append("  ").append(e.tMs).append("  ").append(e.text).append('\n')
        sb.append("recent signal (t, rssi, smoothed, baseline), oldest first:\n")
        val start = if (ringCount < RING) 0 else ringNext
        for (i in 0 until ringCount) {
            val k = (start + i) % RING
            sb.append("  ").append(ringT[k]).append(", ").append(ringRssi[k]).append(", ").append(ringSmoothed[k]).append(", ").append(ringBaseline[k]).append('\n')
        }
        sb.toString()
    }

    companion object {
        private const val NL = '\n'
        const val EVENT_CAPACITY = 20
        const val RING = 150
        const val PUBLISH_EVERY_MS = 150L
    }
}
