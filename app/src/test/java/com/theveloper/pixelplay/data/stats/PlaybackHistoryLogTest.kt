package com.theveloper.pixelplay.data.stats

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlaybackHistoryLogTest {

    private fun repo(dir: File): PlaybackStatsRepository {
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        return PlaybackStatsRepository(ctx)
    }

    @Test
    fun `recording plays appends one small line each and never writes the base file`() = runTest {
        val dir = createTempDirectory("history-log-").toFile()
        val repository = repo(dir)
        val now = System.currentTimeMillis()
        repeat(50) { repository.recordPlayback("song-$it", 30_000L, now + it) }

        val log = File(dir, "playback_history.log")
        val lines = log.readLines()
        assertThat(lines).hasSize(50)
        assertThat(lines.maxOf { it.length }).isLessThan(250)
        assertThat(File(dir, "playback_history.json").exists()).isFalse()
        assertThat(repository.exportEventsForBackup()).hasSize(50)
    }

    @Test
    fun `a new process sees the logged plays and skips a torn last line`() = runTest {
        val dir = createTempDirectory("history-log-").toFile()
        val now = System.currentTimeMillis()
        val first = repo(dir)
        repeat(3) { first.recordPlayback("song-$it", 10_000L, now + it) }
        File(dir, "playback_history.log").appendText("{\"songId\":\"torn\",\"timest")

        val second = repo(dir)
        assertThat(second.exportEventsForBackup().map { it.songId })
            .containsExactly("song-0", "song-1", "song-2")
    }

    @Test
    fun `merge drops logged events that compaction already folded into the base`() {
        val a = PlaybackStatsRepository.PlaybackEvent("a", 100L, 10L, 90L, 100L)
        val b = PlaybackStatsRepository.PlaybackEvent("b", 200L, 10L, 190L, 200L)
        val c = PlaybackStatsRepository.PlaybackEvent("c", 300L, 10L, 290L, 300L)
        assertThat(PlaybackHistoryLog.merge(listOf(a, b), listOf(b, c))).containsExactly(a, b, c).inOrder()
        assertThat(PlaybackHistoryLog.merge(listOf(a), emptyList())).containsExactly(a)
    }

    @Test
    fun `log compacts into the base after enough plays`() = runTest {
        val dir = createTempDirectory("history-log-").toFile()
        val repository = repo(dir)
        val now = System.currentTimeMillis()
        repeat(250) { repository.recordPlayback("s$it", 1_000L, now + it) }
        // AtomicFile is a stub in JVM tests, so only check the log was folded away and memory is intact.
        assertThat(File(dir, "playback_history.log").let { if (it.exists()) it.readLines().size else 0 })
            .isLessThan(200)
        assertThat(repository.exportEventsForBackup()).hasSize(250)
    }
}
