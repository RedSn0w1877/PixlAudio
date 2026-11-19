package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.backup.model.BackupValidationResult
import com.theveloper.pixelplay.data.backup.validation.ModuleSchemaValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The brain's backup module (docs/brain-spec.md, 12.2): salt, taste changes and exclusions, strictly read, and nothing that is a cache. */
class BrainBackupFormatTest {
    private val gson = Gson()

    private val rows = listOf(
        BrainBackupFormat.FeedbackRow(1L, 2, 3, 99L, 1_760_000_000_000L, null),
        BrainBackupFormat.FeedbackRow(2L, 2, 4, 100L, 1_760_000_100_000L, 1_760_000_200_000L),
    )

    @Test
    fun `a payload reads back to exactly what was written`() {
        val text = BrainBackupFormat.encode(gson, -1234567890123L, "[{\"id\":\"a\"}]", rows)
        val p = BrainBackupFormat.decode(gson, text)!!
        assertEquals("-1234567890123", p.salt)
        assertEquals("[{\"id\":\"a\"}]", p.adjustments)
        assertEquals(2, p.feedback.size)
        assertEquals(99L, p.feedback[0].targetId)
        assertNull(p.feedback[0].undoneAtMs)
        assertEquals(1_760_000_200_000L, p.feedback[1].undoneAtMs)
    }

    @Test
    fun `an install with no salt yet and no changes is a valid backup`() {
        val p = BrainBackupFormat.decode(gson, BrainBackupFormat.encode(gson, null, "", emptyList()))!!
        assertNull(p.salt)
        assertEquals("", p.adjustments)
        assertTrue(p.feedback.isEmpty())
    }

    @Test
    fun `anything that is not a brain backup is refused`() {
        val bad = listOf(
            "", "[]", "{}", "not json", "{\"format\":\"other\"}", "{\"format\":\"brain-backup-v1\"}",
            "{\"format\":\"brain-backup-v1\",\"salt\":\"abc\",\"adjustments\":\"\",\"feedback\":[]}",
            "{\"format\":\"brain-backup-v1\",\"adjustments\":\"\",\"feedback\":[{\"id\":1}]}",
        )
        for (text in bad) assertNull(BrainBackupFormat.decode(gson, text), text)
    }

    @Test
    fun `the payload holds no cache`() {
        val text = BrainBackupFormat.encode(gson, 5L, "", rows).lowercase()
        for (cache in listOf("affinity", "island", "embedding", "learner", "posteriors", "plan")) assertFalse(cache in text, cache)
    }

    @Test
    fun `the section is in the backup list and its payload is checked`() {
        assertEquals(BackupSection.BRAIN, BackupSection.fromKey("brain"))
        val validator = ModuleSchemaValidator(com.theveloper.pixelplay.data.backup.validation.ContentSanitizer())
        assertTrue(validator.validate(BackupSection.BRAIN, BrainBackupFormat.encode(gson, 5L, "", rows)) is BackupValidationResult.Valid)
        assertTrue(validator.validate(BackupSection.BRAIN, "[]") is BackupValidationResult.Invalid)
        assertTrue(validator.validate(BackupSection.BRAIN, "{\"format\":\"nope\"}") is BackupValidationResult.Invalid)
    }
}
