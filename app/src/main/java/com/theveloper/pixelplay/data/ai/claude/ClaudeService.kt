package com.theveloper.pixelplay.data.ai.claude

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one door to Claude: the built-in key, the connection check, the monthly budget and the
 * usage bookkeeping around [SdkClaudeTransport]. Every AI feature reaches Claude through here
 * (one-shot features via [complete], Ask Taizo via the [ClaudeTransport] methods).
 */
@Singleton
class ClaudeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdk: SdkClaudeTransport,
    private val budget: ClaudeBudgetStore,
) : ClaudeTransport {

    private val _lastFailure = MutableStateFlow<ClaudeFailure?>(null)
    /** What went wrong with the last Claude request (null after a success), for Settings' status line. */
    val lastFailure: StateFlow<ClaudeFailure?> = _lastFailure.asStateFlow()

    val budgetState: StateFlow<ClaudeBudgetState> get() = budget.state

    fun hasKey(): Boolean = BuiltInClaudeKey.isPresent()

    /** A connection Android has validated (any network: Wi-Fi, mobile data, ethernet). */
    fun isOnline(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun withinBudget(): Boolean = budget.canSpend()

    /** All three conditions for routing a request to Claude. */
    fun canUseNow(): Boolean = hasKey() && withinBudget() && isOnline()

    fun setMonthlyCapUsd(usd: Double) = budget.setMonthlyCapUsd(usd)

    override suspend fun create(request: ClaudeRequest): ClaudeTurn = guarded { sdk.create(request) }

    override suspend fun stream(
        request: ClaudeRequest,
        onText: (String) -> Unit,
        onBlockStart: (JsonObject) -> Unit,
    ): ClaudeTurn = guarded { sdk.stream(request, onText, onBlockStart) }

    /**
     * One prompt in, one text answer out (playlists, greeting, metadata, translations).
     * [system] is cached (it is the stable part); the prompt is the user turn.
     */
    suspend fun complete(
        system: String,
        prompt: String,
        effort: ClaudeEffort,
        maxTokens: Long = ClaudeConfig.MAX_TOKENS,
    ): ClaudeCompletion {
        val request = ClaudeRequest(
            system = systemBlocks(system),
            messages = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            },
            maxTokens = maxTokens,
            effort = effort,
        )
        val turn = create(request)
        if (turn.stopReason == "refusal") {
            throw ClaudeException(ClaudeFailure.REFUSED, "Claude declined this request")
        }
        return ClaudeCompletion(text = turn.text.trim(), usage = turn.usage, model = turn.model ?: ClaudeConfig.MODEL)
    }

    private suspend fun guarded(block: suspend () -> ClaudeTurn): ClaudeTurn {
        if (!hasKey()) throw ClaudeException(ClaudeFailure.NO_KEY, "No built-in Claude key").also { note(it.failure) }
        if (!withinBudget()) {
            throw ClaudeException(ClaudeFailure.OVER_BUDGET, "This month's cloud AI budget is used up").also { note(it.failure) }
        }
        return try {
            block().also { turn ->
                budget.record(turn.usage)
                _lastFailure.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClaudeException) {
            note(e.failure)
            DiagnosticsLog.event("Claude", "request failed: ${e.failure} ${e.statusCode ?: ""}".trim())
            throw e
        }
    }

    private fun note(failure: ClaudeFailure) {
        if (failure != ClaudeFailure.REFUSED) _lastFailure.value = failure
    }

    companion object {
        /** A system prompt as one cached text block. */
        fun systemBlocks(vararg stableTexts: String, volatileText: String? = null): JsonArray = buildJsonArray {
            val stable = stableTexts.filter { it.isNotBlank() }
            stable.forEachIndexed { i, text ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    if (i == stable.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                })
            }
            if (!volatileText.isNullOrBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", volatileText)
                })
            }
        }
    }
}

data class ClaudeCompletion(val text: String, val usage: ClaudeUsage, val model: String)
