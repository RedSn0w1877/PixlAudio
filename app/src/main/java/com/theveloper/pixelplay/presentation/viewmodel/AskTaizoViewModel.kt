package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.claude.ClaudeException
import com.theveloper.pixelplay.data.ai.claude.ClaudeFailure
import com.theveloper.pixelplay.data.ai.claude.ClaudeService
import com.theveloper.pixelplay.data.ai.claude.taizo.StoredTaizoChat
import com.theveloper.pixelplay.data.ai.claude.taizo.StoredTaizoMessage
import com.theveloper.pixelplay.data.ai.claude.taizo.StoredTaizoSource
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoAgent
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoAgentListener
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoChatStore
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoContext
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoContextProvider
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoProfile
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoSource
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoToolExecutor
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoTools
import com.theveloper.pixelplay.data.ai.claude.taizo.music.StationOutcome
import com.theveloper.pixelplay.data.ai.claude.taizo.music.MixCard
import com.theveloper.pixelplay.data.ai.claude.taizo.music.MixCardChip
import com.theveloper.pixelplay.brain.AssistantCards
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.tais.dj.DjRouteResult
import com.theveloper.pixelplay.data.tais.dj.TaisDjEngine
import com.theveloper.pixelplay.data.tais.dj.TaizoMemory
import com.theveloper.pixelplay.data.tais.dj.TaizoTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/** One row of the Ask Taizo chat. */
sealed interface TaizoChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : TaizoChatItem

    data class Assistant(
        override val id: Long,
        val text: String = "",
        val streaming: Boolean = true,
        /** What Taizo is doing right now (a web search, a library search), while [streaming]. */
        @param:StringRes @get:StringRes val activityRes: Int? = null,
        val sources: List<TaizoSource> = emptyList(),
        val isError: Boolean = false,
        /** "(stopped)" / "(cut short)" after the text. */
        @param:StringRes @get:StringRes val suffixRes: Int? = null,
    ) : TaizoChatItem

    /** Songs found by the on-device DJ path (offline), with Play / Add to queue. */
    data class Media(override val id: Long, val result: DjRouteResult, val intro: String? = null) : TaizoChatItem

    /** A tap-to-confirm card for a destructive or large action. [answer] is null while it waits. */
    data class Confirm(override val id: Long, val question: String, val answer: Boolean? = null) : TaizoChatItem

    /** A one-line note (answering on the phone, budget used up...). */
    data class Note(override val id: Long, @param:StringRes @get:StringRes val textRes: Int) : TaizoChatItem

    /**
     * A mix `build_mix` made (docs/brain-spec.md, 9.3): "Late drive · 30 songs", Play, Save, and the chips under it. A chip is a delta on the
     * stored request applied on the phone, with no model call.
     */
    data class Mix(
        override val id: Long,
        val card: MixCard,
        /** A chip or Save is being worked out. */
        val busy: Boolean = false,
        val playing: Boolean = false,
        val saved: Boolean = false,
        /** The last chip or Save did not work. */
        val failed: Boolean = false,
    ) : TaizoChatItem

    /** The one-tap "Less X" that ends an explanation of the listener's taste or of one pick. [state] is one of [LESS_IDLE], [LESS_DONE], [LESS_FAILED]. */
    data class Less(
        override val id: Long,
        val kind: Int,
        val name: String,
        val label: String,
        val state: Int = LESS_IDLE,
        val adjustmentId: String? = null,
    ) : TaizoChatItem

    companion object {
        const val LESS_IDLE = 0
        const val LESS_DONE = 1
        const val LESS_FAILED = 2
    }
}

/** Where this chat's answers come from right now. */
enum class TaizoMode { CLAUDE, ON_DEVICE, OWN_CLOUD }

data class TaizoChatSummary(val id: String, val title: String, val updatedAt: Long)

data class AskTaizoUiState(
    val items: List<TaizoChatItem> = emptyList(),
    val input: String = "",
    val isResponding: Boolean = false,
    val mode: TaizoMode = TaizoMode.CLAUDE,
    val ownCloudName: String? = null,
    val recentChats: List<TaizoChatSummary> = emptyList(),
    val isResolvingTracks: Boolean = false,
    /** The chat was opened from "Ask the host" of Endless Listening: the host chips show and typed text carries the host's short addendum. */
    val djMode: Boolean = false,
)

/** The host's chips (Endless Listening): each is answered on the phone with no model call. */
enum class HostChip { NEW, FAMILIAR, WHY, UPBEAT, SLOWER, SURPRISE }

