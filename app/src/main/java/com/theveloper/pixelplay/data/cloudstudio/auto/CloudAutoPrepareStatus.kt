package com.theveloper.pixelplay.data.cloudstudio.auto

import android.content.Context
import com.theveloper.pixelplay.data.cloudstudio.CloudBudget
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * What Settings › Sing says under "Prepare songs ahead in the cloud": a status line ("3 songs today · $0.12 of $1.00
 * this month", or "Waiting for Wi-Fi") and, for developers, the songs the last run picked and why. The line is worked out
 * only while a screen is collecting it (and again every few seconds then), so it costs nothing otherwise.
 */
@Singleton
class CloudAutoPrepareStatus @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: CloudStudioSettings,
    private val engine: CloudStudioEngine,
) : CloudAutoReport {
    private val environment = LiveCloudAutoEnvironment(context)
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MS)
        }
    }

    /** Empty while the feature is off; says so when its switch is on but Cloud processing, which it needs, is off. */
    val status: StateFlow<String> = combine(settings.settings, ticker) { snapshot, _ ->
        when {
            !snapshot.autoPrepareEnabled -> ""
            !snapshot.enabled -> CloudAutoPrepare.CLOUD_OFF_LINE
            else -> lineFor(snapshot)
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), "")

    private val _why = MutableStateFlow<List<String>>(emptyList())
    @Volatile private var whyLoaded = false

    /** The last run's picks and why (developer mode); read from disk the first time it is asked for. */
    val why: StateFlow<List<String>>
        get() {
            if (!whyLoaded) {
                whyLoaded = true
                _why.value = prefs.getString(WHY_KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }
            }
            return _why.asStateFlow()
        }

    override fun picked(lines: List<String>) {
        whyLoaded = true
        prefs.edit().putString(WHY_KEY, lines.joinToString("\n")).apply()
        _why.value = lines
    }

    private suspend fun lineFor(snapshot: CloudSettingsSnapshot): String {
        engine.loadForDisplay()
        val now = System.currentTimeMillis()
        val today = settings.autoDayCount(CloudBudget.dayStartMs(now, java.time.ZoneId.systemDefault()))
        val waiting = if (environment.conditions().unmetered) null else AutoBlock.NOT_ON_WIFI.label
        return CloudAutoPrepare.statusLine(
            enabled = true,
            todayCount = today,
            monthSpentMicroUsd = engine.autoCommittedThisMonthMicroUsd(),
            budgetMicroUsd = snapshot.effectiveAutoBudgetMicroUsd,
            waitingFor = waiting,
        )
    }

    private companion object {
        const val FILE = "cloud_auto_prepare_status_v1"
        const val WHY_KEY = "why"
        const val REFRESH_MS = 10_000L
    }
}
