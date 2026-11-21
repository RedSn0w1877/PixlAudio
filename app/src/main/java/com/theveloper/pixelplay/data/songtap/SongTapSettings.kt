package com.theveloper.pixelplay.data.songtap

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theveloper.pixelplay.songtap.SongTapNames
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.songTapStore by preferencesDataStore("song_tap")

/**
 * Song Tap's switches and the listener's name, in a small store of their own (nothing is read at app start; the first read is
 * when Song Tap opens).
 *
 * - [allowed]: "Allow Song Tap", off by default. Turned on from the Song Tap row in the player's menu (that
 *   is also when the permissions are asked), or here in Settings.
 * - [firstBeamAsks]: the first beam a receiver ever gets shows an explicit Add / Not now card; once the
 *   listener has said yes, later beams save by themselves with a 6 second Undo.
 */
@Singleton
class SongTapSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val allowKey = booleanPreferencesKey("allow_song_tap")
    private val firstBeamDoneKey = booleanPreferencesKey("first_beam_accepted")
    private val nameKey = stringPreferencesKey("your_name")

    val allowed: Flow<Boolean> get() = context.songTapStore.data.map { it[allowKey] ?: false }.distinctUntilChanged()

    val firstBeamAsks: Flow<Boolean> get() = context.songTapStore.data.map { !(it[firstBeamDoneKey] ?: false) }.distinctUntilChanged()

    /**
     * The name other phones see ("Song drop to X?", "From X"): what the listener typed in Settings (clean, 1 to 20 characters), or the
     * device's model name when there is none. Always usable text.
     */
    val name: Flow<String> get() = context.songTapStore.data.map { SongTapNames.orFallback(storedOrDefault(it[nameKey])) }.distinctUntilChanged()

    suspend fun nameNow(): String = name.first()

    /** Saves [raw] as the name after cleaning it; an empty result puts the device name back. Returns what was saved. */
    suspend fun setName(raw: String): String {
        val clean = SongTapNames.clean(raw)
        context.songTapStore.edit { if (clean.isEmpty()) it.remove(nameKey) else it[nameKey] = clean }
        return SongTapNames.orFallback(storedOrDefault(clean.ifEmpty { null }))
    }

    private fun storedOrDefault(stored: String?): String = stored?.takeIf { it.isNotBlank() } ?: defaultName()

    suspend fun isAllowed(): Boolean = allowed.first()

    suspend fun doesFirstBeamAsk(): Boolean = firstBeamAsks.first()

    suspend fun setAllowed(on: Boolean) {
        context.songTapStore.edit { it[allowKey] = on }
    }

    /** The device's own name for itself, like "Pixel 10 Pro" (its model), cleaned the same way as a typed name. */
    fun defaultName(): String = SongTapNames.orFallback(Build.MODEL ?: "")

    /** The listener said yes to a first beam: from now on beams save at once. */
    suspend fun markFirstBeamAccepted() {
        context.songTapStore.edit { it[firstBeamDoneKey] = true }
    }
}
