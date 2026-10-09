package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Reads one value out of the shared settings DataStore.
 *
 * The whole app keeps its preferences in a single DataStore, so `dataStore.data` emits again on
 * EVERY write (a track change, a library tab switch, a slider step, a sync timestamp...), whatever
 * key changed. A bare `data.map { }` therefore re-emits the same value again and again, which
 * restarted every `combine` / `flatMapLatest` hanging off it (the albums query and Pager, the
 * folder tree, ReplayGain...). Dropping consecutive equal values keeps those pipelines quiet
 * unless the value they actually read changed; the values delivered are identical.
 */
internal fun <T> DataStore<Preferences>.prefFlow(transform: (Preferences) -> T): Flow<T> =
    data.map(transform).distinctUntilChanged()
