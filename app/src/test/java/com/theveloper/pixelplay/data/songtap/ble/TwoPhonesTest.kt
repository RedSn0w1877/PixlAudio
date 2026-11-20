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
import com.theveloper.pixelplay.songtap.SongTapTimeline
import com.theveloper.pixelplay.songtap.SongTapWire
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoPhonesTest {

    /** A radio whose other end is another [LinkedRadio]: what one writes, the other's server receives. */
    private class LinkedRadio(val name: String) : BleRadio {
        lateinit var peer: LinkedRadio
        var listener: BleRadioListener? = null
        var linkUp = true
        val calls = ArrayList<String>()

        override fun start(role: Role, listener: BleRadioListener): BleStatus { this.listener = listener; calls += "start"; return BleStatus.ACTIVE }
        override fun stop() { calls += "stop"; listener = null }
        override fun connect(address: String) { calls += "connect"; listener?.onConnected(address, linkUp) }
        override fun disconnect(address: String) { calls += "disconnect" }
        override fun readInfo(address: String) { listener?.onInfoRead(address, peer.listener?.serverInfo()) }
        override fun readOffer(address: String) {
            val value = if (linkUp) peer.listener?.serverOffer(name) else null
            listener?.onOfferRead(address, value)
        }
        override fun writeControl(address: String, value: ByteArray) {
            val ok = linkUp && peer.listener?.serverControl(name, value) == true
            listener?.onControlWritten(address, ok)
        }
        override fun notifyGo(client: String) { if (linkUp) peer.listener?.onNotified(name, SongTapWire.encodeGo()) }
        override fun dropClient(client: String) { calls += "drop" }
        fun drop() { linkUp = false; peer.listener?.onDisconnected(name); listener?.serverClientGone(peer.name) }
    }

    private class Library : SongTapLibrary {
        val videos = mutableSetOf<String>()
        val removed = mutableListOf<String>()
        var found: String? = null
        override suspend fun hasVideo(videoId: String) = videoId in videos
        override suspend fun hasSpotifyId(spotifyId: String) = false
        override suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?) = found
        override suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?) = videos.add(videoId)
        override suspend fun remove(videoId: String): Boolean { removed += videoId; return videos.remove(videoId) }
        override suspend fun songId(videoId: String) = "id-$videoId"
        override suspend fun songIdForSpotify(spotifyId: String) = "sp-$spotifyId"
    }

    private class Ids(val video: String?) : SongTapSongIds {
        override suspend fun videoIdFor(spotifyId: String) = video
        override suspend fun isrcFor(spotifyId: String): String? = null
    }

    private fun song(id: String) = Song(
        id = id, title = "Never Gonna Give You Up", artist = "Rick Astley", artistId = 1L, album = "Album", albumId = 1L, path = "",
        contentUriString = "spotify://x", albumArtUriString = "https://i.scdn.co/image/abc", duration = 213_000L, mimeType = null,
        bitrate = null, sampleRate = null, spotifyId = "4uLU6hMCjMI75M1A2tKUQC",
    )

    private inner class Phone(val scope: TestScope, val name: String, val clock: TestClock, playing: Song?, firstBeamAsks: Boolean = false, video: String? = "dQw4w9WgXcQ") {
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
            val playback = mockk<PlaybackStateHolder>()
            every { playback.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = playing))
            holder = SongTapStateHolder(settings, SongTapReceiver(library), SongTapSender(Ids(video)), QrTransport(), transport, SongTapDiagnostics(), playback, scope.backgroundScope)
            holder.reducedMotion = { false }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { holder.haptics.collect { haptics += it } }
        }

        val phase get() = holder.ui.value.phase
        fun see(other: String, role: Role, rssi: Int) = radio.listener!!.onSeen(other, role, rssi)
    }

    private class Duo(val sender: Phone, val receiver: Phone, val clock: TestClock, val scope: TestScope) {
        fun step(rssi: Int) {
            clock.advance(100)
            sender.see("B", Role.RECEIVE, rssi)
            receiver.see("A", Role.SEND, rssi)
            scope.testScheduler.runCurrent()
        }
        fun settle(rssi: Int = -62, samples: Int = 8) = repeat(samples) { step(rssi) }
        fun touch(rssi: Int = -40, samples: Int = 8) = repeat(samples) { step(rssi) }
    }

    private fun TestScope.pair(firstBeamAsks: Boolean = false, receiverLibrary: (Library) -> Unit = {}): Duo {
        val clock = TestClock()
        val a = Phone(this, "A", clock, song("1"))
        val b = Phone(this, "B", clock, null, firstBeamAsks)
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

        p.touch()
        passTime(1_000)

        // Both sides are in the beam, the song is in the receiver's library, and nothing was asked.
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.sender.holder.ui.value.beamRole).isEqualTo(Role.SEND)
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
        assertThat(p.receiver.phase).isEqualTo(Phase.SAVED)
        assertThat(p.receiver.holder.ui.value.card!!.title).isEqualTo("Never Gonna Give You Up")
        // The two start within a few tens of ms of each other (here exactly the handshake's rule).
        val skew = p.receiver.holder.ui.value.beamStartMs - p.sender.holder.ui.value.beamStartMs
        assertThat(skew).isAtMost(60L)
        assertThat(skew).isAtLeast(-200L)

        passTime(SongTapTimeline.RECEIVE_TOTAL_MS + 500)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.phase).isAnyOf(Phase.ARMED, Phase.FOUND, Phase.NEAR)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
    }

    @Test
    fun `the first beam a receiver ever gets asks, and a yes saves it`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.sender.phase).isEqualTo(Phase.OFFERED)

        p.receiver.holder.accept()
        passTime(1_000)
        assertThat(p.receiver.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.receiver.holder.ui.value.beamRole).isEqualTo(Role.RECEIVE)
    }

    @Test
    fun `not now tells the sender, and nothing is saved`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
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
    fun `the link dropping before the offer is read fails the beam on both phones and the session recovers`() = runTest {
        val p = pair()
        p.settle()
        p.receiver.radio.linkUp = false // reads and writes fail from here
        p.touch()
        passTime(15_000)
        assertThat(p.receiver.library.videos).isEmpty()
        assertThat(p.receiver.phase).isNotEqualTo(Phase.SAVED)
        assertThat(p.sender.holder.ui.value.beamRole).isNull()
        assertThat(p.receiver.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `the other phone vanishing mid-offer is a lost peer, with a notice`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
        p.touch()
        passTime(1_000)
        assertThat(p.receiver.phase).isEqualTo(Phase.OFFERED)
        p.sender.radio.drop()
        passTime(100)
        // The sender's receiver left while an offer stood: lost peer on the sender.
        assertThat(p.sender.phase).isEqualTo(Phase.FAILED)
        assertThat(p.sender.holder.ui.value.notice).isEqualTo(SongTapNotice.PEER_LOST)
    }

    @Test
    fun `cancelling on the sender after the offer is up closes it and the receiver gives up`() = runTest {
        val p = pair(firstBeamAsks = true)
        p.settle()
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
        repeat(8) { clock.advance(100); a.see("B", Role.SEND, -62); b.see("A", Role.SEND, -62); testScheduler.runCurrent() }
        repeat(8) { clock.advance(100); a.see("B", Role.SEND, -40); b.see("A", Role.SEND, -40); testScheduler.runCurrent() }
        assertThat(a.phase).isEqualTo(Phase.FAILED)
        assertThat(a.holder.ui.value.notice).isEqualTo(SongTapNotice.BOTH_SENDING)
        assertThat(b.phase).isEqualTo(Phase.FAILED)
        assertThat(a.holder.ui.value.beamRole).isNull()
    }

    @Test
    fun `a sender with nothing playing says so and the receiver is not left waiting`() = runTest {
        val clock = TestClock()
        val a = Phone(this, "A", clock, null)
        val b = Phone(this, "B", clock, null)
        a.radio.peer = b.radio
        b.radio.peer = a.radio
        a.holder.attach(); b.holder.attach()
        a.holder.arm(Role.SEND); b.holder.arm(Role.RECEIVE)
        testScheduler.runCurrent()
        val p = Duo(a, b, clock, this)
        p.settle()
        p.touch()
        passTime(2_000)
        assertThat(a.holder.ui.value.notice).isEqualTo(SongTapNotice.NOTHING_TO_SEND)
        assertThat(b.library.videos).isEmpty()
        assertThat(b.phase).isNotEqualTo(Phase.SAVED)
    }

    @Test
    fun `a second touch of the same song within a minute is not saved twice`() = runTest {
        val p = pair()
        p.settle()
        p.touch()
        passTime(SongTapTimeline.RECEIVE_TOTAL_MS + 2_000)
        assertThat(p.receiver.library.videos).hasSize(1)
        // Apart, and together again.
        repeat(6) { p.step(-85) }
        passTime(3_000)
        p.settle()
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
        p.touch()
        passTime(2_000)
        // The tap's tick came from the session; no success from the session while the beam plays.
        assertThat(p.receiver.haptics).doesNotContain(HapticCue.SUCCESS)
    }
}
