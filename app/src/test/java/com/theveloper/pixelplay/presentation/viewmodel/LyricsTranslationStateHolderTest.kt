package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.translate.OnDeviceLyricsTranslator
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsTranslationStateHolderTest {

    private fun song(id: String) = Song(
        id = id, title = "Song $id", artist = "Artist", artistId = 1L, album = "Album", albumId = 1L,
        path = "path", contentUriString = "content://dummy/$id", albumArtUriString = null,
        duration = 180_000L, mimeType = "audio/mpeg", bitrate = null, sampleRate = null
    )

    private val spanish = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Hola amigo"),
            SyncedLine(2_000, "Buenos días"),
            SyncedLine(3_000, "Hola amigo"),
        )
    )

    /** ML Kit stand-in: "es" lyrics, every model downloaded unless a test says otherwise. */
    private class FakeTranslator : OnDeviceLyricsTranslator {
        var songLanguage: String? = "es"
        var lineLanguages: Map<String, String?> = emptyMap()
        val downloaded = mutableSetOf("en", "es")
        var downloadGate: CompletableDeferred<Unit>? = null
        var translateGate: CompletableDeferred<Unit>? = null
        val translated = mutableListOf<String>()
        var translateCalls = 0
        private var identifyCalls = 0

        override suspend fun identifyLanguages(texts: List<String>): List<String?> {
            identifyCalls++
            // The first call reads the song's joined lines; later ones read each line.
            return if (identifyCalls == 1) listOf(songLanguage) else texts.map { lineLanguages[it] ?: songLanguage }
        }

        override fun supportedLanguage(languageTag: String): String? =
            languageTag.substringBefore('-').takeIf { it in setOf("en", "es", "ja", "ko") }

        override suspend fun isModelDownloaded(language: String): Boolean = language in downloaded

        override suspend fun downloadModels(source: String, target: String) {
            downloadGate?.await()
            downloaded += source
            downloaded += target
        }

        override suspend fun translate(lines: List<String>, source: String, target: String): List<String> {
            translateCalls++
            translateGate?.await()
            translated += lines
            return lines.map { "[$target] $it" }
        }
    }

    private class FakePreferences : DataStore<Preferences> {
        val flow = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(flow.value)
            flow.value = next
            return next
        }
    }

    private class Harness(
        val holder: LyricsTranslationStateHolder,
        val player: MutableStateFlow<StablePlayerState>,
        val preferences: FakePreferences,
        val messages: MutableList<String>,
    )

    // The holder runs in backgroundScope, so the tests step it with runCurrent(): advanceUntilIdle()
    // stops as soon as no FOREGROUND work is left and would never run it.
    private fun TestScope.harness(
        translator: FakeTranslator,
        initial: StablePlayerState = StablePlayerState(currentSong = song("1"), lyrics = spanish),
    ): Harness {
        val player = MutableStateFlow(initial)
        val playback = mockk<PlaybackStateHolder>(relaxed = true)
        every { playback.stablePlayerState } returns player
        every { playback.updateStablePlayerState(any()) } answers {
            player.update(firstArg<(StablePlayerState) -> StablePlayerState>())
        }
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } answers { "s${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg<Any>()) } answers { "s${firstArg<Int>()}" }
        val preferences = FakePreferences()
        val holder = LyricsTranslationStateHolder(context, playback, translator, preferences)
        holder.targetLanguageTag = { "en-US" }
        holder.initialize(backgroundScope)
        val messages = mutableListOf<String>()
        backgroundScope.launch { holder.messages.collect { messages += it } }
        runCurrent()
        return Harness(holder, player, preferences, messages)
    }

    private fun message(id: Int) = "s$id"

    @Test
    fun `translates the current song and shows the translations`() = runTest {
        val translator = FakeTranslator()
        val h = harness(translator)

        h.holder.translateCurrent()
        runCurrent()

        val lines = h.player.value.lyrics!!.synced!!
        assertEquals("[en] Hola amigo", lines[0].translation)
        assertEquals("[en] Buenos días", lines[1].translation)
        assertEquals("[en] Hola amigo", lines[2].translation)
        // Repeated lines are translated once.
        assertEquals(listOf("Hola amigo", "Buenos días"), translator.translated)
        assertEquals(LyricsTranslatePhase.Idle, h.holder.state.value.phase)
        assertEquals(true, h.preferences.flow.value[booleanPreferencesKey("show_lyrics_translation")])
    }

    @Test
    fun `a song change mid-translation drops the result`() = runTest {
        val translator = FakeTranslator().apply { translateGate = CompletableDeferred() }
        val h = harness(translator)

        h.holder.translateCurrent()
        runCurrent()
        assertEquals(LyricsTranslatePhase.Translating, h.holder.state.value.phase)

        h.player.value = StablePlayerState(currentSong = song("2"), lyrics = spanish)
        runCurrent()
        translator.translateGate!!.complete(Unit)
        runCurrent()

        assertTrue(h.player.value.lyrics!!.synced!!.all { it.translation == null })
        assertEquals("2", h.holder.state.value.songId)
        assertEquals(LyricsTranslatePhase.Idle, h.holder.state.value.phase)
    }

    @Test
    fun `lyrics already in the phone's language say so`() = runTest {
        val translator = FakeTranslator().apply { songLanguage = "en" }
        val h = harness(translator)

        h.holder.translateCurrent()
        runCurrent()

        assertEquals(listOf(message(R.string.lyrics_translate_already_in_target_language)), h.messages)
        assertEquals(0, translator.translateCalls)
    }

    @Test
    fun `an unknown language and an unsupported one each say so`() = runTest {
        val unknown = FakeTranslator().apply { songLanguage = null }
        val h1 = harness(unknown)
        h1.holder.translateCurrent()
        runCurrent()
        assertEquals(listOf(message(R.string.lyrics_translate_unknown_language)), h1.messages)

        val unsupported = FakeTranslator().apply { songLanguage = "xx" }
        val h2 = harness(unsupported)
        h2.holder.translateCurrent()
        runCurrent()
        assertEquals(listOf(message(R.string.lyrics_translate_unsupported)), h2.messages)
        assertEquals(0, unknown.translateCalls + unsupported.translateCalls)
    }

    @Test
    fun `a missing model downloads first, with its own phase and notice`() = runTest {
        val translator = FakeTranslator().apply {
            downloaded.remove("es")
            downloadGate = CompletableDeferred()
        }
        val h = harness(translator)

        h.holder.translateCurrent()
        runCurrent()
        assertTrue(h.holder.state.value.phase is LyricsTranslatePhase.DownloadingModel)
        assertEquals(listOf(message(R.string.lyrics_translate_downloading_model)), h.messages)

        translator.downloadGate!!.complete(Unit)
        runCurrent()
        assertEquals("[en] Buenos días", h.player.value.lyrics!!.synced!![1].translation)
        assertEquals(LyricsTranslatePhase.Idle, h.holder.state.value.phase)
    }

    @Test
    fun `the same song's lyrics reloading get their translations back without translating again`() = runTest {
        val translator = FakeTranslator()
        val h = harness(translator)
        h.holder.translateCurrent()
        runCurrent()

        // A resync / metadata edit reloads the lyrics without the on-device translations.
        h.player.value = h.player.value.copy(lyrics = spanish.copy())
        runCurrent()

        assertEquals("[en] Buenos días", h.player.value.lyrics!!.synced!![1].translation)
        assertEquals(1, translator.translateCalls)
    }

    @Test
    fun `saving paths get the lyrics without the on-device translations`() = runTest {
        val own = Lyrics(
            synced = listOf(SyncedLine(0, "Hola amigo"), SyncedLine(1_000, "Ya traducida", translation = "Mine"))
        )
        val translator = FakeTranslator()
        val h = harness(translator, StablePlayerState(currentSong = song("1"), lyrics = own))
        h.holder.translateCurrent()
        runCurrent()
        val shown = h.player.value.lyrics!!
        assertEquals("[en] Hola amigo", shown.synced!![0].translation)

        val saved = h.holder.withoutOnDeviceTranslations("1", shown)!!

        assertNull(saved.synced!![0].translation)
        assertEquals("Mine", saved.synced!![1].translation)
        // Another song has no on-device translations to take off.
        assertTrue(h.holder.withoutOnDeviceTranslations("2", shown) === shown)
    }

    @Test
    fun `lines already in the phone's language stay as they are`() = runTest {
        val mixed = Lyrics(synced = listOf(SyncedLine(0, "Te quiero"), SyncedLine(1_000, "I love you")))
        val translator = FakeTranslator().apply { lineLanguages = mapOf("I love you" to "en") }
        val h = harness(translator, StablePlayerState(currentSong = song("1"), lyrics = mixed))

        h.holder.translateCurrent()
        runCurrent()

        val lines = h.player.value.lyrics!!.synced!!
        assertEquals("[en] Te quiero", lines[0].translation)
        assertNull(lines[1].translation)
        assertEquals(listOf("Te quiero"), translator.translated)
    }

    @Test
    fun `plain-only lyrics are not translated on device`() = runTest {
        val translator = FakeTranslator()
        val h = harness(translator, StablePlayerState(currentSong = song("1"), lyrics = Lyrics(plain = listOf("Hola"))))

        h.holder.translateCurrent()
        runCurrent()

        assertEquals(listOf(message(R.string.lyrics_translate_needs_synced)), h.messages)
        assertEquals(0, translator.translateCalls)
    }
}
