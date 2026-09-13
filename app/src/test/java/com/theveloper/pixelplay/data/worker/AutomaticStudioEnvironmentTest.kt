package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.ListenableWorker
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticStudioEnvironmentTest {
    private fun environment(online: () -> Boolean = { true }): AutomaticStudioEnvironment {
        PlaybackActivityTracker.setPlaybackActive(false)
        val context = mockk<Context>()
        val battery = mockk<Intent>()
        val power = mockk<PowerManager>()
        val storage = mockk<File>()
        val connectivity = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.registerReceiver(null, any<IntentFilter>()) } returns battery
        every { battery.getIntExtra(any(), any()) } answers {
            when (firstArg<String>()) {
                BatteryManager.EXTRA_LEVEL -> 80
                BatteryManager.EXTRA_SCALE -> 100
                BatteryManager.EXTRA_STATUS -> BatteryManager.BATTERY_STATUS_DISCHARGING
                else -> -1
            }
        }
        every { context.getSystemService(PowerManager::class.java) } returns power
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } answers { online() }
        every { power.currentThermalStatus } returns PowerManager.THERMAL_STATUS_NONE
        every { context.filesDir } returns storage
        every { storage.usableSpace } returns AutomaticStudioPolicy.MIN_FREE_BYTES * 2
        return AutomaticStudioEnvironment(context).apply { setEnabled(true, true) }
    }

    @Test fun `restarted background worker can begin when device conditions allow`() = runTest {
        val environment = environment()
        var ran = false
        val result = environment.runQuietly(AutomaticStudioKind.INSTRUMENTAL) { ran = true; ListenableWorker.Result.success() }
        assertTrue(ran)
        assertFalse((result as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
    }

    @Test fun `leaving the app does not cancel quiet background work`() = runTest {
        val environment = environment().apply { setAppVisible(true) }
        var cleanedUp = false
        val work = async {
            environment.runQuietly(AutomaticStudioKind.INSTRUMENTAL) {
                try { awaitCancellation() } finally { cleanedUp = true }
            }
        }
        runCurrent()
        environment.setAppVisible(false)
        advanceTimeBy(3_001)
        assertFalse(work.isCompleted)
        work.cancelAndJoin()
        assertTrue(cleanedUp)
    }

    @Test fun `offline lyric work never begins and local instrumentals remain eligible`() = runTest {
        val environment = environment { false }.apply { setAppVisible(true) }
        var lookupStarted = false
        val result = environment.runQuietly(AutomaticStudioKind.LYRICS) {
            lookupStarted = true
            ListenableWorker.Result.success()
        }
        assertFalse(lookupStarted)
        assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
        assertNull(environment.blockedReason(AutomaticStudioKind.INSTRUMENTAL))
    }

    @Test fun `losing usable connectivity defers a running lookup instead of recording a catalog miss`() = runTest {
        var online = true
        val environment = environment { online }.apply { setAppVisible(true) }
        val work = async { environment.runQuietly(AutomaticStudioKind.LYRICS) { awaitCancellation() } }
        runCurrent()
        online = false
        advanceTimeBy(3_001)
        assertTrue((work.await() as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
    }

    @Test fun `a fast offline failure is deferred before the next watchdog tick`() = runTest {
        var online = true
        val environment = environment { online }.apply { setAppVisible(true) }
        val result = environment.runQuietly(AutomaticStudioKind.LYRICS) {
            online = false
            ListenableWorker.Result.failure()
        }
        assertTrue((result as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
    }

    @Test fun `turning off one feature cancels its running task without disabling the other`() = runTest {
        val environment = environment().apply { setAppVisible(true) }
        val work = async { environment.runQuietly(AutomaticStudioKind.LYRICS) { awaitCancellation() } }
        runCurrent()
        environment.setEnabled(false, true)
        advanceTimeBy(3_001)
        assertTrue((work.await() as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
        assertNull(environment.blockedReason(AutomaticStudioKind.INSTRUMENTAL))
    }

    @Test fun `automatic time budget cancels unfinished work before WorkManager limit`() = runTest {
        val environment = environment().apply { setAppVisible(true) }
        var cleanedUp = false
        val work = async {
            environment.runQuietly(AutomaticStudioKind.LYRICS) {
                try { awaitCancellation() } finally { cleanedUp = true }
            }
        }
        runCurrent()
        advanceTimeBy(AutomaticStudioPolicy.MAX_WORK_DURATION_MS + 1)
        assertTrue((work.await() as ListenableWorker.Result.Success).outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false))
        assertTrue(cleanedUp)
    }

    @Test fun `WorkManager cancellation propagates instead of becoming success`() = runTest {
        val environment = environment().apply { setAppVisible(true) }
        val work = async { environment.runQuietly(AutomaticStudioKind.LYRICS) { awaitCancellation() } }
        runCurrent()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
    }
}
