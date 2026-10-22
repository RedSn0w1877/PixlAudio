package com.theveloper.pixelplay.presentation

import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.presentation.model.isTechnical
import com.theveloper.pixelplay.presentation.model.visibleBackupSections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The backup pickers list the technical sections only in Developer mode. */
class SettingsBackupSectionsTest {

    @Test
    fun `normal mode lists only the sections a listener recognises`() {
        val shown = visibleBackupSections(BackupSection.entries, developerMode = false)

        assertEquals(
            listOf(
                BackupSection.PLAYLISTS,
                BackupSection.GLOBAL_SETTINGS,
                BackupSection.FAVORITES,
                BackupSection.LYRICS,
                BackupSection.SEARCH_HISTORY,
                BackupSection.PLAYBACK_HISTORY,
                BackupSection.EQUALIZER,
            ),
            shown,
        )
    }

    @Test
    fun `developer mode lists every section`() {
        assertEquals(
            BackupSection.entries.toList(),
            visibleBackupSections(BackupSection.entries, developerMode = true),
        )
    }

    @Test
    fun `the technical sections are exactly the five the spec names`() {
        val technical = BackupSection.entries.filter { it.isTechnical }.toSet()

        assertEquals(
            setOf(
                BackupSection.TRANSITIONS,
                BackupSection.ENGAGEMENT_STATS,
                BackupSection.QUICK_FILL,
                BackupSection.ARTIST_IMAGES,
                BackupSection.AI_USAGE_LOGS,
            ),
            technical,
        )
        assertFalse(BackupSection.PLAYLISTS.isTechnical)
        assertTrue(BackupSection.AI_USAGE_LOGS.isTechnical)
    }
}
