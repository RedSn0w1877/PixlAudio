package com.theveloper.pixelplay.data.worker

import androidx.work.ListenableWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaisStudioBatchTest {
    @Test
    fun `failed batch song permits the next dependency while retaining its retry details`() {
        val result = TaisStudioWorker.failureResult("spotify_a", "Audio unavailable", continueOnFailure = true)
        assertTrue(result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals("spotify_a", output.getString(TaisStudioWorker.OUTPUT_SONG_ID))
        assertEquals(TaisStudioWorker.OUTCOME_FAILED, output.getString(TaisStudioWorker.OUTPUT_OUTCOME))
        assertEquals("Audio unavailable", output.getString(TaisStudioWorker.OUTPUT_FAILURE_REASON))
        assertFalse(output.getBoolean(TaisStudioWorker.OUTPUT_WORD_SYNC_PRODUCED, true))
    }

    @Test
    fun `standalone failure remains a WorkManager failure`() {
        val result = TaisStudioWorker.failureResult("song", "Audio unavailable", continueOnFailure = false)
        assertTrue(result is ListenableWorker.Result.Failure)
    }
}
