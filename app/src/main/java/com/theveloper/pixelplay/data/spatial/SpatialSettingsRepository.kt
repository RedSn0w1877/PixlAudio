package com.theveloper.pixelplay.data.spatial

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.cloudstudio.CloudBuiltInConfig
import com.theveloper.pixelplay.data.preferences.prefFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The settings of §6.1 in the shared `settings` DataStore, under the spec's own key names (the same strings the iOS
 * app stores under). Being in that store they travel in `.pxpl` backups like every other setting, with no extra code.
 *
 * This is the only writer. The player side gets every change through
 * [SpatialPlayback.apply] (the playback service collects [settings]); the cloud side and the UI read [settings]
 * (the UI through [com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioStateHolder]).
 *
 * A value that is missing reads as its default; a value of the wrong type or outside its range (a hand-edited
 * backup) reads as the default or the nearest allowed value, never as a crash. The stored forms are §6.1's, the same
 * on both platforms: the cap's "No limit" is `-1`, the storage budget stays within 256..65536 MB, the daily count
 * within 1..100.
 */
@Singleton
class SpatialSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    /** Whether this build carries PixlAudio's own cloud keys: the default of `spatial.cloud` (§6.1). */
    private val builtInCloud: CloudBuiltInConfig,
) {
    private object Keys {
        val PROFILE = stringPreferencesKey(KEY_PROFILE)
        val ROUTE = stringPreferencesKey(KEY_ROUTE)
        val CLOUD = booleanPreferencesKey(KEY_CLOUD)
        val PREPARE_AHEAD = booleanPreferencesKey(KEY_PREPARE_AHEAD)
        val ENHANCED = booleanPreferencesKey(KEY_ENHANCED)
        val STORAGE_MB = intPreferencesKey(KEY_STORAGE_MB)
        val MONTHLY_CAP_USD = intPreferencesKey(KEY_MONTHLY_CAP_USD)
        val DAILY_AHEAD = intPreferencesKey(KEY_DAILY_AHEAD)
    }

    /** The defaults of §6.1 for this build (`spatial.cloud` is on when built-in cloud keys exist). */
    val defaults: SpatialSettings
        get() = SpatialSettings.defaults(builtInCloudKeys = builtInCloud.isBundled)

    /**
     * The settings of §6.1, as they change. Distinct: the shared store emits on every write of any setting, and
     * only a change to one of these eight values passes.
     */
    val settings: Flow<SpatialSettings> = dataStore.prefFlow(::read)

    /** The settings right now (one read of the store). */
    suspend fun current(): SpatialSettings = settings.first()

    /** `spatial.profile` (§6.1): [profile], or null for `off`. */
    suspend fun setProfile(profile: SpatialProfile?) {
        dataStore.edit { it[Keys.PROFILE] = profile?.id ?: SpatialProfile.OFF_ID }
    }

    /**
     * `spatial.route` (§6.1). Developer mode's row; the stored value stays, and is honoured, when Developer mode is
     * switched off (§6.1 "Stored forms").
     */
    suspend fun setRouteMode(mode: SpatialRouteMode) {
        dataStore.edit { it[Keys.ROUTE] = mode.id }
    }

    /** `spatial.cloud` (§6.1). Once written, the build's default no longer applies. */
    suspend fun setCloud(enabled: Boolean) {
        dataStore.edit { it[Keys.CLOUD] = enabled }
    }

    /** `spatial.prepareAhead` (§6.1, §6.4). */
    suspend fun setPrepareAhead(enabled: Boolean) {
        dataStore.edit { it[Keys.PREPARE_AHEAD] = enabled }
    }

    /** `enhanced.enabled` (§6.1, §6.3). */
    suspend fun setEnhanced(enabled: Boolean) {
        dataStore.edit { it[Keys.ENHANCED] = enabled }
    }

    /**
     * `cloudAudio.storageMB` (§6.1 "Stored forms"): kept within
     * [SpatialSettings.MIN_STORAGE_MB]..[SpatialSettings.MAX_STORAGE_MB].
     */
    suspend fun setStorageMb(megabytes: Int) {
        dataStore.edit { it[Keys.STORAGE_MB] = SpatialSettings.clampStorageMb(megabytes) }
    }

    /**
     * `cloudAudio.monthlyCapUSD` (§6.1): whole dollars, kept inside 0..[SpatialSettings.MAX_MONTHLY_CAP_USD]; null is
     * "No limit" (stored as [NO_MONTHLY_CAP]).
     */
    suspend fun setMonthlyCapUsd(usd: Int?) {
        dataStore.edit {
            it[Keys.MONTHLY_CAP_USD] = usd?.coerceIn(0, SpatialSettings.MAX_MONTHLY_CAP_USD) ?: NO_MONTHLY_CAP
        }
    }

    /**
     * `cloudAudio.dailyAhead` (§6.1 "Stored forms", §6.4): kept within
     * [SpatialSettings.MIN_DAILY_AHEAD]..[SpatialSettings.MAX_DAILY_AHEAD].
     */
    suspend fun setDailyAhead(songs: Int) {
        dataStore.edit { it[Keys.DAILY_AHEAD] = SpatialSettings.clampDailyAhead(songs) }
    }

    private fun read(preferences: Preferences): SpatialSettings {
        // Looked up by name and cast safely: `preferences[key]` casts unchecked, so a value of another type (a
        // restored or hand-edited backup) would throw at the first read instead of falling back to the default.
        val stored = preferences.asMap()
        val capUsd = stored[Keys.MONTHLY_CAP_USD] as? Int
        return SpatialSettings(
            profile = SpatialProfile.fromId(stored[Keys.PROFILE] as? String),
            routeMode = SpatialRouteMode.fromId(stored[Keys.ROUTE] as? String),
            cloud = stored[Keys.CLOUD] as? Boolean ?: builtInCloud.isBundled,
            prepareAhead = stored[Keys.PREPARE_AHEAD] as? Boolean ?: SpatialSettings.DEFAULT_PREPARE_AHEAD,
            enhanced = stored[Keys.ENHANCED] as? Boolean ?: SpatialSettings.DEFAULT_ENHANCED,
            storageMb = SpatialSettings.clampStorageMb(
                stored[Keys.STORAGE_MB] as? Int ?: SpatialSettings.DEFAULT_STORAGE_MB
            ),
            monthlyCapUsd = when {
                capUsd == null -> SpatialSettings.DEFAULT_MONTHLY_CAP_USD
                // "No limit" is written as -1; any negative number reads as it, as on iOS.
                capUsd < 0 -> null
                else -> capUsd.coerceAtMost(SpatialSettings.MAX_MONTHLY_CAP_USD)
            },
            dailyAhead = SpatialSettings.clampDailyAhead(
                stored[Keys.DAILY_AHEAD] as? Int ?: SpatialSettings.DEFAULT_DAILY_AHEAD
            ),
        )
    }

    companion object {
        /** §6.1 key: `off` or a profile id. */
        const val KEY_PROFILE = "spatial.profile"

        /** §6.1 key: `headphones` or `always`. */
        const val KEY_ROUTE = "spatial.route"

        /** §6.1 key (Boolean); absent until the person touches the switch, so the build's default applies. */
        const val KEY_CLOUD = "spatial.cloud"

        /** §6.1 key (Boolean). */
        const val KEY_PREPARE_AHEAD = "spatial.prepareAhead"

        /** §6.1 key (Boolean). */
        const val KEY_ENHANCED = "enhanced.enabled"

        /** §6.1 key (Int, megabytes, 256..65536). */
        const val KEY_STORAGE_MB = "cloudAudio.storageMB"

        /** §6.1 key (Int, whole dollars; [NO_MONTHLY_CAP] for "No limit"). */
        const val KEY_MONTHLY_CAP_USD = "cloudAudio.monthlyCapUSD"

        /** §6.1 key (Int, songs a day, 1..100). */
        const val KEY_DAILY_AHEAD = "cloudAudio.dailyAhead"

        /**
         * How "No limit" is stored under [KEY_MONTHLY_CAP_USD] (§6.1 "Stored forms": `-1`, identical on both
         * platforms). Not 0: a cap of 0 means "spend nothing", as it does for Sing's cap.
         */
        const val NO_MONTHLY_CAP = -1
    }
}
