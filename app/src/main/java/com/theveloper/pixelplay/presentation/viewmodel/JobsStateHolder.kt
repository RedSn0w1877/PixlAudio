package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.worker.PIXELPLAY_JOB_TAG
import com.theveloper.pixelplay.data.worker.PixelPlayJobKind
import com.theveloper.pixelplay.data.worker.pixelPlayJobKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One tagged [WorkInfo], reduced to what the "active jobs" UI actually shows. */
data class PixelPlayJob(
    val id: String,
    val kind: PixelPlayJobKind,
    val state: WorkInfo.State,
    val percent: Int?,
    val detail: String?
)

/**
 * Watches every worker tagged [PIXELPLAY_JOB_TAG] so the home screen can show one combined
 * "what's running right now" list instead of the user having to know which feature owns which
 * background job.
 */
@Singleton
class JobsStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    private val _activeJobs = MutableStateFlow<List<PixelPlayJob>>(emptyList())
    val activeJobs: StateFlow<List<PixelPlayJob>> = _activeJobs.asStateFlow()

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            workManager.getWorkInfosByTagFlow(PIXELPLAY_JOB_TAG).collect { infos ->
                _activeJobs.value = infos
                    .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    .map { it.toPixelPlayJob() }
            }
        }
    }

    private fun WorkInfo.toPixelPlayJob(): PixelPlayJob = PixelPlayJob(
        id = id.toString(),
        kind = pixelPlayJobKind(),
        state = state,
        percent = progress.getInt("percent", -1).takeIf { it in 0..100 },
        detail = progress.getString("detail")
    )
}
