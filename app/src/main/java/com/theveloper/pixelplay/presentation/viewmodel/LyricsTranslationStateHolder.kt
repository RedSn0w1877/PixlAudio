package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.translate.OnDeviceLyricsTranslator
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.presentation.lyrics.model.lacksTranslationsFrom
import com.theveloper.pixelplay.presentation.lyrics.model.untranslatedLines
import com.theveloper.pixelplay.presentation.lyrics.model.withTranslations
import com.theveloper.pixelplay.presentation.lyrics.model.withoutTranslationsFrom
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the lyrics toolbar's Translate button shows while an on-device translation runs. */
sealed interface LyricsTranslatePhase {
    data object Idle : LyricsTranslatePhase
    /** The translation model for [languageName] is downloading (first use of that language). */
    data class DownloadingModel(val languageName: String) : LyricsTranslatePhase
    data object Translating : LyricsTranslatePhase
}

@Immutable
data class LyricsTranslateUiState(
    val songId: String? = null,
    val phase: LyricsTranslatePhase = LyricsTranslatePhase.Idle,
) {
    val busy: Boolean get() = phase != LyricsTranslatePhase.Idle
}

/**
 * The lyrics page's Translate button: translates the current song's synced lines ON THIS PHONE
 * into the phone's language (owner decision: the phone's language only, not the app's), then
 * shows them under each line.
 *
 * The translations live only in memory: they are applied to `stablePlayerState.lyrics` (so the
 * karaoke view and the plain view both show them) and kept in a small per-session cache, so a
 * reload of the same song's lyrics gets them back. Nothing is written to the song, its .lrc or the
 * lyrics cache; "Translate via AI" (the long-press menu) is the path that persists. Whatever saves
 * the lyrics or translates them another way (the Save dialog, the sync editor's draft, Translate
 * via AI) starts from [withoutOnDeviceTranslations].
 *
 * A result that lands after a song change is dropped, and a song change cancels the running job.
 */
