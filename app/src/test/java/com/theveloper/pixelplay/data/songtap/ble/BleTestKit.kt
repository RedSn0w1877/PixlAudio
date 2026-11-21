package com.theveloper.pixelplay.data.songtap.ble

import com.theveloper.pixelplay.data.songtap.SongTapSigner
import com.theveloper.pixelplay.data.songtap.SongTapVerifier
import com.theveloper.pixelplay.data.songtap.TransportEvent
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.SongTapWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope

/** A clock the test moves by hand. */
class TestClock(var elapsed: Long = 10_000L, var wall: Long = 1_763_600_000_000L) : SongTapClock {
    override fun elapsedMs() = elapsed
    override fun wallMs() = wall
    fun advance(ms: Long) { elapsed += ms; wall += ms }
}

class FakeImpulses : ImpulseSource {
    var running = false
    var profile = SongTapSensitivity.DEFAULT
    private var callback: ((Long) -> Unit)? = null
    override fun start(onImpulse: (Long) -> Unit) { running = true; callback = onImpulse }
    override fun stop() { running = false; callback = null }
    override fun setSensitivity(profile: SongTapSensitivity) { this.profile = profile }
    fun jerk(atMs: Long) { callback?.invoke(atMs) }
}

/** A radio that only records what it is asked and lets the test play the platform's part. */
class FakeRadio : BleRadio {
    lateinit var listener: BleRadioListener
    var startStatus = BleStatus.ACTIVE
    var running = false
    var role = Role.SEND
    val calls = ArrayList<String>()
    val controlWrites = ArrayList<ByteArray>()
    val notifications = ArrayList<Pair<String, ByteArray>>()
    val dropped = ArrayList<String>()
    var advertisedName = ""

    override fun start(role: Role, listener: BleRadioListener, name: String): BleStatus {
        this.listener = listener
        this.role = role
        this.advertisedName = name
        calls += "start:${role.code}"
        running = startStatus == BleStatus.ACTIVE
        return startStatus
    }

    override fun stop() { calls += "stop"; running = false }
    override fun connect(address: String) { calls += "connect:$address" }
    override fun disconnect(address: String) { calls += "disconnect:$address" }
    override fun readInfo(address: String) { calls += "readInfo:$address" }
    override fun readName(address: String) { calls += "readName:$address" }
    override fun readOffer(address: String) { calls += "readOffer:$address" }
    override fun writeControl(address: String, value: ByteArray) { calls += "write:$address"; controlWrites += value }
    override fun notify(client: String, value: ByteArray) { calls += "notify:$client"; notifications += client to value }
    override fun dropClient(client: String) { calls += "drop:$client"; dropped += client }

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}

/** One transport with everything around it under the test's hand. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleRig(testScope: TestScope, val diag: SongTapDiagnostics = SongTapDiagnostics()) {
    val radio = FakeRadio()
    val clock = TestClock()
    val impulses = FakeImpulses()
    val events = ArrayList<TransportEvent>()
    private var randomByte = 0
    val transport = BleTransport(
        radio = radio,
        impulses = impulses,
        clock = clock,
        diag = diag,
        scope = testScope.backgroundScope,
        newSigner = { SongTapSigner.create() },
        verifier = SongTapVerifier,
        random = { b -> for (i in b.indices) b[i] = (++randomByte).toByte() },
    )

    init {
        (testScope.backgroundScope as CoroutineScope).launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
            transport.events.collect { events += it }
        }
    }

    /** One scan result, [stepMs] after the last. */
    fun see(address: String, role: Role, rssi: Int, stepMs: Long = 100L, name: String? = null) {
        clock.advance(stepMs)
        radio.listener.onSeen(address, role, rssi, name)
    }

    /**
     * A phone resting 20 to 30 cm away: inside the tap near gate (-70, on the iPhone scale: the rig's offset is 0 unless a test sets one) and
     * well below the hold level (-45), long enough for the detector to settle. The first reading is the baseline, so there is no jump.
     */
    fun settle(address: String, role: Role, rssi: Int = NEAR_DBM, samples: Int = 8, name: String? = null) {
        repeat(samples) { see(address, role, rssi, name = name) }
    }

    /** The signal jumps 27 dB and stays: the phones touch (the dwell fires at the third reading, a jerk makes it the second/third). */
    fun touch(address: String, role: Role, rssi: Int = TOUCH_DBM, samples: Int = 8) {
        repeat(samples) { see(address, role, rssi) }
    }

    companion object {
        /** Resting 20 to 30 cm apart, about -60 on the iPhone scale. */
        const val NEAR_DBM = -60

        /** Touching, about -33 on the iPhone scale. */
        const val TOUCH_DBM = -33

        /** Inside the broad list gate (-85) and outside the tap near gate (-70). */
        const val LIST_ONLY_DBM = -80
    }

    inline fun <reified T : TransportEvent> of(): List<T> = events.filterIsInstance<T>()

    fun lastControl(): SongTapWire.ControlMessage? = controlWritesDecoded().lastOrNull()

    fun controlWritesDecoded(): List<SongTapWire.ControlMessage?> = radio.controlWrites.map { SongTapWire.decodeControl(it) }
}
