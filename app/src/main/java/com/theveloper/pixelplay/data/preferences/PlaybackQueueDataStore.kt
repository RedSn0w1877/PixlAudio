package com.theveloper.pixelplay.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Qualifier

/**
 * The saved playback queue lives in its own DataStore file (`playback_queue`), not in `settings`.
 *
 * A big queue is ~1.3 MB of JSON (5,000 songs), and a preferences DataStore rewrites and fsyncs its whole
 * file on every edit. While the queue shared the settings file, every tab switch, slider step or sync
 * timestamp rewrote the queue too, every play/pause or skip rewrote the whole settings file, and the cold
 * start (which waits for the settings read before the splash lifts) had to parse the queue as well.
 * [UserPreferencesRepository] moves an existing queue over once, from the old `settings` key.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackQueueDataStore

val Context.playbackQueueDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_queue")
