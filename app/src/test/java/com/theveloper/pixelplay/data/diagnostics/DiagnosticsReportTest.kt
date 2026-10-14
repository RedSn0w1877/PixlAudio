package com.theveloper.pixelplay.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DiagnosticsReportTest {

    private val info = DiagnosticsInfo(
        appId = "com.theveloper.pixelplay",
        versionName = "2.0.0-beta",
        versionCode = 42,
        buildType = "release",
        commit = "49aa403",
        buildDate = "2026-10-14",
        device = "Google Pixel 10 Pro (blazer)",
        android = "Android 17 (API 37), patch 2026-10-05",
        abi = "arm64-v8a",
        locale = "en-US",
        spotifySignInConfigured = true,
        cloudKeysBundled = false,
        generatedAtMs = 1_760_000_000_000L
    )

    @Test
    fun report_hasBuildDeviceExitsAndLogSections() {
        val text = DiagnosticsReport.render(
            info,
            exitReasons = listOf("2026-10-14T10:00:00Z reason=CRASH status=0 importance=FOREGROUND rssKb=1"),
            log = "2026-10-14T10:00:01Z W/Http: GET api.spotify.com/v1/me -> 429\n"
        )
        assertThat(text).contains("49aa403")
        assertThat(text).contains("2026-10-14")
        assertThat(text).contains("Pixel 10 Pro")
        assertThat(text).contains("reason=CRASH")
        assertThat(text).contains("-> 429")
        assertThat(text.indexOf("== Build ==")).isLessThan(text.indexOf("== Device =="))
        assertThat(text.indexOf("== Device ==")).isLessThan(text.indexOf("== Log"))
    }

    @Test
    fun emptyExitsAndLogAreSaidSo() {
        val text = DiagnosticsReport.render(info, emptyList(), "")
        assertThat(text).contains("(none recorded)")
        assertThat(text).contains("(empty)")
    }

    @Test
    fun exitReasonNames_coverTheCommonOnes() {
        assertThat(DiagnosticsReport.exitReasonName(4)).isEqualTo("CRASH")
        assertThat(DiagnosticsReport.exitReasonName(6)).isEqualTo("ANR")
        assertThat(DiagnosticsReport.exitReasonName(3)).isEqualTo("LOW_MEMORY")
        assertThat(DiagnosticsReport.exitReasonName(999)).isEqualTo("REASON_999")
    }

    @Test
    fun exitDescription_isRedacted() {
        val line = DiagnosticsReport.describeExit(
            0L, 4, 0, 100, "crash at https://example.com/x?token=SECRETVALUE1234", 10
        )
        assertThat(line).doesNotContain("SECRETVALUE1234")
        assertThat(line).contains("reason=CRASH")
    }
}
