package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.worker.InstrumentalRenderJob
import com.theveloper.pixelplay.data.worker.RenderJobState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingUiReducerTest {

    private fun job(state: RenderJobState, percent: Int = 0) = InstrumentalRenderJob(
        id = "job", state = state, percent = percent, instrumentalPath = null,
        failureReason = null, automatic = false, createdAtMs = 1L
    )

    @Test
    fun `nothing rendered and nothing running is a plain Sing`() {
        assertEquals(SingUi(), singUi(available = false, active = false, job = null, remoteActive = false))
    }

    @Test
    fun `vocals off is active`() {
        val ui = singUi(available = true, active = true, job = null, remoteActive = false)
        assertTrue(ui.active)
        assertFalse(ui.rendering)
    }

    @Test
    fun `a queued render shows without a percentage`() {
        val ui = singUi(available = false, active = false, job = job(RenderJobState.QUEUED), remoteActive = false)
        assertTrue(ui.rendering)
        assertNull(ui.progress)
    }

    @Test
    fun `a running render shows its progress`() {
        val ui = singUi(available = false, active = false, job = job(RenderJobState.RUNNING, 48), remoteActive = false)
        assertTrue(ui.rendering)
        assertEquals(0.48f, ui.progress!!, 0.0001f)
    }

    @Test
    fun `a finished or failed render is not rendering`() {
        assertFalse(singUi(false, false, job(RenderJobState.FAILED), false).rendering)
        assertFalse(singUi(true, false, job(RenderJobState.SUCCEEDED, 100), false).rendering)
    }

    @Test
    fun `a render already on disk never shows as rendering`() {
        assertFalse(singUi(available = true, active = false, job = job(RenderJobState.RUNNING, 10), remoteActive = false).rendering)
    }

    @Test
    fun `Cast or Spotify Connect disables Sing`() {
        assertFalse(singUi(available = true, active = false, job = null, remoteActive = true).enabled)
        assertTrue(singUi(available = true, active = false, job = null, remoteActive = false).enabled)
    }
}
