package com.theveloper.pixelplay.data.songtap.ring

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.songtap.QrTransport
import com.theveloper.pixelplay.data.songtap.SongTapLibrary
import com.theveloper.pixelplay.data.songtap.SongTapReceiver
import com.theveloper.pixelplay.data.songtap.SongTapSender
import com.theveloper.pixelplay.data.songtap.SongTapSettings
import com.theveloper.pixelplay.data.songtap.SongTapSongIds
import com.theveloper.pixelplay.data.songtap.ble.BleRig
import com.theveloper.pixelplay.data.songtap.ble.SongTapDiagnostics
import com.theveloper.pixelplay.presentation.viewmodel.PlaybackStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.SongTapCodeKind
import com.theveloper.pixelplay.presentation.viewmodel.SongTapNotice
import com.theveloper.pixelplay.presentation.viewmodel.SongTapRingNote
import com.theveloper.pixelplay.presentation.viewmodel.SongTapStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapLink
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import com.theveloper.pixelplay.songtap.ring.RingScanMessage
import com.theveloper.pixelplay.songtap.ring.RingScanStage
import com.theveloper.pixelplay.songtap.ring.RingUnpack
import com.theveloper.pixelplay.songtap.ring.image.RingScanFailure
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The ring code's flow on the Song Tap controller, with a fake scanner (results are handed in), a fake lookup and a fake library: the
 * real session state machine, the real rules over time, the real Bluetooth transport over a fake radio. Nothing here needs a camera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RingCodeFlowTest {

    private class Library : SongTapLibrary {
        val videos = mutableSetOf<String>()
        val removed = mutableListOf<String>()
        override suspend fun hasVideo(videoId: String) = videoId in videos
        override suspend fun hasSpotifyId(spotifyId: String) = false
        override suspend fun findVideo(title: String, artist: String, durationMs: Long, isrc: String?, spotifyId: String?): String? = null
        override suspend fun import(videoId: String, title: String, artist: String, durationMs: Long, artUrl: String?): Boolean = videos.add(videoId)
        override suspend fun remove(videoId: String): Boolean { removed += videoId; return videos.remove(videoId) }
        override suspend fun songId(videoId: String) = "id-$videoId"
        override suspend fun songIdForSpotify(spotifyId: String) = "sp-$spotifyId"
    }

    private class Ids(val video: String?) : SongTapSongIds {
        override suspend fun videoIdFor(spotifyId: String) = video
        override suspend fun isrcFor(spotifyId: String): String? = null
    }

    private fun song(id: String = "1") = Song(
        id = id, title = "Never Gonna Give You Up", artist = "Rick Astley", artistId = 1L, album = "Album", albumId = 1L, path = "",
        contentUriString = "spotify://x", albumArtUriString = "https://i.scdn.co/image/abc", duration = 213_000L, mimeType = null,
        bitrate = null, sampleRate = null, spotifyId = "4uLU6hMCjMI75M1A2tKUQC",
    )

    private class Phone(scope: TestScope, val role: Role, video: String?, firstBeamAsks: Boolean, playing: Song?) {
        val rig = BleRig(scope)
        val library = Library()
        val resolver = FakeRingResolver()
        private var next = 0

        init {
            rig.clock.wall = 1_763_600_000_000L
        }

        val holder: SongTapStateHolder

        init {
            val settings = mockk<SongTapSettings>(relaxed = true)
            every { settings.allowed } returns flowOf(true)
            coEvery { settings.isAllowed() } returns true
            coEvery { settings.doesFirstBeamAsk() } returns firstBeamAsks
            coEvery { settings.nameNow() } returns "Anna"
            coEvery { settings.rssiOffsetNow() } returns 0
            coEvery { settings.sensitivityNow() } returns SongTapSensitivity.HIGH
            every { settings.rssiOffsetDb } returns flowOf(0)
            every { settings.sensitivity } returns flowOf(SongTapSensitivity.HIGH)
            val playback = mockk<PlaybackStateHolder>()
            every { playback.stablePlayerState } returns MutableStateFlow(StablePlayerState(currentSong = playing))
            holder = SongTapStateHolder(
                settings, SongTapReceiver(library), SongTapSender(Ids(video)), QrTransport(), rig.transport, SongTapDiagnostics(),
                resolver, RingCodeDiagnostics(), playback, scope.backgroundScope,
            )
            holder.reducedMotion = { false }
            holder.clock = { rig.clock.wall }
            holder.ringRandom = { ++next * 37 % 256 }
        }

        val ui get() = holder.ui.value
        val bucket get() = RingCodePayloads.bucketOf(rig.clock.wall)

        /** A fresh code for [id] as the camera would read it: a decode of the payload the sender made. */
        fun read(id: String = "dQw4w9WgXcQ", random: Int = 17, ageMinutes: Int = 0): RingScanResult.Decoded =
            RingScanResult.Decoded(RingCodePayload(id, (bucket - ageMinutes + 64) % 64, random), corrected = 1, erasures = 0)

    }

    private fun TestScope.pass(p: Phone, ms: Long) {
        p.rig.clock.advance(ms)
        advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    /** The camera hands in [times] equal results 150 ms apart (three of them are what a steady camera gives for a code). */
    private fun TestScope.scan(p: Phone, result: RingScanResult, times: Int = 3) {
        repeat(times) {
            pass(p, 150)
            p.holder.onRingResult(result)
        }
    }

    private fun TestScope.receiver(firstBeamAsks: Boolean = false): Phone {
        val p = Phone(this, Role.RECEIVE, "dQw4w9WgXcQ", firstBeamAsks, null)
        p.holder.attach()
        p.holder.arm(Role.RECEIVE)
        testScheduler.runCurrent()
        return p
    }

    private fun TestScope.sender(video: String? = "dQw4w9WgXcQ"): Phone {
        val p = Phone(this, Role.SEND, video, false, song())
        p.holder.attach()
        p.holder.arm(Role.SEND)
        testScheduler.runCurrent()
        return p
    }

    private fun decodeBack(bits: ByteArray): RingCodePayload {
        val word = RingCodeLayout.slotsToCodeword(bits)
        val unpacked = RingCodePayloads.fromCodeword(word)
        assertThat(unpacked).isInstanceOf(RingUnpack.Ok::class.java)
        return (unpacked as RingUnpack.Ok).payload
    }

    // ── The sender ───────────────────────────────────────────────────────────────────

    @Test
    fun `a song with a video gets the ring code with this minute and the song's video in it`() = runTest {
        val p = sender()
        p.holder.showCode()
        testScheduler.runCurrent()
        assertThat(p.ui.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.RING)
        assertThat(p.ui.ringNote).isNull()
        val payload = decodeBack(p.ui.ring!!.bits)
        assertThat(payload.videoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(payload.minuteBucket).isEqualTo(p.bucket)
    }

    @Test
    fun `the ring code is made again with a fresh tag every minute`() = runTest {
        val p = sender()
        p.holder.showCode()
        testScheduler.runCurrent()
        val first = p.ui.ring!!
        val firstTag = decodeBack(first.bits).tag()
        pass(p, 59_000)
        assertThat(p.ui.ring).isSameInstanceAs(first)
        pass(p, 2_000)
        val second = p.ui.ring!!
        assertThat(second.serial).isNotEqualTo(first.serial)
        assertThat(decodeBack(second.bits).tag()).isNotEqualTo(firstTag)
        pass(p, 61_000)
        assertThat(p.ui.ring!!.serial).isNotEqualTo(second.serial)
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.RING)
        assertThat(p.ui.phase).isEqualTo(Phase.OFFERED)
    }

    @Test
    fun `a song with no video goes straight to the QR with one line saying why`() = runTest {
        val p = sender(video = null)
        p.holder.showCode()
        testScheduler.runCurrent()
        assertThat(p.ui.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.QR)
        assertThat(p.ui.ringNote).isEqualTo(SongTapRingNote.NO_VIDEO)
        assertThat(p.ui.ring).isNull()
        assertThat(SongTapLink.decode(p.ui.code!!)).isNotNull()
    }

    @Test
    fun `Use QR code instead swaps the ring code for the QR and never shows both`() = runTest {
        val p = sender()
        p.holder.showCode()
        testScheduler.runCurrent()
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.RING)
        p.holder.showQrInstead()
        testScheduler.runCurrent()
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.QR)
        assertThat(p.ui.ring).isNull()
        val decoded = SongTapPayloads.decode(SongTapLink.decode(p.ui.code!!)!!)
        assertThat(decoded).isInstanceOf(com.theveloper.pixelplay.songtap.PayloadDecode.Ok::class.java)
        // Leaving the code brings the ring code back the next time.
        p.holder.hideCode()
        testScheduler.runCurrent()
        assertThat(p.ui.codeKind).isNull()
        p.holder.showCode()
        testScheduler.runCurrent()
        assertThat(p.ui.codeKind).isEqualTo(SongTapCodeKind.RING)
    }

    // ── The receiver ─────────────────────────────────────────────────────────────────

    @Test
    fun `three equal reads look the song up by its video, save it, and Undo takes it back`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(cameraAvailable = true)
        scan(p, p.read())
        testScheduler.runCurrent()
        assertThat(p.resolver.lookups).containsExactly("dQw4w9WgXcQ")
        pass(p, 100)
        assertThat(p.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.ui.phase).isEqualTo(Phase.SAVED)
        assertThat(p.ui.card!!.title).isEqualTo("Never Gonna Give You Up")
        assertThat(p.ui.card!!.artist).isEqualTo("Rick Astley")
        assertThat(p.ui.beamRole).isEqualTo(Role.RECEIVE)
        p.holder.undo()
        pass(p, 100)
        assertThat(p.library.removed).containsExactly("dQw4w9WgXcQ")
    }

    @Test
    fun `one or two reads are not enough`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, p.read(), times = 2)
        testScheduler.runCurrent()
        assertThat(p.resolver.lookups).isEmpty()
        assertThat(p.ui.phase).isEqualTo(Phase.ARMED)
    }

    @Test
    fun `the first beam still asks Add or Not now, for a ring code too`() = runTest {
        val p = receiver(firstBeamAsks = true)
        p.holder.ringScanStart(true)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.ui.phase).isEqualTo(Phase.OFFERED)
        assertThat(p.ui.card!!.title).isEqualTo("Never Gonna Give You Up")
        assertThat(p.library.videos).isEmpty()
        p.holder.accept()
        pass(p, 100)
        assertThat(p.library.videos).containsExactly("dQw4w9WgXcQ")
        assertThat(p.ui.phase).isEqualTo(Phase.SAVED)
    }

    @Test
    fun `the QR is not offered at first and is offered after six seconds of a scan that reads nothing`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.SCANNING)
        assertThat(p.ui.ringScan.qrOffered).isFalse()
        scan(p, RingScanResult.Failed(RingScanFailure.NO_PLATE), 33)
        // 4.95 s in
        assertThat(p.ui.ringScan.qrOffered).isFalse()
        scan(p, RingScanResult.Failed(RingScanFailure.NO_PLATE), 10)
        assertThat(p.ui.ringScan.qrOffered).isTrue()
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.SCANNING)
    }

    @Test
    fun `no camera offers the QR at once`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(cameraAvailable = false)
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.UNAVAILABLE)
        assertThat(p.ui.ringScan.qrOffered).isTrue()
    }

    @Test
    fun `a camera that fails to start offers the QR`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        p.holder.ringCameraFailed()
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.UNAVAILABLE)
        assertThat(p.ui.ringScan.qrOffered).isTrue()
    }

    @Test
    fun `an expired tag is refused without looking anything up, and the QR is offered`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, p.read(ageMinutes = 6))
        pass(p, 100)
        assertThat(p.resolver.lookups).isEmpty()
        assertThat(p.library.videos).isEmpty()
        assertThat(p.ui.ringScan.message).isEqualTo(RingScanMessage.EXPIRED)
        assertThat(p.ui.ringScan.qrOffered).isTrue()
        assertThat(p.ui.phase).isEqualTo(Phase.ARMED)
    }

    @Test
    fun `a tag from the future is refused as a clock problem`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, p.read(ageMinutes = -4))
        pass(p, 100)
        assertThat(p.resolver.lookups).isEmpty()
        assertThat(p.ui.ringScan.message).isEqualTo(RingScanMessage.CLOCK)
    }

    @Test
    fun `a code whose tag does not check out never gets as far as the controller`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, RingScanResult.Failed(RingScanFailure.BAD_CHECKSUM), times = 30)
        pass(p, 100)
        assertThat(p.resolver.lookups).isEmpty()
        assertThat(p.ui.phase).isEqualTo(Phase.ARMED)
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.SCANNING)
    }

    @Test
    fun `the same code is taken once, a replay of it is refused`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.resolver.lookups).hasSize(1)
        // The beam plays out, the screen goes back to waiting, the listener scans again and the camera sees the very same code.
        pass(p, 8_000)
        assertThat(p.ui.phase).isEqualTo(Phase.ARMED)
        p.holder.ringScanStart(true)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.resolver.lookups).hasSize(1)
        assertThat(p.ui.ringScan.message).isEqualTo(RingScanMessage.REPLAY)
        assertThat(p.library.videos).hasSize(1)
    }

    @Test
    fun `the rate limits of Song Tap still apply to a ring code`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        scan(p, p.read(random = 17))
        pass(p, 100)
        assertThat(p.library.videos).hasSize(1)
        pass(p, 8_000)
        // A new code (a new tag) for the same song a few seconds later: the session says it was just taken.
        p.holder.ringScanStart(true)
        scan(p, p.read(random = 99))
        pass(p, 100)
        assertThat(p.resolver.lookups).hasSize(2)
        assertThat(p.ui.notice).isEqualTo(SongTapNotice.DUPLICATE)
        assertThat(p.library.videos).hasSize(1)
    }

    @Test
    fun `a song that cannot be looked up saves nothing, says so and offers the QR`() = runTest {
        val p = receiver()
        p.resolver.offline = true
        p.holder.ringScanStart(true)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.resolver.lookups).hasSize(1)
        assertThat(p.library.videos).isEmpty()
        assertThat(p.ui.phase).isEqualTo(Phase.ARMED)
        assertThat(p.ui.ringScan.message).isEqualTo(RingScanMessage.LOOKUP_FAILED)
        assertThat(p.ui.ringScan.qrOffered).isTrue()
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.SCANNING)
        // Back online, a few seconds later, the same code works: nothing was held against it.
        p.resolver.offline = false
        pass(p, 4_000)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.library.videos).containsExactly("dQw4w9WgXcQ")
    }

    @Test
    fun `leaving the screen closes the scan`() = runTest {
        val p = receiver()
        p.holder.ringScanStart(true)
        p.holder.disarm()
        assertThat(p.ui.ringScan.stage).isEqualTo(RingScanStage.IDLE)
        scan(p, p.read())
        pass(p, 100)
        assertThat(p.resolver.lookups).isEmpty()
    }

    @Test
    fun `a QR code scanned in the last-resort route still works`() = runTest {
        val p = receiver()
        val payload = SongTapPayloads.make(
            nowMs = p.rig.clock.wall, ytVideoId = "aaaaaaaaaaa", spotifyId = null, isrc = null, title = "Another song", artist = "Someone",
            durationMs = 200_000L, artUrl = null,
        )!!
        p.holder.onScanned(SongTapLink.encode(SongTapPayloads.encode(payload)!!))
        pass(p, 100)
        assertThat(p.library.videos).containsExactly("aaaaaaaaaaa")
    }
}