@Singleton
class LyricsTranslationStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val translator: OnDeviceLyricsTranslator,
    private val dataStore: DataStore<Preferences>,
) {
    /** The phone's language as a BCP-47 tag; replaced in tests. */
    internal var targetLanguageTag: () -> String = {
        Resources.getSystem().configuration.locales[0].toLanguageTag()
    }

    private val _state = MutableStateFlow(LyricsTranslateUiState())
    val state: StateFlow<LyricsTranslateUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scope: CoroutineScope? = null
    private var watchJob: Job? = null
    private var runJob: Job? = null
    private var runToken = 0L

    /**
     * Song id → its on-device translations (source line → translation), most recent songs kept.
     * An access-ordered map (a read reorders it), so every use holds its lock.
     */
    private val cache = object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?): Boolean =
            size > CACHE_SONGS
    }

    fun initialize(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        watchJob?.cancel()
        watchJob = coroutineScope.launch {
            var lastSongId: String? = null
            playbackStateHolder.stablePlayerState
                .map { it.currentSong?.id to it.lyrics }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second === b.second }
                .collect { (songId, lyrics) ->
                    if (songId != lastSongId) {
                        lastSongId = songId
                        runJob?.cancel()
                        _state.value = LyricsTranslateUiState(songId = songId)
                    }
                    // The same song's lyrics reloaded (a metadata edit, a resync) without the
                    // translations made earlier in this session: put them back.
                    val cached = songId?.let { cachedFor(it) } ?: return@collect
                    if (lyrics != null && lyrics.lacksTranslationsFrom(cached)) applyTo(songId, cached)
                }
        }
    }

    /** Translates the current song's lyrics; a no-op while this song is already being translated. */
    fun translateCurrent() {
        val scope = scope ?: return
        val current = playbackStateHolder.stablePlayerState.value
        val songId = current.currentSong?.id ?: return
        val lyrics = current.lyrics ?: return
        if (runJob?.isActive == true && _state.value.songId == songId) return
        runJob?.cancel()
        val token = ++runToken
        runJob = scope.launch {
            try {
                translate(songId, lyrics)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.w(e, "On-device lyrics translation failed")
                emit(context.getString(R.string.lyrics_translate_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                // Only this run may clear the spinner, and only for its own song.
                if (token == runToken) {
                    _state.update { if (it.songId == songId) it.copy(phase = LyricsTranslatePhase.Idle) else it }
                }
            }
        }
    }

    /**
     * [lyrics] without the translations this session made on the phone for [songId] (the lyrics'
     * own translations stay). Paths that persist the lyrics or translate them another way start
     * from this: the Save dialog's .lrc and the sync editor's draft (either would otherwise write
     * the machine translations into the user's lyrics) and Translate via AI (which would otherwise
     * answer "already translated" for the rest of the session, since the cache puts them back on
     * every reload).
     */
    fun withoutOnDeviceTranslations(songId: String, lyrics: Lyrics?): Lyrics? {
        val byLine = cachedFor(songId) ?: return lyrics
        return lyrics?.withoutTranslationsFrom(byLine)
    }

    private fun cachedFor(songId: String): Map<String, String>? = synchronized(cache) { cache[songId] }

    private suspend fun translate(songId: String, lyrics: Lyrics) {
        if (lyrics.synced.isNullOrEmpty()) {
            emit(context.getString(R.string.lyrics_translate_needs_synced))
            return
        }
        val targetTag = targetLanguageTag()
        val target = translator.supportedLanguage(targetTag)
        if (target == null) {
            emit(context.getString(R.string.lyrics_translate_unsupported, displayName(targetTag)))
            return
        }
        val pending = lyrics.untranslatedLines()
        if (pending.isEmpty()) {
            // Every line already carries a translation: just show them.
            showTranslations()
            return
        }
        val keys = pending.keys.toList()
        val texts = pending.values.toList()

        // Language ID is unreliable on one short line, so the song's language comes from its
        // first lines read together.
        val sample = texts.take(LANGUAGE_SAMPLE_LINES).joinToString("\n")
        val sourceTag = translator.identifyLanguages(listOf(sample)).firstOrNull()
        if (sourceTag == null) {
            emit(context.getString(R.string.lyrics_translate_unknown_language))
            return
        }
        val source = translator.supportedLanguage(sourceTag)
        if (source == null) {
            emit(context.getString(R.string.lyrics_translate_unsupported, displayName(sourceTag)))
            return
        }
        if (source == target) {
            emit(context.getString(R.string.lyrics_translate_already_in_target_language))
            return
        }

        val missing = listOf(source, target).firstOrNull { !translator.isModelDownloaded(it) }
        if (missing != null) {
            val name = displayName(missing)
            setPhase(songId, LyricsTranslatePhase.DownloadingModel(name))
            emit(context.getString(R.string.lyrics_translate_downloading_model, name))
            translator.downloadModels(source, target)
        }
        setPhase(songId, LyricsTranslatePhase.Translating)

        // Mixed songs (a K-pop verse with an English hook): lines already in the phone's language
        // are left as they are instead of being "translated" into themselves.
        val lineLanguages = translator.identifyLanguages(texts)
        val toTranslate = texts.indices.filter { i ->
            val tag = lineLanguages.getOrNull(i)
            tag == null || translator.supportedLanguage(tag) != target
        }
        val translated = translator.translate(toTranslate.map { texts[it] }, source, target)
        val byLine = HashMap<String, String>(toTranslate.size)
        toTranslate.forEachIndexed { n, i ->
            val text = translated.getOrNull(n)?.trim()
            if (!text.isNullOrEmpty() && !text.equals(texts[i], ignoreCase = true)) byLine[keys[i]] = text
        }
        if (byLine.isEmpty()) {
            emit(context.getString(R.string.lyrics_translate_already_in_target_language))
            return
        }
        synchronized(cache) { cache[songId] = cache[songId].orEmpty() + byLine }
        applyTo(songId, byLine)
        showTranslations()
    }

    /** Applies [byLine] to the current lyrics, only while [songId] is still the song playing. */
    private fun applyTo(songId: String, byLine: Map<String, String>) {
        playbackStateHolder.updateStablePlayerState { state ->
            val lyrics = state.lyrics
            if (state.currentSong?.id != songId || lyrics == null) {
                state
            } else {
                val translated = lyrics.withTranslations(byLine)
                if (translated === lyrics) state else state.copy(lyrics = translated)
            }
        }
    }

    private suspend fun showTranslations() {
        dataStore.edit { it[SHOW_TRANSLATION_KEY] = true }
    }

    private fun setPhase(songId: String, phase: LyricsTranslatePhase) {
        _state.update { if (it.songId == songId) it.copy(phase = phase) else it }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun displayName(languageTag: String): String {
        val name = Locale.forLanguageTag(languageTag).getDisplayLanguage(Locale.getDefault())
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            .ifBlank { languageTag }
    }

    private companion object {
        const val CACHE_SONGS = 30
        const val LANGUAGE_SAMPLE_LINES = 40
        /** The lyrics page's "Show translations" preference (LyricsAppearancePrefs reads it). */
        val SHOW_TRANSLATION_KEY = booleanPreferencesKey("show_lyrics_translation")
    }
}
