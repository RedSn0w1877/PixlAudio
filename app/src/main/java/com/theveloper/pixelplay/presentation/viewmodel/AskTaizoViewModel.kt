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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
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
)

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
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskTaizoUiState())
    val uiState: StateFlow<AskTaizoUiState> = _uiState.asStateFlow()

    private val ids = AtomicLong(1)
    private var chatId: String = UUID.randomUUID().toString()
    private var history: List<JsonObject> = emptyList()
    private var profile: TaizoProfile? = null
    /** Song + queue the last turn described, so an unchanged now-playing block isn't repeated. */
    private var lastNowSignature: String? = null
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

        responseJob = viewModelScope.launch {
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
                finishStreamingItems()
                _uiState.update { it.copy(isResponding = false) }
                persist()
            }
        }
    }

    /** Stops the answer being written; what arrived so far stays, marked "(stopped)". */
    fun stop() {
        val job = responseJob ?: return
        pendingConfirmations.values.forEach { it.complete(false) }
        job.cancel()
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
            val snapshot = synchronized(streamed) { streamed.toString() }
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
        }

        try {
            val system = ClaudeService.systemBlocks(TaizoContext.INSTRUCTIONS, profileBlock())
            val result = agent.run(
                history = TaizoAgent.trimHistory(history),
                userContent = userContent(text),
                system = system,
                toolDefinitions = TaizoTools.requestTools(withWeb = true),
                listener = listener,
                effort = TaizoContext.effortFor(text),
            )
            history = TaizoAgent.trimHistory(result.history)
            val finalText = synchronized(streamed) { streamed.toString().trim() }
            when (result.outcome) {
                TaizoAgent.Outcome.REFUSED -> updateAssistant(assistant.id) {
                    it.copy(text = context.getString(R.string.taizo_refusal), streaming = false, activityRes = null, isError = true)
                }
                else -> updateAssistant(assistant.id) {
                    it.copy(
                        text = finalText.ifBlank { result.reply.trim() },
                        streaming = false,
                        activityRes = null,
                        sources = result.sources,
                        suffixRes = if (result.outcome == TaizoAgent.Outcome.TRUNCATED) R.string.taizo_truncated else null,
                    )
                }
            }
            memory.add(text, finalText.take(MEMORY_REPLY_CHARS))
        } catch (e: ClaudeException) {
            if (!e.fallsBackOnDevice) throw e
            // Claude dropped out mid-way: this answer comes from the phone instead.
            removeItem(assistant.id)
            val noteRes = when (e.failure) {
                ClaudeFailure.OVER_BUDGET -> R.string.taizo_note_over_budget
                ClaudeFailure.OFFLINE -> R.string.taizo_note_offline
                else -> R.string.taizo_note_unavailable
            }
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
                val media = TaizoChatItem.Media(nextId(), turn.result, turn.aiIntro)
                appendItem(media)
                memory.add(text, mediaNote(turn.result))
                if (turn.aiIntro == null && turn.result !is DjRouteResult.NoResults) {
                    viewModelScope.launch {
                        val intro = runCatching { djEngine.introFor(text, turn.result, forceOnDevice = forceOnDevice) }
                            .getOrNull() ?: return@launch
                        _uiState.update { state ->
                            state.copy(items = state.items.map { if (it.id == media.id && it is TaizoChatItem.Media) it.copy(intro = intro) else it })
                        }
                    }
                }
            }
            is TaizoTurn.Conversation -> {
                updateAssistant(assistant.id) { it.copy(text = turn.text, streaming = false, activityRes = null) }
                memory.add(text, turn.text)
            }
            is TaizoTurn.Error -> updateAssistant(assistant.id) {
                it.copy(text = turn.message, streaming = false, activityRes = null, isError = true)
            }
        }
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
        lastNowSignature = signature
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))
        val nowBlock = TaizoContext.nowPlayingBlock(now, upNext, includeDetails, time)
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
