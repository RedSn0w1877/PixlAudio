package com.theveloper.pixelplay.data.songtap.ble

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.songtap.QrTransport
import com.theveloper.pixelplay.data.songtap.SongTapLibrary
import com.theveloper.pixelplay.data.songtap.SongTapReceiver
import com.theveloper.pixelplay.data.songtap.SongTapSender
import com.theveloper.pixelplay.data.songtap.SongTapSettings
import com.theveloper.pixelplay.data.songtap.SongTapSigner
import com.theveloper.pixelplay.data.songtap.SongTapSongIds
import com.theveloper.pixelplay.data.songtap.SongTapVerifier
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SongTapNotice
import com.theveloper.pixelplay.presentation.viewmodel.SongTapStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapLink
import com.theveloper.pixelplay.songtap.SongTapNames
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.SongTapSession
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapTimeline
import com.theveloper.pixelplay.songtap.SongTapWire
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Two phones, whole: the real session state machine, the real controller and the real Bluetooth transport on each,
 * with the radios wired to each other. A "touch" is both phones' signal jumping. What is not real is the platform's
 * Bluetooth, the clock, and the song library.
 *
 * Since spec 5 the sender's listener says yes to a phone first ([Duo.approve]); the receiver then fetches the offer before
 * the tap, and the tap is the commit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoPhonesTest {

    /** A radio whose other end is another [LinkedRadio]: what one writes, the other's server receives. */
    private class LinkedRadio(val name: String) : BleRadio {
        lateinit var peer: LinkedRadio
        var listener: BleRadioListener? = null
        var linkUp = true
        val calls = ArrayList<String>()
        var advertised = ""

        override fun start(role: Role, listener: BleRadioListener, name: String): BleStatus { this.listener = listener; advertised = name; calls += "start"; return BleStatus.ACTIVE }
        override fun stop() { calls += "stop"; listener = null }
        override fun connect(address: String) { calls += "connect"; listener?.onConnected(address, linkUp) }
        override fun disconnect(address: String) { calls += "disconnect" }
        override fun readInfo(address: String) { listener?.onInfoRead(address, peer.listener?.serverInfo()) }
        override fun readName(address: String) { calls += "readName"; listener?.onNameRead(address, if (linkUp) peer.listener?.serverName() else null) }
        override fun readOffer(address: String) {
            calls += "readOffer"
            val value = if (linkUp) peer.listener?.serverOffer(name) else null
            listener?.onOfferRead(address, value)
        }
        override fun writeControl(address: String, value: ByteArray) {
            val ok = linkUp && peer.listener?.serverControl(name, value) == true
            listener?.onControlWritten(address, ok)
        }
        override fun notify(client: String, value: ByteArray) { if (linkUp) peer.listener?.onNotified(name, value) }
        override fun dropClient(client: String) { calls += "drop" }
        fun drop() { linkUp = false; peer.listener?.onDisconnected(name); listener?.serverClientGone(peer.name) }
    }

    private class Library : SongTapLibrary {
        /** When set, a save waits for it: the save is slow, and the test looks at what has already happened. */
        var gate: CompletableDeferred<Unit>? = null
        val videos = mutableSetOf<String>()
        val removed = mutableListOf<String>()
        var found: String? = null
        override suspend fun hasVideo(videoId: String) = videoId in videos
        override suspend fun hasSpotifyId(spotifyId: String) = false
        override suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?) = found
        override suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?): Boolean {
            gate?.await()
            return videos.add(videoId)
        }
        override suspend fun remove(videoId: String): Boolean { removed += videoId; return videos.remove(videoId) }
        override suspend fun songId(videoId: String) = "id-$videoId"
        override suspend fun songIdForSpotify(spotifyId: String) = "sp-$spotifyId"
    }

    private class Ids(val video: String?) : SongTapSongIds {
        override suspend fun videoIdFor(spotifyId: String) = video
        override suspend fun isrcFor(spotifyId: String): String? = null
    }

    private fun song(id: String, title: String = "Never Gonna Give You Up") = Song(
        id = id, title = title, artist = "Rick Astley", artistId = 1L, album = "Album", albumId = 1L, path = "",
        contentUriString = "spotify://x", albumArtUriString = "https://i.scdn.co/image/abc", duration = 213_000L, mimeType = null,
        bitrate = null, sampleRate = null, spotifyId = "4uLU6hMCjMI75M1A2tKUQC",
    )

    private inner class Phone(
        val scope: TestScope, val name: String, val clock: TestClock, playing: Song?, firstBeamAsks: Boolean = false,
        video: String? = "dQw4w9WgXcQ", val ownName: String = name,
    ) {
        val radio = LinkedRadio(name)
        val library = Library()
        val impulses = FakeImpulses()
        val haptics = ArrayList<HapticCue>()
        val transport = BleTransport(radio, impulses, clock, SongTapDiagnostics(), scope.backgroundScope, { SongTapSigner.create() }, SongTapVerifier) { b -> b.fill(0x42) }
        val holder: SongTapStateHolder

        init {
            val settings = mockk<SongTapSettings>(relaxed = true)
            every { settings.allowed } returns flowOf(true)
            coEvery { settings.isAllowed() } returns true
            coEvery { settings.doesFirstBeamAsk() } returns firstBeamAsks
            coEvery { settings.nameNow() } returns ownName
            // The rig reads like an iPhone (offset 0) and the default profile, unless a test says otherwise.
            coEvery { settings.rssiOffsetNow() } returns 0
            coEvery { settings.sensitivityNow() } returns SongTapSensitivity.HIGH
            every { settings.rssiOffsetDb } returns flowOf(0)
            every { settings.sensitivity } returns flowOf(SongTapSensitivity.HIGH)
            val playback = mockk<PlaybackStateHolder>()
            every { playback.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = playing))
            holder = SongTapStateHolder(settings, SongTapReceiver(library), SongTapSender(Ids(video)), QrTransport(), transport, SongTapDiagnostics(), playback, scope.backgroundScope)
            holder.reducedMotion = { false }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { holder.haptics.collect { haptics += it } }
        }

        val phase get() = holder.ui.value.phase
        fun see(other: String, role: Role, rssi: Int, name: String? = null) = radio.listener!!.onSeen(other, role, rssi, name)
    }

    private class Duo(val sender: Phone, val receiver: Phone, val clock: TestClock, val scope: TestScope) {
        fun step(rssi: Int) {
            clock.advance(100)
            // What a scan reports is the short name the other phone's radio was told to advertise.
            sender.see("B", Role.RECEIVE, rssi, SongTapNames.advertName(receiver.ownName))
            receiver.see("A", Role.SEND, rssi, SongTapNames.advertName(sender.ownName))
            scope.testScheduler.runCurrent()
        }
        /** Only the sender's radio hears the other phone this time (the other phone's own reading did not move). */
        fun stepSenderOnly(rssi: Int) {
            clock.advance(100)
            sender.see("B", Role.RECEIVE, rssi, SongTapNames.advertName(receiver.ownName))
            scope.testScheduler.runCurrent()
        }

        fun stepReceiverOnly(rssi: Int) {
            clock.advance(100)
            receiver.see("A", Role.SEND, rssi, SongTapNames.advertName(sender.ownName))
            scope.testScheduler.runCurrent()
        }

        fun settle(rssi: Int = BleRig.NEAR_DBM, samples: Int = 8) = repeat(samples) { step(rssi) }
        fun touch(rssi: Int = BleRig.TOUCH_DBM, samples: Int = 8) = repeat(samples) { step(rssi) }

        /** The sender's listener answers "Song drop to <the phone in the list>?" with yes. */
        fun approve() {
            val entry = sender.holder.ui.value.nearby.first()
            sender.holder.approve(entry.id, entry.name)
            scope.testScheduler.runCurrent()
        }
    }

    private fun TestScope.pair(firstBeamAsks: Boolean = false, receiverLibrary: (Library) -> Unit = {}, senderName: String = "Anna", receiverName: String = "Bea", playing: Song? = song("1")): Duo {
        val clock = TestClock()
        val a = Phone(this, "A", clock, playing, ownName = senderName)
        val b = Phone(this, "B", clock, null, firstBeamAsks, ownName = receiverName)
        a.radio.peer = b.radio
        b.radio.peer = a.radio
        receiverLibrary(b.library)
        a.holder.attach()
        b.holder.attach()
        a.holder.arm(Role.SEND)
        b.holder.arm(Role.RECEIVE)
        testScheduler.runCurrent()
        return Duo(a, b, clock, this)
    }

    private fun TestScope.passTime(ms: Long) {
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    @Test
    fun `touching two phones sends the song, saves it and plays both animations to the end`() = runTest {
        val p = pair()
        assertThat(p.sender.phase).isEqualTo(Phase.ARMED)
        assertThat(p.receiver.holder.ui.value.bluetooth).isEqualTo(BleStatus.ACTIVE)

        p.settle()
        assertThat(p.sender.phase).isAnyOf(Phase.FOUND, Phase.NEAR)
        assertThat(p.receiver.phase).isAnyOf(Phase.FOUND, Phase.NEAR)
        assertThat(p.sender.holder.glow.value).isGreaterThan(0)
        // Before a yes nothing is on offer: the receiver finds nothing to fetch.
        assertThat(p.receiver.holder.ui.value.session.pre).isFalse()

        p.approve()
        // The yes puts the offer up for that phone only, and the receiver fetches and checks it at once, before any touch.
        assertThat(p.sender.holder.ui.value.session.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.sender.holder.ui.value.session.offerRead).isTrue()
        assertThat(p.receiver.holder.ui.value.session.pre).isTrue()
        assertThat(p.receiver.radio.calls.count { it == "readOffer" }).isAtLeast(1)
        val readsBeforeTap = p.receiver.radio.calls.count { it == "readOffer" }

        p.touch()
        passTime(1_000)

        // Both sides are in the beam, the song is in the receiver's library, and nothing was asked.
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
        assertThat(p.receiver.phase).isEqualTo(Phase.SAVED)
        assertThat(p.receiver.holder.ui.value.card!!.title).isEqualTo("Never Gonna Give You Up")
        // The tap only handed over what was in hand: no offer was read after it.
        assertThat(p.receiver.radio.calls.count { it == "readOffer" }).isEqualTo(readsBeforeTap)
        // Each phone starts its own beam at its own commit: no go, no delay, so the two start within one scan interval of each other.
        val skew = p.receiver.holder.ui.value.beamStartMs - p.sender.holder.ui.value.beamStartMs
        assertThat(skew).isAtMost(2_000L)
        assertThat(skew).isAtLeast(-2_000L)

        passTime(SongTapTimeline.RECEIVE_TOTAL_MS + 500)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        assertThat(p.sender.phase).isAnyOf(Phase.SAVED, Phase.ARMED, Phase.FOUND, Phase.NEAR)
        assertThat(p.receiver.phase).isAnyOf(Phase.ARMED, Phase.FOUND, Phase.NEAR)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
    }

    @Test
    fun `the first haptic and the beam come at the tap, before the save is done and before the sender has heard anything`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = pair(receiverLibrary = { it.gate = gate })
        p.settle()
        p.approve()
        p.touch()
        // The save is still waiting on its gate. Everything the listener can feel has already happened.
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.haptics).containsExactly(HapticCue.TICK)
        assertThat(p.sender.haptics).containsExactly(HapticCue.TICK)
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        assertThat(p.receiver.phase).isEqualTo(Phase.TRANSFERRING)
        assertThat(p.sender.holder.ui.value.session.acked).isFalse()

        gate.complete(Unit)
        passTime(100)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.phase).isEqualTo(Phase.SAVED)
        assertThat(p.sender.holder.ui.value.session.acked).isTrue()
    }

    @Test
    fun `the first beam a receiver ever gets asks at the tap, the sender already beaming, and a yes saves it`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        // The sender's commit is its own: its beam is playing while the other phone's listener decides.
        assertThat(p.sender.phase).isEqualTo(Phase.TRANSFERRING)
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)

        p.receiver.holder.accept()
        passTime(1_000)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
    }

    @Test
    fun `not now tells the sender, and nothing is saved`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        p.receiver.holder.decline()
        passTime(500)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.phase).isEqualTo(Phase.DECLINED)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `undo inside the card's window takes the song out again`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        p.touch()
        passTime(2_000)
        assertThat(p.receiver.library.videos).hasSize(1)
        p.receiver.holder.undo()
        passTime(100)
        assertThat(p.receiver.library.removed).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `the link dropping after the offer was fetched still saves the song, and the sender gives up after its own timeout`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        p.receiver.radio.linkUp = false // writes and reads fail from here: the answer to the sender cannot get through
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        passTime(SongTapSession.ACK_TIMEOUT_MS + 500)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.sender.holder.ui.value.notice).isEqualTo(SongTapNotice.TIMEOUT)
    }

    @Test
    fun `when no offer is in hand at the tap nothing is beamed on either phone, and the sender gives up after a minute`() = runTest {
        val p = pair()
        p.receiver.radio.linkUp = false // the receiver cannot connect: it has fetched nothing
        p.settle()
        p.approve()
        p.touch()
        passTime(15_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.phase).isNotEqualTo(Phase.SAVED)
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        // The sender's own tap came, but nobody had read its offer: no beam, still armed to that phone.
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.sender.phase).isEqualTo(Phase.OFFERED)
        passTime(SongTapSession.TARGET_TIMEOUT_MS + 2_000)
        assertThat(p.sender.phase).isAnyOf(Phase.CANCELLED, Phase.ARMED, Phase.FOUND, Phase.NEAR)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `the other phone vanishing mid-beam is a lost peer, with a notice`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        p.sender.radio.drop()
        passTime(100)
        // The receiver left while the sender waited for its answer: lost peer on the sender.
        assertThat(p.sender.phase).isEqualTo(Phase.FAILED)
        assertThat(p.sender.holder.ui.value.notice).isEqualTo(SongTapNotice.PEER_LOST)
    }

    @Test
    fun `the first beam's Add still saves the song after the phones came apart`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        p.sender.radio.drop()
        passTime(100)
        // Pulling the phones apart after a tap is normal: the card is still there and Add still works.
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        p.receiver.holder.accept()
        passTime(1_000)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.phase).isEqualTo(Phase.SAVED)
        assertThat(p.receiver.library.removed).isEmpty()
    }

    @Test
    fun `a code scanned while a card waits for Add does not replace the card or what Undo would remove`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        val other = SongTapPayloads.make(
            nowMs = System.currentTimeMillis(), ytVideoId = "aaaaaaaaaaa", spotifyId = null, isrc = null,
            title = "Another song", artist = "Someone", durationMs = 200_000L, artUrl = null,
        )!!
        p.receiver.holder.onScanned(SongTapLink.encode(SongTapPayloads.encode(other)!!))
        passTime(100)
        assertThat(p.receiver.holder.ui.value.card!!.title).isEqualTo("Never Gonna Give You Up")
        p.receiver.holder.accept()
        passTime(1_000)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        p.receiver.holder.undo()
        passTime(100)
        assertThat(p.receiver.library.removed).containsExactly("dQw4w9WgXcQ")
    }

    @Test
    fun `an arm that is overtaken by a disarm leaves nothing running`() = runTest {
        val clock = TestClock()
        val a = Phone(this, "A", clock, song("1"))
        a.holder.attach()
        a.holder.arm(Role.SEND)
        a.holder.disarm() // before the arm's settings reads have run
        testScheduler.runCurrent()
        assertThat(a.phase).isEqualTo(Phase.IDLE)
        assertThat(a.radio.calls).doesNotContain("start")
        assertThat(a.impulses.running).isFalse()
    }

    @Test
    fun `cancelling on the sender after the beam started closes it and the receiver gives up`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        p.sender.holder.cancel()
        assertThat(p.sender.phase).isEqualTo(Phase.CANCELLED)
        passTime(60_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `both phones sending is told to the listener, not tried`() = runTest {
        val clock = TestClock()
        val a = Phone(this, "A", clock, song("1"))
        val b = Phone(this, "B", clock, song("2"))
        a.radio.peer = b.radio
        b.radio.peer = a.radio
        a.holder.attach(); b.holder.attach()
        a.holder.arm(Role.SEND); b.holder.arm(Role.SEND)
        testScheduler.runCurrent()
        repeat(8) { clock.advance(100); a.see("B", Role.SEND, BleRig.NEAR_DBM); b.see("A", Role.SEND, BleRig.NEAR_DBM); testScheduler.runCurrent() }
        repeat(8) { clock.advance(100); a.see("B", Role.SEND, BleRig.TOUCH_DBM); b.see("A", Role.SEND, BleRig.TOUCH_DBM); testScheduler.runCurrent() }
        assertThat(a.phase).isEqualTo(Phase.FAILED)
        assertThat(a.holder.ui.value.notice).isEqualTo(SongTapNotice.BOTH_SENDING)
        assertThat(b.phase).isEqualTo(Phase.FAILED)
        assertThat(a.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `a sender with nothing playing says so and the receiver is not left waiting`() = runTest {
        val p = pair(playing = null)
        p.settle()
        // Nothing is playing, so the picker has nothing to offer: a yes says so.
        val entry = p.sender.holder.ui.value.nearby.first()
        p.sender.holder.approve(entry.id, entry.name)
        testScheduler.runCurrent()
        p.touch()
        passTime(2_000)
        assertThat(p.sender.holder.ui.value.notice).isEqualTo(SongTapNotice.NOTHING_TO_SEND)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.phase).isNotEqualTo(Phase.SAVED)
    }

    @Test
    fun `a second touch of the same song within a minute is not saved twice`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        p.touch()
        passTime(SongTapTimeline.RECEIVE_TOTAL_MS + 2_000)
        assertThat(p.receiver.library.videos).hasSize(1)
        // Apart, and together again.
        repeat(6) { p.step(-85) }
        passTime(3_000)
        p.settle()
        p.approve()
        p.touch()
        passTime(2_000)
        assertThat(p.receiver.library.videos).hasSize(1)
        assertThat(p.receiver.library.removed).isEmpty()
    }

    @Test
    fun `leaving the screen stops the radio and the sensor on both phones`() = runTest {
        val p = pair()
        p.settle()
        p.sender.holder.disarm()
        p.receiver.holder.disarm()
        passTime(100)
        assertThat(p.sender.radio.calls.last()).isEqualTo("stop")
        assertThat(p.receiver.radio.calls.last()).isEqualTo("stop")
        assertThat(p.sender.impulses.running).isFalse()
        assertThat(p.receiver.impulses.running).isFalse()
        assertThat(p.sender.phase).isEqualTo(Phase.IDLE)
    }

    @Test
    fun `the beam's own haptics replace the session's success buzz while it plays`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        p.touch()
        passTime(2_000)
        // The tap's tick came from the session, once on each phone; no success from the session while the beam plays.
        assertThat(p.receiver.haptics).doesNotContain(HapticCue.SUCCESS)
        assertThat(p.receiver.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
        assertThat(p.sender.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
    }

    // ── The one-sided tap (spec 6) ───────────────────────────────────────────────────

    @Test
    fun `the sender feeling the tap first beams both phones, while the receiver's own signal never moves`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        assertThat(p.receiver.holder.ui.value.session.pre).isTrue()
        repeat(4) { p.stepSenderOnly(BleRig.TOUCH_DBM) }
        passTime(1_000)
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
        assertThat(p.sender.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
        // The receiver's own detector never fired: it took the sender's word.
        assertThat(p.receiver.holder.ui.value.session.pre).isFalse()
    }

    @Test
    fun `the receiver feeling the tap first beams both phones, while the sender's own signal never moves`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        repeat(4) { p.stepReceiverOnly(BleRig.TOUCH_DBM) }
        passTime(1_000)
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
        assertThat(p.sender.haptics.count { it == HapticCue.TICK }).isEqualTo(1)
    }

    @Test
    fun `the sender's tap is not believed by a receiver that is a few metres away, and the sender's beam times out`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        // The receiver's own signal for the sender fell to a few metres (it has not noticed the sender is gone yet: its link is still up).
        repeat(4) { p.stepReceiverOnly(BleRig.LIST_ONLY_DBM) }
        repeat(4) { p.stepSenderOnly(BleRig.TOUCH_DBM) }
        passTime(1_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.haptics).isEmpty()
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        passTime(SongTapSession.ACK_TIMEOUT_MS + 500)
        assertThat(p.sender.holder.ui.value.notice).isEqualTo(SongTapNotice.TIMEOUT)
    }

    @Test
    fun `a tap with no yes from the sender beams nothing, on either side`() = runTest {
        val p = pair()
        p.settle()
        // No approve: the receiver holds no offer and the sender serves none.
        repeat(4) { p.stepSenderOnly(BleRig.TOUCH_DBM) }
        repeat(4) { p.stepReceiverOnly(BleRig.TOUCH_DBM) }
        passTime(1_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
    }


    // ── Song Drop: names, the prompt, the picker, the target ─────────────────────────

    @Test
    fun `the picker lists the phone with its name, and the prompt asks when it comes inside the strict gate`() = runTest {
        val p = pair()
        p.settle(rssi = BleRig.LIST_ONLY_DBM, samples = 4)
        // A few metres away: in the list, with its name, but nothing connects and nobody is asked yet.
        val listed = p.sender.holder.ui.value.nearby.single()
        assertThat(listed.name).isEqualTo("Bea")
        assertThat(listed.near).isFalse()
        assertThat(p.sender.holder.ui.value.prompt).isNull()
        assertThat(p.receiver.radio.calls).doesNotContain("connect")
        assertThat(p.sender.holder.ui.value.session.phase).isAnyOf(Phase.FOUND, Phase.NEAR)

        // Walking up, slowly (no jump): inside the strict gate the sender's listener is asked "Song drop to Bea?".
        for (rssi in listOf(-78, -76, -74, -72, -70, -68, -66, -64, -62, -60, -60, -60)) p.step(rssi)
        val asked = p.sender.holder.ui.value.prompt!!
        assertThat(asked.name).isEqualTo("Bea")
        assertThat(asked.near).isTrue()
        assertThat(p.receiver.radio.calls).contains("connect")
    }

    @Test
    fun `no to the prompt ignores that phone for a minute, and a touch then beams nothing`() = runTest {
        val p = pair()
        p.settle()
        val asked = p.sender.holder.ui.value.prompt!!
        p.sender.holder.decideNo(asked.id)
        testScheduler.runCurrent()
        assertThat(p.sender.holder.ui.value.prompt).isNull()
        p.touch()
        passTime(1_000)
        // Nobody said yes: the receiver is never given an offer, and neither phone beams.
        assertThat(p.receiver.holder.ui.value.session.pre).isFalse()
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        assertThat(p.sender.phase).isNotEqualTo(Phase.TRANSFERRING)
    }

    @Test
    fun `yes arms the beam to that phone, and cancelling puts the offer away`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        val armed = p.sender.holder.ui.value
        assertThat(armed.session.phase).isEqualTo(Phase.OFFERED)
        assertThat(armed.session.target).isNotEqualTo(0)
        assertThat(armed.peerName).isEqualTo("Bea")
        assertThat(armed.beamRole).isNull()
        assertThat(armed.prompt).isNull() // armed: nothing more to ask

        p.sender.holder.cancelDrop()
        passTime(100)
        assertThat(p.sender.phase).isAnyOf(Phase.ARMED, Phase.FOUND, Phase.NEAR)
        assertThat(p.sender.holder.ui.value.session.target).isEqualTo(0)
        // The offer is gone: a touch now beams nothing.
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `a sender stays armed to its phone for a minute and then lets go`() = runTest {
        val p = pair()
        p.settle()
        p.approve()
        passTime(30_000)
        assertThat(p.sender.phase).isEqualTo(Phase.OFFERED)
        passTime(35_000)
        assertThat(p.sender.phase).isNotEqualTo(Phase.OFFERED)
        assertThat(p.sender.holder.ui.value.session.target).isEqualTo(0)
    }

    @Test
    fun `the receiver's cards say who the song is from, with the full name`() = runTest {
        val long = "\u00e9".repeat(20) // 40 bytes: only 12 of them fit the advertisement
        val p = pair(firstBeamAsks = true, senderName = long)
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        // The consent card names the sender with the whole name, read over the connection, not the cut one from the advertisement.
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.receiver.holder.ui.value.peerName).isEqualTo(long)
        p.receiver.holder.accept()
        passTime(1_000)
        assertThat(p.receiver.holder.ui.value.peerName).isEqualTo(long)
    }

    @Test
    fun `a song picked from a song's options is offered instead of the one that plays`() = runTest {
        val p = pair()
        val other = song("2", title = "Another song")
        var raised = false
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { p.sender.holder.dropRequests.first(); raised = true }
        p.sender.holder.beginSongDrop(other)
        testScheduler.runCurrent()
        assertThat(raised).isTrue()
        job.cancel()
        assertThat(p.sender.holder.ui.value.nowPlaying!!.title).isEqualTo("Another song")
        assertThat(p.sender.holder.ui.value.dropMode).isTrue()
        p.settle()
        p.approve()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.holder.ui.value.card!!.title).isEqualTo("Another song")
        // Leaving the screen puts the playing song back.
        p.sender.holder.disarm()
        assertThat(p.sender.holder.ui.value.dropMode).isFalse()
        assertThat(p.sender.holder.ui.value.nowPlaying!!.title).isEqualTo("Never Gonna Give You Up")
    }

    @Test
    fun `the phones advertise their own names`() = runTest {
        val p = pair(senderName = "Anna's Pixel", receiverName = "Bea")
        assertThat(p.sender.radio.advertised).isEqualTo("Anna's Pixel")
        assertThat(p.receiver.radio.advertised).isEqualTo("Bea")
    }
}