/**
 * Ask Taizo: the assistant chat. Online with the built-in key it is Claude Sonnet 5.5 with the
 * app's tools, web search and fetch, streaming its answer ([TaizoAgent]); offline (or over the
 * monthly budget, or when a Claude request fails mid-way) it answers on the phone through the
 * TAIS DJ engine: the deterministic "play / queue" path plus the on-device model, no web. A
 * Developer-mode cloud assistant (own key) answers through the same engine.
 *
 * The last few chats are kept on the phone ([TaizoChatStore]); "New chat" starts over.
 */
@HiltViewModel
class AskTaizoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claude: ClaudeService,
    private val aiHandler: AiHandler,
    private val contextProvider: TaizoContextProvider,
    private val toolExecutor: TaizoToolExecutor,
    private val chatStore: TaizoChatStore,
    private val djEngine: TaisDjEngine,
    private val spotifyRepository: SpotifyRepository,
    private val musicRepository: MusicRepository,
    private val brainFeedback: BrainFeedback,
    private val assistantEngine: com.theveloper.pixelplay.data.ai.claude.taizo.music.BrainAssistantEngine,
    private val stationStore: com.theveloper.pixelplay.data.brain.StationStore,
    private val brainSession: BrainSessionStateHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskTaizoUiState())
    val uiState: StateFlow<AskTaizoUiState> = _uiState.asStateFlow()

    private val ids = AtomicLong(1)
    private var chatId: String = UUID.randomUUID().toString()
    private var history: List<JsonObject> = emptyList()
    private var profile: TaizoProfile? = null
    /** Song + queue the last turn described, so an unchanged now-playing block isn't repeated. */
    private var lastNowSignature: String? = null

    /** The now-playing signature of the turn in flight; becomes [lastNowSignature] only once that turn is in the history. */
    private var pendingNowSignature: String? = null
    private var responseJob: Job? = null
    private val pendingConfirmations = HashMap<Long, CompletableDeferred<Boolean>>()
    private val memory = TaizoMemory()
    private val agent = TaizoAgent(claude, toolExecutor)

    init {
        refreshMode()
        viewModelScope.launch { loadRecentChats() }
        viewModelScope.launch { runCatching { djEngine.prewarm() } }
        // The profile reads the whole play history: start it while the user types.
        viewModelScope.launch {
            if (profile == null) runCatching { contextProvider.profile() }.getOrNull()?.let { if (profile == null) profile = it }
        }
    }

    fun attachPlayer(player: TaizoPlayer) {
        toolExecutor.player = player
        hostPlayer = player
    }

    /** The player the sheet attached, for the host's chips. */
    private var hostPlayer: TaizoPlayer? = null

    /** Opened from "Ask the host" (true) or from anywhere else (false). */
    fun setDjMode(on: Boolean) = _uiState.update { if (it.djMode == on) it else it.copy(djMode = on) }

    /**
     * A chip of the host. Every one is answered on the phone: the novelty switch, five songs built for the change put next in the queue, or the
     * existing "Why this song" sheet. Nothing is sent to a model, so a chip costs no part of the monthly budget.
     */
    fun hostChip(chip: HostChip) {
        if (_uiState.value.isResponding) return
        appendItem(TaizoChatItem.User(nextId(), context.getString(hostChipLabel(chip))))
        viewModelScope.launch {
            val reply = try {
                runHostChip(chip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "A host chip failed")
                context.getString(R.string.endless_reply_nothing)
            }
            appendItem(TaizoChatItem.Assistant(nextId(), text = reply, streaming = false))
        }
    }

    private fun hostChipLabel(chip: HostChip): Int = when (chip) {
        HostChip.NEW -> R.string.endless_chip_new
        HostChip.FAMILIAR -> R.string.endless_chip_familiar
        HostChip.WHY -> R.string.endless_chip_why
        HostChip.UPBEAT -> R.string.endless_chip_upbeat
        HostChip.SLOWER -> R.string.endless_chip_slower
        HostChip.SURPRISE -> R.string.endless_chip_surprise
    }

    private suspend fun runHostChip(chip: HostChip): String = when (chip) {
        HostChip.NEW -> {
            stationStore.setNovelty(com.theveloper.pixelplay.data.brain.Novelty.DISCOVER)
            context.getString(R.string.endless_reply_new)
        }
        HostChip.FAMILIAR -> {
            stationStore.setNovelty(com.theveloper.pixelplay.data.brain.Novelty.FAMILIAR)
            context.getString(R.string.endless_reply_familiar)
        }
        HostChip.WHY -> {
            val song = hostPlayer?.currentSong()
            val why = song?.let { brainSession.queueWhy.value[it.id] }
            if (song == null) context.getString(R.string.endless_reply_no_song)
            else if (why == null) context.getString(R.string.endless_reply_no_why)
            else {
                brainSession.actions.showWhy(song, why.key, why.arg, why.servedX, why.model)
                context.getString(R.string.endless_reply_why)
            }
        }
        HostChip.UPBEAT -> steerHost("energy_up", context.getString(R.string.endless_reply_upbeat))
        HostChip.SLOWER -> steerHost("energy_down", context.getString(R.string.endless_reply_slower))
        HostChip.SURPRISE -> steerHost("more_new", context.getString(R.string.endless_reply_surprise))
    }

    /** `steer_session` for this queue only, run directly: five songs built for the change go next. What it cannot do, it says (no energy data, nothing playing). */
    private suspend fun steerHost(change: String, done: String): String {
        val outcome = assistantEngine.steer(change, "", "session", hostPlayer)
        return if (outcome.applied) done else outcome.note.ifBlank { context.getString(R.string.endless_reply_nothing) }
    }

    fun detachPlayer(player: TaizoPlayer) {
        if (toolExecutor.player === player) toolExecutor.player = null
    }

    fun onInputChange(text: String) = _uiState.update { it.copy(input = text) }

    /** Updates the header (Claude / on this phone / own provider). */
    fun refreshMode() {
        viewModelScope.launch {
            val route = runCatching { aiHandler.currentRoute() }.getOrNull()
            _uiState.update {
                it.copy(
                    mode = when (route) {
                        is AiRoute.Claude -> TaizoMode.CLAUDE
                        is AiRoute.Cloud -> TaizoMode.OWN_CLOUD
                        else -> TaizoMode.ON_DEVICE
                    },
                    ownCloudName = (route as? AiRoute.Cloud)?.provider?.displayName,
                )
            }
        }
    }

    fun send(textOverride: String? = null) {
        val text = (textOverride ?: _uiState.value.input).trim()
        if (text.isBlank() || _uiState.value.isResponding) return
        val userItem = TaizoChatItem.User(nextId(), text)
        _uiState.update { it.copy(items = it.items + userItem, input = "", isResponding = true) }

        // LAZY so responseJob is this job before its body runs. A stopped turn can still be unwinding when the next
        // one starts; only the current turn may finish the bubbles and clear isResponding.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val myJob = coroutineContext[Job]
            try {
                when (val route = aiHandler.currentRoute()) {
                    is AiRoute.Claude -> {
                        _uiState.update { it.copy(mode = TaizoMode.CLAUDE) }
                        answerWithClaude(text)
                    }
                    is AiRoute.Cloud -> {
                        _uiState.update { it.copy(mode = TaizoMode.OWN_CLOUD, ownCloudName = route.provider.displayName) }
                        answerOnPhoneEngine(text, forceOnDevice = false, noteRes = null)
                    }
                    is AiRoute.OnDevice -> {
                        _uiState.update { it.copy(mode = TaizoMode.ON_DEVICE) }
                        answerOnPhoneEngine(text, forceOnDevice = true, noteRes = onDeviceReasonRes())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Ask Taizo turn failed")
                appendItem(TaizoChatItem.Assistant(nextId(), context.getString(R.string.taizo_error_generic), streaming = false, isError = true))
            } finally {
                if (responseJob === myJob) {
                    finishStreamingItems()
                    _uiState.update { it.copy(isResponding = false) }
                    persist()
                }
            }
        }
        responseJob = job
        job.start()
    }

    /** Stops the answer being written; what arrived so far stays, marked "(stopped)". */
    fun stop() {
        val job = responseJob ?: return
        pendingConfirmations.values.forEach { it.complete(false) }
        job.cancel()
        // The stopped turn never reaches the history, so the next one must carry the full now-playing block.
        lastNowSignature = null
        _uiState.update { state ->
            state.copy(
                isResponding = false,
                items = state.items.map { item ->
                    if (item is TaizoChatItem.Assistant && item.streaming) {
                        item.copy(streaming = false, activityRes = null, suffixRes = R.string.taizo_stopped)
                    } else item
                },
            )
        }
        viewModelScope.launch { persist() }
    }

    fun answerConfirmation(itemId: Long, accepted: Boolean) {
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it is TaizoChatItem.Confirm && it.id == itemId) it.copy(answer = accepted) else it })
        }
        pendingConfirmations.remove(itemId)?.complete(accepted)
    }

    fun newChat() {
        if (_uiState.value.isResponding) stop()
        viewModelScope.launch {
            persist()
            chatId = UUID.randomUUID().toString()
            history = emptyList()
            toolExecutor.newConversation()
            profile = null
            lastNowSignature = null
            memory.clear()
            _uiState.update { it.copy(items = emptyList(), input = "") }
            loadRecentChats()
            refreshMode()
        }
    }

    fun openChat(id: String) {
        if (_uiState.value.isResponding) stop()
        viewModelScope.launch {
            persist()
            val chat = chatStore.load().firstOrNull { it.id == id } ?: return@launch
            chatId = chat.id
            history = TaizoChatStore.historyFrom(chat.messages)
            toolExecutor.newConversation()
            profile = null
            lastNowSignature = null
            memory.clear()
            val items = chat.messages.map { message ->
                if (message.role == "user") TaizoChatItem.User(nextId(), message.text)
                else TaizoChatItem.Assistant(
                    id = nextId(),
                    text = message.text,
                    streaming = false,
                    sources = message.sources.map { TaizoSource(it.url, it.title) },
                )
            }
            _uiState.update { it.copy(items = items, input = "") }
            loadRecentChats()
        }
    }

    /** Imports Spotify results from an offline DJ card into the library, then hands them over (the browse-tab pipeline). */
    fun resolveOnlineTracks(tracks: List<SpotifyTrack>, onReady: (List<Song>) -> Unit) {
        if (tracks.isEmpty() || _uiState.value.isResolvingTracks) return
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingTracks = true) }
            try {
                spotifyRepository.importTracks(tracks)
                val songIds = spotifyRepository.unifiedIdsFor(tracks).map { it.toString() }
                val songs = musicRepository.getSongsByIds(songIds).first()
                if (songs.isNotEmpty()) onReady(songs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Couldn't import Spotify tracks from the chat")
            } finally {
                _uiState.update { it.copy(isResolvingTracks = false) }
            }
        }
    }

    // ---- cards under the answer: nothing below calls a model ------------------------------------------------------------------

    /** `build_mix` returned: the card goes under the answer. Reads the mix the engine kept, never the model's text. */
    private fun showMixCard(result: String) {
        val mixId = runCatching { (Json.parseToJsonElement(result) as? JsonObject)?.get("mix_id")?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return
        val card = toolExecutor.mixCard(mixId) ?: return
        appendItem(TaizoChatItem.Mix(nextId(), card))
    }

    private fun updateMix(id: Long, change: (TaizoChatItem.Mix) -> TaizoChatItem.Mix) = _uiState.update { state ->
        state.copy(items = state.items.map { if (it.id == id && it is TaizoChatItem.Mix) change(it) else it })
    }

    private fun mixItem(id: Long): TaizoChatItem.Mix? = _uiState.value.items.firstOrNull { it.id == id } as? TaizoChatItem.Mix

    /** Play on a mix card: the queue becomes the mix (every pick resolved before this card was shown). */
    fun playMix(itemId: Long) {
        val item = mixItem(itemId) ?: return
        if (item.busy) return
        viewModelScope.launch {
            val ok = runCatching { toolExecutor.playMix(item.card.mixId) }.getOrDefault(false)
            updateMix(itemId) { it.copy(playing = ok, failed = !ok) }
        }
    }

    /** Save on a mix card: a playlist of the listener's with the mix's songs. */
    fun saveMix(itemId: Long) {
        val item = mixItem(itemId) ?: return
        if (item.busy || item.saved) return
        updateMix(itemId) { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val saved = try {
                toolExecutor.saveMix(item.card.mixId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Saving a mix from the chat failed")
                null
            }
            updateMix(itemId) { it.copy(busy = false, saved = saved != null, failed = saved == null) }
        }
    }

    /** A chip under a mix card: the stored request with that delta, built again by the engine on the phone. */
    fun applyMixChip(itemId: Long, chip: MixCardChip) {
        val item = mixItem(itemId) ?: return
        if (item.busy) return
        updateMix(itemId) { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val next = try {
                toolExecutor.adjustMix(item.card.mixId, chip.chip)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "A mix chip failed")
                null
            }
            updateMix(itemId) {
                if (next != null) it.copy(card = next, busy = false, playing = false, saved = false, failed = false) else it.copy(busy = false, failed = true)
            }
        }
    }

    /**
     * After an answer that explained the listener's taste or a pick: the one tap that acts on it, "Less trap". Offered only for a genre or
     * an artist an explain tool returned in this turn AND the listener's own words named ([AssistantCards.lessTarget]).
     */
    private fun offerLess(userText: String) {
        val target = AssistantCards.lessTarget(userText, toolExecutor.namedThisTurn()) ?: return
        val label = AssistantCards.lessLabel(target.name)
        if (label.isEmpty()) return
        appendItem(TaizoChatItem.Less(nextId(), target.kind, target.name, label))
    }

    private fun updateLess(id: Long, change: (TaizoChatItem.Less) -> TaizoChatItem.Less) = _uiState.update { state ->
        state.copy(items = state.items.map { if (it.id == id && it is TaizoChatItem.Less) change(it) else it })
    }

    /** "Less trap": the existing taste adjustment (undone in Taste changes, or with Undo here). */
    fun tapLess(itemId: Long) {
        val item = _uiState.value.items.firstOrNull { it.id == itemId } as? TaizoChatItem.Less ?: return
        if (item.state == TaizoChatItem.LESS_DONE) return
        viewModelScope.launch {
            val id = try {
                if (item.kind == AssistantCards.NAMED_GENRE) brainFeedback.adjustGenre(item.name, more = false) else brainFeedback.lessFrom(item.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "A taste change from the chat failed")
                null
            }
            // The profile the model reads is stale after a taste change: it is rebuilt for the next turn.
            if (id != null) profile = null
            updateLess(itemId) { it.copy(state = if (id != null) TaizoChatItem.LESS_DONE else TaizoChatItem.LESS_FAILED, adjustmentId = id) }
        }
    }

    fun undoLess(itemId: Long) {
        val item = _uiState.value.items.firstOrNull { it.id == itemId } as? TaizoChatItem.Less ?: return
        val adjustment = item.adjustmentId ?: return
        viewModelScope.launch {
            runCatching { brainFeedback.undo(adjustment) }
            profile = null
            updateLess(itemId) { it.copy(state = TaizoChatItem.LESS_IDLE, adjustmentId = null) }
        }
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun answerWithClaude(text: String) {
        val assistant = TaizoChatItem.Assistant(nextId(), activityRes = R.string.taizo_activity_thinking)
        appendItem(assistant)
        val streamed = StringBuilder()
        var stepStart = 0
        var lastPublish = 0L

        fun publish(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < STREAM_PUBLISH_MS) return
            lastPublish = now
            // What the listener reads while it streams is checked like the final reply: a name no tool returned never shows, even for a moment.
            val snapshot = streamingText(synchronized(streamed) { streamed.toString() }) ?: return
            updateAssistant(assistant.id) { it.copy(text = snapshot) }
        }

        val listener = object : TaizoAgentListener {
            override fun onText(delta: String) {
                synchronized(streamed) { streamed.append(delta) }
                updateAssistant(assistant.id) { it.copy(activityRes = null) }
                publish()
            }

            override fun onDiscardStep() {
                synchronized(streamed) { streamed.setLength(stepStart) }
                publish(force = true)
            }

            override fun onStepStart() {
                synchronized(streamed) {
                    // Keep a paragraph break between the text before and after a tool round.
                    if (streamed.isNotEmpty() && !streamed.endsWith("\n")) streamed.append("\n\n")
                    stepStart = streamed.length
                }
            }

            override fun onActivity(toolName: String, isServerTool: Boolean) {
                updateAssistant(assistant.id) { it.copy(activityRes = activityFor(toolName)) }
            }

            override suspend fun confirm(question: String): Boolean {
                val card = TaizoChatItem.Confirm(nextId(), question)
                val answer = CompletableDeferred<Boolean>()
                pendingConfirmations[card.id] = answer
                insertBefore(assistant.id, card)
                return answer.await()
            }

            override fun onToolResult(name: String, result: String) {
                if (name == TaizoTools.BUILD_MIX) showMixCard(result)
            }
        }

        try {
            val profileText = profileBlock()
            val system = ClaudeService.systemBlocks(
                TaizoContext.INSTRUCTIONS, profileText,
                // The host's short addendum, after the cache breakpoint: the cached prefix is the same with or without it.
                volatileText = if (_uiState.value.djMode) TaizoContext.HOST_ADDENDUM else null,
            )
            // What the model is shown (the profile, what is playing) and what earlier turns of this chat returned are things it may say back.
            val grounding = toolExecutor.grounding
            if (!grounding.isSeeded) grounding.noteHistory(history)
            grounding.noteContext(profileText)
            grounding.noteUser(text)
            val result = agent.run(
                history = TaizoAgent.trimHistory(history),
                userContent = userContent(text),
                system = system,
                toolDefinitions = TaizoTools.requestTools(withWeb = true),
                listener = listener,
                effort = TaizoContext.effortFor(text),
            )
            history = TaizoAgent.trimHistory(result.history)
            // A refused turn leaves the history as it was, so its now-playing block never got in.
            if (result.outcome != TaizoAgent.Outcome.REFUSED) lastNowSignature = pendingNowSignature
            // A taste change makes the cached profile (and its digest) stale: rebuild it for the next turn.
            if (toolExecutor.consumeTasteChanged()) profile = null
            val finalText = groundedReply(synchronized(streamed) { streamed.toString().trim() })
            when (result.outcome) {
                TaizoAgent.Outcome.REFUSED -> updateAssistant(assistant.id) {
                    it.copy(text = context.getString(R.string.taizo_refusal), streaming = false, activityRes = null, isError = true)
                }
                else -> updateAssistant(assistant.id) {
                    it.copy(
                        text = finalText.ifBlank { groundedReply(result.reply.trim()) },
                        streaming = false,
                        activityRes = null,
                        sources = result.sources,
                        suffixRes = if (result.outcome == TaizoAgent.Outcome.TRUNCATED) R.string.taizo_truncated else null,
                    )
                }
            }
            memory.add(text, finalText.take(MEMORY_REPLY_CHARS))
            if (result.outcome != TaizoAgent.Outcome.REFUSED) offerLess(text)
        } catch (e: ClaudeException) {
            if (!e.fallsBackOnDevice) throw e
            lastNowSignature = null
            val noteRes = when (e.failure) {
                ClaudeFailure.OVER_BUDGET -> R.string.taizo_note_over_budget
                ClaudeFailure.OFFLINE -> R.string.taizo_note_offline
                else -> R.string.taizo_note_unavailable
            }
            if (toolExecutor.consumeTasteChanged()) profile = null
            if (toolExecutor.ranSideEffectsThisTurn()) {
                // Tools already acted (played, queued, made a playlist...): asking the phone again would do it
                // twice. Keep what Claude wrote and say why it stopped.
                val partial = groundedReply(synchronized(streamed) { streamed.toString().trim() })
                updateAssistant(assistant.id) {
                    it.copy(
                        text = partial.ifBlank { context.getString(R.string.taizo_error_generic) },
                        streaming = false,
                        activityRes = null,
                        isError = partial.isBlank(),
                    )
                }
                if (_uiState.value.items.none { it is TaizoChatItem.Note && it.textRes == noteRes }) {
                    insertBefore(assistant.id, TaizoChatItem.Note(nextId(), noteRes))
                }
                return
            }
            // Claude dropped out mid-way: this answer comes from the phone instead.
            removeItem(assistant.id)
            _uiState.update { it.copy(mode = TaizoMode.ON_DEVICE) }
            answerOnPhoneEngine(text, forceOnDevice = true, noteRes = noteRes)
        }
    }

    private suspend fun answerOnPhoneEngine(text: String, forceOnDevice: Boolean, noteRes: Int?) {
        if (noteRes != null && _uiState.value.items.none { it is TaizoChatItem.Note && it.textRes == noteRes }) {
            insertBefore(_uiState.value.items.lastOrNull()?.id ?: -1L, TaizoChatItem.Note(nextId(), noteRes), afterLast = true)
        }
        val assistant = TaizoChatItem.Assistant(nextId(), activityRes = R.string.taizo_activity_thinking)
        appendItem(assistant)
        when (val turn = djEngine.respond(text, memory, forceOnDevice = forceOnDevice)) {
            is TaizoTurn.Media -> {
                removeItem(assistant.id)
                toolExecutor.grounding.noteUser(text)
                val media = TaizoChatItem.Media(nextId(), turn.result, turn.aiIntro?.let(::introOrNull))
                appendItem(media)
                memory.add(text, mediaNote(turn.result))
                if (turn.aiIntro == null && turn.result !is DjRouteResult.NoResults) {
                    viewModelScope.launch {
                        val intro = runCatching { djEngine.introFor(text, turn.result, forceOnDevice = forceOnDevice) }
                            .getOrNull()?.let(::introOrNull) ?: return@launch
                        _uiState.update { state ->
                            state.copy(items = state.items.map { if (it.id == media.id && it is TaizoChatItem.Media) it.copy(intro = intro) else it })
                        }
                    }
                }
            }
            is TaizoTurn.Conversation -> {
                // The phone's model and the plain cloud route name things too: the same rule holds. What it was shown (the library facts) and
                // what the listener wrote are the names and numbers it may say.
                toolExecutor.grounding.noteUser(text)
                toolExecutor.grounding.noteContext(turn.facts)
                val checked = groundedReply(turn.text)
                updateAssistant(assistant.id) { it.copy(text = checked, streaming = false, activityRes = null) }
                memory.add(text, checked)
            }
            is TaizoTurn.Station -> {
                // No model and no connection needed: the name is looked up in the library and the genre atlas on the phone.
                val outcome = startStationFromSentence(turn.name)
                val started = outcome?.started == true
                // The reply names what was matched, not the lowercased words the listener typed.
                val reply = if (started) context.getString(R.string.brain_taizo_station_started, outcome?.title?.takeIf { it.isNotBlank() } ?: turn.name)
                else context.getString(R.string.brain_taizo_station_not_found, turn.name)
                updateAssistant(assistant.id) { it.copy(text = reply, streaming = false, activityRes = null, isError = !started) }
                memory.add(text, reply)
            }
            is TaizoTurn.Error -> updateAssistant(assistant.id) {
                it.copy(text = turn.message, streaming = false, activityRes = null, isError = true)
            }
        }
    }

    /**
     * The grounding rule (docs/brain-spec.md, 1 principle 9, 9.4): the reply may name a track, an album or an artist only when a tool returned it
     * in this chat (or the listener said it), and may state a number about the listener only when a tool returned it. A name that fails is replaced,
     * a sentence with a number that fails is left out, and the marks the model put around names are taken off. The saved history keeps what the
     * model wrote; only what the listener reads is cleaned.
     */
    private fun groundedReply(raw: String): String {
        if (raw.isEmpty()) return raw
        val verdict = toolExecutor.grounding.check(raw, context.getString(R.string.taizo_unverified_name))
        if (!verdict.ok) {
            Timber.tag(TAG).w("Reply held ${verdict.ungroundedNames.size} unverified names and ${verdict.ungroundedNumbers.size} unverified numbers")
        }
        // Nothing left once the unverified sentences are gone: say so. The raw text is never shown in its place.
        return verdict.cleaned.ifBlank { context.getString(R.string.taizo_unverified_reply) }
    }

    /**
     * The text of a reply that is still being written, checked the same way ([groundedReply]): an unclosed mark and the word being typed are held
     * back (a half-written name cannot be judged), the rest goes through the grounding check. Null when it cannot be checked right now (the
     * update is skipped, the last shown text stays).
     */
    private fun streamingText(raw: String): String? {
        var hold = raw
        val open = hold.lastIndexOf("[[")
        if (open >= 0 && hold.indexOf("]]", open) < 0) hold = hold.substring(0, open)
        val lastBreak = hold.indexOfLast { it.isWhitespace() }
        hold = if (lastBreak >= 0) hold.substring(0, lastBreak + 1) else ""
        if (hold.isBlank()) return ""
        return try {
            toolExecutor.grounding.check(hold, context.getString(R.string.taizo_unverified_name)).cleaned
        } catch (e: Exception) {
            null
        }
    }

    /** An AI-written intro line for a result card, or null when it names anything no tool or the listener gave (the card keeps its plain text). */
    private fun introOrNull(raw: String): String? {
        val verdict = toolExecutor.grounding.check(raw, context.getString(R.string.taizo_unverified_name))
        return if (verdict.ok) verdict.cleaned.takeIf { it.isNotBlank() } else null
    }

    /**
     * "Start a station from X" offline: X is tried as an artist, a genre, a playlist and an album of the listener's, in that order, through the
     * same call the chat's `start_station` tool makes (so the first track plays at once and the engine continues from the cached pools).
     */
    private suspend fun startStationFromSentence(name: String): StationOutcome? {
        val player = toolExecutor.player ?: return null
        // The listener's own things first, free and offline; an artist the phone does not know is searched last.
        for (kind in listOf("library_artist", "genre", "playlist", "album", "artist")) {
            val outcome = try {
                assistantEngine.startStation(name, kind, "blend", player)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "A station from a sentence could not start as $kind")
                continue
            }
            if (outcome.started) return outcome
        }
        return null
    }

    private suspend fun profileBlock(): String {
        val p = profile ?: contextProvider.profile().also { profile = it }
        return TaizoContext.profileBlock(p)
    }

    private suspend fun userContent(text: String): kotlinx.serialization.json.JsonArray {
        val player = toolExecutor.player
        val now = runCatching { contextProvider.nowPlaying(player) }.getOrNull()
        val queue = player?.queue().orEmpty()
        val index = player?.currentIndex() ?: -1
        val upNext = if (index >= 0 && index < queue.size) queue.drop(index + 1) else queue
        val signature = now?.songId + "|" + upNext.take(TaizoContext.QUEUE_PREVIEW).joinToString(",") { it.id }
        val includeDetails = signature != lastNowSignature
        pendingNowSignature = signature
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))
        val nowBlock = TaizoContext.nowPlayingBlock(now, upNext, includeDetails, time)
        toolExecutor.grounding.noteContext(nowBlock)
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", nowBlock)
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
    }

    private fun onDeviceReasonRes(): Int? = when {
        !claude.hasKey() -> null
        !claude.withinBudget() -> R.string.taizo_note_over_budget
        !claude.isOnline() -> R.string.taizo_note_offline
        else -> null
    }

    @StringRes
    private fun activityFor(toolName: String): Int = when (toolName) {
        "web_search" -> R.string.taizo_activity_web_search
        "web_fetch" -> R.string.taizo_activity_web_fetch
        TaizoTools.SEARCH_LIBRARY -> R.string.taizo_activity_library
        TaizoTools.SEARCH_SPOTIFY -> R.string.taizo_activity_spotify
        TaizoTools.PLAY_SONGS -> R.string.taizo_activity_play
        TaizoTools.ADD_TO_QUEUE -> R.string.taizo_activity_queue
        TaizoTools.PLAYBACK_CONTROL -> R.string.taizo_activity_playback
        TaizoTools.CREATE_PLAYLIST, TaizoTools.ADD_TO_PLAYLIST -> R.string.taizo_activity_playlist
        TaizoTools.LISTENING_INSIGHTS -> R.string.taizo_activity_insights
        TaizoTools.GET_LYRICS -> R.string.taizo_activity_lyrics
        TaizoTools.START_SING -> R.string.taizo_activity_sing
        TaizoTools.LIKE_SONG -> R.string.taizo_activity_like
        TaizoTools.LIBRARY_OVERVIEW -> R.string.taizo_activity_library
        TaizoTools.TASTE_PROFILE -> R.string.taizo_activity_taste
        TaizoTools.ADJUST_TASTE, TaizoTools.LIST_TASTE_ADJUSTMENTS, TaizoTools.REMOVE_TASTE_ADJUSTMENT -> R.string.taizo_activity_taste_change
        TaizoTools.RESOLVE_TRACKS -> R.string.taizo_activity_resolve
        TaizoTools.RECOMMEND -> R.string.taizo_activity_recommend
        TaizoTools.BUILD_MIX -> R.string.taizo_activity_mix
        TaizoTools.START_STATION -> R.string.taizo_activity_station
        TaizoTools.STEER_SESSION -> R.string.taizo_activity_steer
        TaizoTools.EXPLAIN_TRACK, TaizoTools.EXPLAIN_TASTE -> R.string.taizo_activity_explain
        TaizoTools.LOOKUP_GENRE -> R.string.taizo_activity_genre
        TaizoTools.SIMILAR_ARTISTS -> R.string.taizo_activity_similar
        TaizoTools.NEW_RELEASES -> R.string.taizo_activity_releases
        else -> R.string.taizo_activity_thinking
    }

    private fun mediaNote(result: DjRouteResult): String = when (result) {
        is DjRouteResult.Offline -> "Found ${result.songs.size} songs from the library: " +
            result.songs.take(3).joinToString(", ") { "${it.title} by ${it.displayArtist}" }
        is DjRouteResult.Online -> "Found ${result.tracks.size} songs on Spotify."
        DjRouteResult.NoResults -> "Found no matching songs."
    }

    private fun nextId(): Long = ids.getAndIncrement()

    private fun appendItem(item: TaizoChatItem) = _uiState.update { it.copy(items = it.items + item) }

    private fun removeItem(id: Long) = _uiState.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }

    /** Puts [item] just before the row [beforeId] (or at the end when [afterLast] or the row is gone). */
    private fun insertBefore(beforeId: Long, item: TaizoChatItem, afterLast: Boolean = false) = _uiState.update { state ->
        val index = state.items.indexOfFirst { it.id == beforeId }
        if (afterLast || index < 0) state.copy(items = state.items + item)
        else state.copy(items = state.items.toMutableList().apply { add(index, item) })
    }

    private fun updateAssistant(id: Long, change: (TaizoChatItem.Assistant) -> TaizoChatItem.Assistant) =
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.id == id && it is TaizoChatItem.Assistant) change(it) else it })
        }

    private fun finishStreamingItems() = _uiState.update { state ->
        state.copy(items = state.items.map { item ->
            when {
                item is TaizoChatItem.Assistant && item.streaming -> item.copy(streaming = false, activityRes = null)
                item is TaizoChatItem.Confirm && item.answer == null -> item.copy(answer = false)
                else -> item
            }
        })
    }

    private suspend fun persist() {
        val messages = _uiState.value.items.mapNotNull { item ->
            when (item) {
                is TaizoChatItem.User -> StoredTaizoMessage("user", item.text)
                is TaizoChatItem.Assistant -> item.text.takeIf { it.isNotBlank() && !item.isError }?.let { text ->
                    StoredTaizoMessage("assistant", text, item.sources.map { StoredTaizoSource(it.url, it.title) })
                }
                is TaizoChatItem.Media -> StoredTaizoMessage("assistant", mediaNote(item.result))
                else -> null
            }
        }
        if (messages.none { it.role == "user" }) return
        val title = messages.first { it.role == "user" }.text.take(TITLE_CHARS)
        runCatching { chatStore.save(StoredTaizoChat(chatId, title, System.currentTimeMillis(), messages)) }
        loadRecentChats()
    }

    private suspend fun loadRecentChats() {
        val chats = runCatching { chatStore.load() }.getOrDefault(emptyList())
        _uiState.update { state -> state.copy(recentChats = chats.map { TaizoChatSummary(it.id, it.title, it.updatedAt) }) }
    }

    override fun onCleared() {
        pendingConfirmations.values.forEach { it.complete(false) }
        toolExecutor.player = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "AskTaizo"
        const val STREAM_PUBLISH_MS = 40L
        const val MEMORY_REPLY_CHARS = 600
        const val TITLE_CHARS = 60
    }
}
