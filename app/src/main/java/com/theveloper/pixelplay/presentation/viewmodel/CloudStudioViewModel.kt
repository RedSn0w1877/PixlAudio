package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudSecrets
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioState
import com.theveloper.pixelplay.data.cloudstudio.toCloudSong
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Cloud Studio screens' facade over [CloudStudioStateHolder] (all state lives there, so it outlives the screens
 * and is shared by the queue, a playlist's menu and the song card).
 */
@HiltViewModel
class CloudStudioViewModel @Inject constructor(
    private val holder: CloudStudioStateHolder,
    private val engine: CloudStudioEngine,
) : ViewModel() {
    val ui: StateFlow<CloudStudioUiState> get() = holder.ui
    val engineState: StateFlow<CloudStudioState> get() = holder.engineState
    val settings: StateFlow<CloudSettingsSnapshot> get() = holder.settingsState
    /** What a start did ("Processing 1 song in the cloud", why nothing started…); screens show each line as a toast. */
    val messages: Flow<String> get() = holder.messages

    fun onScreenShown() = holder.onScreenShown()
    fun loadJobs() = holder.loadJobs()
    fun updateSettings(change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot) = holder.updateSettings(change)
    fun updateDraft(draft: CloudSecrets) = holder.updateDraft(draft)
    fun flushDraft() = holder.flushDraft()
    fun forgetKeys() = holder.forgetKeys()
    fun testConnection() = holder.testConnection()
    fun runSelftest() = holder.runSelftest()

    fun startNow(kind: CloudBatchKind, currentSong: Song?, title: String) =
        holder.startNow(kind, currentSong?.toCloudSong(), title)

    fun startNow(songs: List<Song>, title: String) =
        holder.startNow(songs.map { it.toCloudSong() }, title)

    fun cancel(jobKey: String) = holder.cancel(jobKey)
    fun retry(jobKey: String) = holder.retry(jobKey)
    fun remove(jobKey: String) = holder.remove(jobKey)
    fun clearFinished() = holder.clearFinished()

    fun summaryLine(): String? = engine.summaryLine()
    fun committedThisMonthMicroUsd(): Long = holder.committedThisMonthMicroUsd()

    override fun onCleared() {
        // The draft must not be lost when the screen goes for good.
        holder.flushDraft()
    }
}
