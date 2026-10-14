package com.theveloper.pixelplay.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RingBufferLogTest {

    @TempDir
    lateinit var dir: File

    private fun log(maxBytes: Long = 2048L, maxLineChars: Int = 200) =
        RingBufferLog(File(dir, "diag"), maxBytes, maxLineChars)

    @Test
    fun appendedLinesAreReadBackInOrder() {
        val log = log()
        log.append("one")
        log.append("two")
        log.append("three")
        assertThat(log.readAll()).isEqualTo("one\ntwo\nthree\n")
    }

    @Test
    fun totalSizeNeverExceedsCap_andNewestLinesSurvive() {
        val cap = 4096L
        val log = log(maxBytes = cap)
        for (i in 1..2000) log.append("line-%05d xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".format(i))

        assertThat(log.sizeOnDisk()).isAtMost(cap)
        val text = log.readAll()
        assertThat(text).contains("line-02000")
        assertThat(text).doesNotContain("line-00001")
        // What remains is a contiguous tail: every line from the first kept one up to 2000.
        val numbers = text.lines().filter { it.isNotBlank() }.map { it.substringAfter("line-").take(5).toInt() }
        assertThat(numbers).isInOrder()
        assertThat(numbers.last()).isEqualTo(2000)
        assertThat(numbers.zipWithNext().all { (a, b) -> b == a + 1 }).isTrue()
    }

    @Test
    fun rotationKeepsExactlyTwoFiles() {
        val log = log(maxBytes = 2048L)
        for (i in 1..500) log.append("entry $i ".padEnd(60, '.'))
        val files = File(dir, "diag").listFiles().orEmpty().map { it.name }.sorted()
        assertThat(files).containsExactly(RingBufferLog.CURRENT_NAME, RingBufferLog.PREVIOUS_NAME)
    }

    @Test
    fun overlongLinesAreTruncated() {
        val log = log(maxBytes = 8192L, maxLineChars = 50)
        log.append("x".repeat(500))
        val text = log.readAll()
        assertThat(text.length).isLessThan(120)
        assertThat(text).contains("[truncated]")
    }

    @Test
    fun clearEmptiesTheLog_andItKeepsWorkingAfterwards() {
        val log = log()
        log.append("before")
        log.clear()
        assertThat(log.readAll()).isEmpty()
        assertThat(log.sizeOnDisk()).isEqualTo(0L)
        log.append("after")
        assertThat(log.readAll()).isEqualTo("after\n")
    }

    @Test
    fun anExistingFileIsAppendedToByANewInstance() {
        log().append("first run")
        val second = log()
        second.append("second run")
        assertThat(second.readAll()).isEqualTo("first run\nsecond run\n")
    }

    @Test
    fun writesFromManyThreadsAreAllKeptIntactWithinTheCap() {
        val log = log(maxBytes = 1_000_000L)
        val threads = (1..4).map { t ->
            Thread { for (i in 1..200) log.append("t$t-$i") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val lines = log.readAll().lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(800)
        assertThat(lines.all { it.matches(Regex("t[1-4]-\\d+")) }).isTrue()
    }
}
