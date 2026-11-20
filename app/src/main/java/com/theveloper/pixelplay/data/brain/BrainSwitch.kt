package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.BrainRemoteFlags
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The switches of the music brain (docs/brain-spec.md, 2.1, 3.7, 7.1, 12.3): whether Autoplay
 * continues the queue, whether YouTube Music may be asked for related music (with it off, autoplay
 * continues from the library alone), which engine ranks the mixes (`engine = legacy | brain`), and
 * whether the one background job runs (a local switch and a remote one).
 */
@Singleton
class BrainSwitch @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val remote: BrainRemoteFlags,
) : com.theveloper.pixelplay.data.brain.source.KnowledgeSwitch {
    val autoplayEnabledFlow: Flow<Boolean> get() = preferences.brainAutoplayEnabledFlow

    val youTubeSourceEnabledFlow: Flow<Boolean> get() = preferences.brainYouTubeSourceEnabledFlow

    /** [BrainEngineChoice.LEGACY] or [BrainEngineChoice.BRAIN] (the default). */
    val engineFlow: Flow<Int> get() = preferences.brainEngineFlow

    val refreshEnabledFlow: Flow<Boolean> get() = preferences.brainRefreshEnabledFlow

    /** "Use my Spotify music to shape picks": the Spotify facts and history the picks may count (default on). */
    val spotifyShapesPicksFlow: Flow<Boolean> get() = preferences.brainSpotifyShapesPicksFlow

    /** "Let Taizo help the engine": the optional batch jobs (island names, genre tags, artist suggestions). Off by default. */
    val assistHelpFlow: Flow<Boolean> get() = preferences.brainAssistHelpEnabledFlow

    suspend fun setAssistHelp(enabled: Boolean) = preferences.setBrainAssistHelpEnabled(enabled)

    /** The batch jobs may run: the listener turned them on AND the remote config does not switch them off. Asked before every call. */
    suspend fun assistHelpAllowed(): Boolean = assistHelpFlow.first() && remote.assistAllowed()

    /** The local switch of one open-data source (Developer mode > Brain data). */
    fun sourceEnabledFlow(host: com.theveloper.pixelplay.data.brain.source.KnowledgeHost): Flow<Boolean> =
        preferences.brainSourceEnabledFlow(host.key)

    suspend fun setSourceEnabled(host: com.theveloper.pixelplay.data.brain.source.KnowledgeHost, enabled: Boolean) =
        preferences.setBrainSourceEnabled(host.key, enabled)

    /**
     * An open-data source may be asked: its local switch is on AND the remote config does not switch it off. Asked before every
     * request, so flipping either stops the very next one.
     */
    override suspend fun sourceAllowed(host: com.theveloper.pixelplay.data.brain.source.KnowledgeHost): Boolean =
        sourceEnabledFlow(host).first() && remote.sourceAllowed(host.key)

    suspend fun spotifyShapesPicks(): Boolean = spotifyShapesPicksFlow.first()

    suspend fun setSpotifyShapesPicks(enabled: Boolean) = preferences.setBrainSpotifyShapesPicks(enabled)

    suspend fun setAutoplayEnabled(enabled: Boolean) = preferences.setBrainAutoplayEnabled(enabled)

    suspend fun setYouTubeSourceEnabled(enabled: Boolean) = preferences.setBrainYouTubeSourceEnabled(enabled)

    suspend fun setEngine(engine: Int) = preferences.setBrainEngine(BrainEngineChoice.clamp(engine))

    suspend fun setRefreshEnabled(enabled: Boolean) = preferences.setBrainRefreshEnabled(enabled)

    /** The brain has had state to rank by (a cheap marker; see [UserPreferencesRepository.brainStateSeenFlow]). */
    suspend fun brainStateSeen(): Boolean = preferences.brainStateSeenFlow.first()

    suspend fun markBrainStateSeen() = preferences.setBrainStateSeen()

    suspend fun engine(): Int = BrainEngineChoice.clamp(engineFlow.first())

    /**
     * The refresh worker may run: the local switch is on AND the remote config does not switch it off.
     * Read at the start of every run and between lanes, so flipping either takes effect within one fetch.
     */
    suspend fun refreshAllowed(): Boolean = refreshEnabledFlow.first() && remote.refreshAllowed()

    companion object {
        /**
         * The legacy discovery paths the brain replaces in stage 1 (section 13, "Retire"): the eager
         * import of `MusicDiscoveryRepository.augment` into the mixes, and Home's "Beyond your library"
         * text search. Off. The code stays until stage 2 deletes it, so this is the one place to turn
         * them back on if the brain's row has to be pulled.
         */
        const val LEGACY_EAGER_DISCOVERY = false

        /**
         * Autoplay and stations on a Spotify Connect device (14.6, owner question 7). On: the phone's
         * queue is what the device plays, and a pick is queued only when Spotify has it. The one place
         * to turn it off if a speaker misbehaves; Cast is always off (see `AutoplayPolicy.remoteRule`).
         */
        const val AUTOPLAY_ON_CONNECT = true
    }
}
