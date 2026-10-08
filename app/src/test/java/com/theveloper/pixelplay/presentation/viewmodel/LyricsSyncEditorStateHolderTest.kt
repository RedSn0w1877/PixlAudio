package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.media.AudioManager
import com.google.android.gms.cast.framework.CastSession
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.sync.LyricsSyncDraftStore
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.TransitionController
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectController
import com.theveloper.pixelplay.data.spotify.connect.SpotifyConnectUiState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The tap-sync editor's open / close paths: the Spotify Connect guard (owner decision: explain,
 * never pause or seek the speaker), and errors or messages instead of silent closes and spinners.
 * The Android analogue of iOS `LyricsSyncTests` / `LyricsSyncEntryTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsSyncEditorStateHolderTest {

    private val dispatcher = StandardTestDispatcher()
    private val scheduler get() = dispatcher.scheduler
    private val testScope = TestScope(dispatcher)

    private val song = testSong("1", "Indian Summer")
    private val otherSong = testSong("2", "Someone Else")

    private val stable = MutableStateFlow(StablePlayerState(currentSong = song))
    private val castSession = MutableStateFlow<CastSession?>(null)
    private val connectAttached = MutableStateFlow(false)
    private val connectUi = MutableStateFlow(SpotifyConnectUiState())

    private val context: Context = mockk(relaxed = true)
    private val playback: PlaybackStateHolder = mockk(relaxed = true)
    private val engine: DualPlayerEngine = mockk(relaxed = true)
    private val transitionController: TransitionController = mockk(relaxed = true)
    private val castStateHolder: CastStateHolder = mockk(relaxed = true)
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val lyricsStateHolder: LyricsStateHolder = mockk(relaxed = true)
    private val draftStore: LyricsSyncDraftStore = mockk(relaxed = true)
    private val preferences: UserPreferencesRepository = mockk(relaxed = true)
    private val spotifyConnect: SpotifyConnectController = mockk(relaxed = true)

    private lateinit var holder: LyricsSyncEditorStateHolder
    private val messages = mutableListOf<String>()

    private fun text(res: Int) = "s$res"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { context.getString(any()) } answers { text(firstArg()) }
        every { context.getSystemService(AudioManager::class.java) } returns null
        every { playback.stablePlayerState } returns stable
        every { playback.currentSpeed() } returns 1f
        every { castStateHolder.castSession } returns castSession
        every { spotifyConnect.isAttached } returns connectAttached
        every { spotifyConnect.uiState } returns connectUi
        coEvery { lyricsRepository.getStoredLyrics(any()) } returns null
        coEvery { draftStore.load(any()) } returns null
        every { preferences.lyricsTapOffsetBluetoothMsFlow } returns flowOf(0)
        every { preferences.lyricsTapOffsetSpeakerMsFlow } returns flowOf(0)
        every { preferences.lyricsSyncDefaultSpeedFlow } returns flowOf(1f)
        every { preferences.lyricsSyncHapticsFlow } returns flowOf(true)
        every { preferences.lyricsSyncIntroSeenCountFlow } returns flowOf(0)
        every { preferences.lyricsSyncChipDismissedSongIdsFlow } returns flowOf(emptySet())
        holder = LyricsSyncEditorStateHolder(
            context = context,
            playback = playback,
            engine = engine,
            transitionController = transitionController,
            castStateHolder = castStateHolder,
            lyricsRepository = lyricsRepository,
            lyricsStateHolder = lyricsStateHolder,
            draftStore = draftStore,
            preferences = preferences,
            spotifyConnect = spotifyConnect,
        )
        testScope.launch { holder.messageEvents.collect { messages += it } }
        scheduler.runCurrent()
    }

    @AfterEach
    fun tearDown() {
        holder.close()
        scheduler.runCurrent()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun settle() {
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()
    }

    @Test
    fun openWhileConnectAttached_showsPhoneOnlyError_andTouchesNoPlayer() {
        connectAttached.value = true

        holder.open(SyncEntry.WORDS)
        settle()

        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_connect)), holder.phase.value)
        verify(exactly = 0) { playback.pauseLocal() }
        verify(exactly = 0) { playback.seekTo(any()) }
        verify(exactly = 0) { engine.beginExactTimingSession() }
        verify(exactly = 0) { transitionController.suspend(any()) }
        verify(exactly = 0) { playback.setPlaybackSpeed(any()) }

        holder.close()
        assertEquals(SyncPhase.Closed, holder.phase.value)
        verify(exactly = 0) { playback.setPlaybackSpeed(any()) }
    }

    @Test
    fun openWhileConnectConnecting_showsError() {
        connectUi.value = SpotifyConnectUiState(connectingDeviceId = "echo")

        holder.open(SyncEntry.WORDS)
        settle()

        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_connect)), holder.phase.value)
        verify(exactly = 0) { playback.pauseLocal() }
    }

    @Test
    fun requestOpenWhileConnect_returnsFalse_andNeverTimesOut() {
        connectAttached.value = true

        val opening = holder.requestOpen(otherSong, SyncEntry.FIX_TIMING)
        scheduler.advanceTimeBy(9_000)
        scheduler.runCurrent()

        assertFalse(opening)
        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_connect)), holder.phase.value)
        assertFalse(text(R.string.lyrics_sync_open_timeout) in messages)
        verify(exactly = 0) { playback.pauseLocal() }
    }

    @Test
    fun connectAttachMidSession_errorsWithoutPausingTheSpeaker() {
        holder.open(SyncEntry.WORDS)
        settle()
        assertEquals(SyncPhase.NeedWords, holder.phase.value)
        verify(exactly = 1) { playback.pauseLocal() } // startSession

        connectAttached.value = true
        settle()
        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_connect)), holder.phase.value)

        // The song changing on the speaker or the app going to the background must not pause it.
        stable.value = StablePlayerState(currentSong = otherSong)
        settle()
        assertEquals(SyncDialog.NONE, holder.uiState.value.dialog)
        holder.onHostStopped()
        verify(exactly = 1) { playback.pauseLocal() }
        verify(exactly = 0) { playback.seekTo(any()) }

        // Close still restores the player.
        holder.close()
        assertEquals(SyncPhase.Closed, holder.phase.value)
        verify(exactly = 1) { playback.setPlaybackSpeed(1f) }
        verify(exactly = 1) { engine.endExactTimingSession() }
        verify(exactly = 1) { transitionController.resume("lyrics_sync") }
    }

    @Test
    fun castMidSession_stillShowsTheCastingError() {
        holder.open(SyncEntry.WORDS)
        settle()

        castSession.value = mockk(relaxed = true)
        settle()

        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_casting)), holder.phase.value)
    }

    @Test
    fun loadThatHangs_showsTheErrorScreenAfterTheWatchdog() {
        coEvery { lyricsRepository.getStoredLyrics(any()) } coAnswers { awaitCancellation() }

        holder.open(SyncEntry.WORDS)
        scheduler.advanceTimeBy(LyricsSyncEditorStateHolder.LOAD_TIMEOUT_MS - 1)
        scheduler.runCurrent()
        assertEquals(SyncPhase.Loading, holder.phase.value)

        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_load_failed)), holder.phase.value)

        holder.close()
        assertEquals(SyncPhase.Closed, holder.phase.value)
        verify(exactly = 1) { engine.endExactTimingSession() }
    }

    @Test
    fun loadThatThrows_showsTheErrorScreenInsteadOfCrashing() {
        every { preferences.lyricsSyncHapticsFlow } returns flow { throw IllegalStateException("broken store") }

        holder.open(SyncEntry.WORDS)
        settle()

        assertEquals(SyncPhase.Error(text(R.string.lyrics_sync_load_failed)), holder.phase.value)
    }

    @Test
    fun theEditorStaysOpen() {
        // Android analogue of the iOS "still open 3 s later" entry test.
        holder.open(SyncEntry.WORDS)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()

        assertEquals(SyncPhase.NeedWords, holder.phase.value)
    }

    @Test
    fun playerUnloading_closesWithAMessage() {
        holder.open(SyncEntry.WORDS)
        settle()

        stable.value = StablePlayerState(currentSong = null)
        scheduler.advanceTimeBy(601)
        scheduler.runCurrent()

        assertEquals(SyncPhase.Closed, holder.phase.value)
        assertTrue(text(R.string.lyrics_sync_playback_stopped) in messages)
    }

    @Test
    fun openWithNothingPlaying_saysSo() {
        stable.value = StablePlayerState(currentSong = null)

        holder.open()
        scheduler.runCurrent()

        assertEquals(SyncPhase.Closed, holder.phase.value)
        assertTrue(text(R.string.lyrics_sync_no_song) in messages)
    }

    @Test
    fun requestOpenThatTimesOut_saysSo() {
        val opening = holder.requestOpen(otherSong, SyncEntry.FIX_TIMING)
        assertTrue(opening)
        scheduler.advanceTimeBy(8_001)
        scheduler.runCurrent()

        assertEquals(SyncPhase.Closed, holder.phase.value)
        assertTrue(text(R.string.lyrics_sync_open_timeout) in messages)
    }

    @Test
    fun removeMyTimingThatFails_staysOpenAndSaysSo() {
        holder.open(SyncEntry.WORDS)
        settle()
        coEvery { lyricsRepository.resetLyrics(any<Song>()) } throws IOException("disk full")

        holder.removeMyTiming()
        settle()

        assertEquals(SyncPhase.NeedWords, holder.phase.value)
        assertTrue(text(R.string.lyrics_sync_remove_failed) in messages)
    }

    private fun testSong(id: String, title: String) = Song(
        id = id,
        title = title,
        artist = "Blood Cultures",
        album = "Happy Birthday",
        path = "/music/$id.mp3",
        contentUriString = "content://media/external/audio/media/$id",
        albumArtUriString = null,
        duration = 295_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100,
        artistId = 1L,
        albumId = 1L
    )
}
