package com.theveloper.pixelplay.data.diagnostics

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Observes things from the outside, so the code that owns them (the Spotify importer, the
 * workers, the player) does not need a logging call of its own: the Spotify session, and every
 * WorkManager job's life cycle. Both write through [DiagnosticsLog.event].
 */
object DiagnosticsWatchers {

    /** Logs the Spotify sign-in state at start and on every change (signed in or out). */
    fun watchSpotifySession(scope: CoroutineScope, signedIn: Flow<Boolean>) {
        scope.launch {
            var first = true
            signedIn.distinctUntilChanged().catch { }.collect { value ->
                DiagnosticsLog.event(
                    "Spotify",
                    if (first) "session at start: signedIn=$value" else "session changed: signedIn=$value"
                )
                first = false
            }
        }
    }

    /**
     * Logs worker transitions: RUNNING, SUCCEEDED, FAILED, CANCELLED, and ENQUEUED again after a
     * retry. Only the worker's short class name, the run attempt and any numeric/boolean output
     * (counts, never text) are written. The state seen at startup is the baseline: it is not
     * replayed, except for jobs already RUNNING.
     */
    fun watchWork(context: Context, scope: CoroutineScope) {
        scope.launch {
            val query = WorkQuery.Builder.fromStates(WorkInfo.State.entries.toList()).build()
            val seen = HashMap<UUID, WorkInfo.State>()
            var primed = false
            WorkManager.getInstance(context).getWorkInfosFlow(query)
                .catch { DiagnosticsLog.warn("Work", "work observer stopped: ${it.javaClass.simpleName}") }
                .collect { infos ->
                    val present = HashSet<UUID>()
                    for (info in infos) {
                        present += info.id
                        val before = seen.put(info.id, info.state)
                        if (before == info.state) continue
                        if (!primed && info.state != WorkInfo.State.RUNNING) continue
                        if (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 0) continue
                        if (info.state == WorkInfo.State.BLOCKED) continue
                        DiagnosticsLog.event("Work", describe(info))
                    }
                    seen.keys.retainAll(present)
                    primed = true
                }
        }
    }

    private fun describe(info: WorkInfo): String {
        val name = info.tags
            .firstOrNull { it.startsWith("com.theveloper.pixelplay") }
            ?.substringAfterLast('.')
            ?: "unknown"
        val counts = if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED) {
            info.outputData.keyValueMap.entries
                .filter { it.value is Number || it.value is Boolean }
                .take(6)
                .joinToString(" ") { "${it.key}=${it.value}" }
        } else {
            ""
        }
        return "$name ${info.state} attempt=${info.runAttemptCount}" + if (counts.isNotEmpty()) " $counts" else ""
    }
}
