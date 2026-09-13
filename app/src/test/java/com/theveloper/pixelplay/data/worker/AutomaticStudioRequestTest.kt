package com.theveloper.pixelplay.data.worker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutomaticStudioRequestTest {
    @Test fun `manual requests are never tagged for automatic cancellation`() {
        val lyrics = TaisStudioWorker.buildRequest("song", "content://media/song")
        val stems = StemSeparatorWorker.buildRequest("song", "content://media/song")
        for (request in listOf(lyrics, stems)) {
            assertFalse(AUTO_STUDIO_WORK_TAG in request.tags)
            assertFalse(request.workSpec.input.getBoolean(INPUT_AUTOMATIC_STUDIO, true))
        }
    }

    @Test fun `automatic jobs carry quiet mode and still appear in existing progress rows`() {
        val lyrics = TaisStudioWorker.buildRequest("song", "content://media/song", automatic = true)
        val stems = StemSeparatorWorker.buildRequest("song", "content://media/song", automatic = true)
        assertTrue(AUTO_LYRICS_TAG in lyrics.tags)
        assertTrue(AUTO_INSTRUMENTAL_TAG in stems.tags)
        for (request in listOf(lyrics, stems)) {
            assertTrue(AUTO_STUDIO_WORK_TAG in request.tags)
            assertTrue(PIXELPLAY_JOB_TAG in request.tags)
            assertTrue(AUTO_SONG_TAG_PREFIX + "song" in request.tags)
            assertTrue(request.workSpec.input.getBoolean(INPUT_AUTOMATIC_STUDIO, false))
            assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
            assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        }
    }
}
