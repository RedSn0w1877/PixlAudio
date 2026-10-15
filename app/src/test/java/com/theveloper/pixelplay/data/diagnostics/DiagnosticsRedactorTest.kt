package com.theveloper.pixelplay.data.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DiagnosticsRedactorTest {

    @AfterEach
    fun reset() {
        DiagnosticsRedactor.clearSensitiveForTest()
    }

    @Test
    fun url_dropsQueryStringAndFragment() {
        val out = DiagnosticsRedactor.redact(
            "GET https://rr3.example.googlevideo.com/videoplayback?expire=1&sig=AAAA1111BBBB&key=abc#frag failed"
        )
        assertThat(out).contains("https://rr3.example.googlevideo.com/videoplayback?…")
        assertThat(out).doesNotContain("AAAA1111BBBB")
        assertThat(out).doesNotContain("expire=1")
        assertThat(out).doesNotContain("frag")
        assertThat(out).endsWith("failed")
    }

    @Test
    fun url_dropsCredentialsInAuthority() {
        val out = DiagnosticsRedactor.redact("proxy http://user:hunter2@host.example:8080/path")
        assertThat(out).doesNotContain("hunter2")
        assertThat(out).contains("host.example:8080/path")
    }

    @Test
    fun oauthCallbackCodeIsRemoved() {
        val out = DiagnosticsRedactor.redact("redirect pixelplay://spotify-callback?code=AQDabcdefghijk123456&state=xyz")
        assertThat(out).doesNotContain("AQDabcdefghijk123456")
        assertThat(out).doesNotContain("state=xyz")
        assertThat(out).contains("pixelplay://spotify-callback")
    }

    @Test
    fun bearerToken_isRedacted() {
        val out = DiagnosticsRedactor.redact("Authorization: Bearer BQC4abcDEFghi_jklMNOpqr-stuVWXyz0123")
        assertThat(out).doesNotContain("BQC4abcDEFghi")
        assertThat(out).contains("<redacted>")
    }

    @Test
    fun secretPairs_areRedacted_butHttpStatusCodesStay() {
        val out = DiagnosticsRedactor.redact("refresh_token=AQBxyz987 access_token: abc.def code=403 status=429")
        assertThat(out).doesNotContain("AQBxyz987")
        assertThat(out).doesNotContain("abc.def")
        assertThat(out).contains("code=403")
        assertThat(out).contains("status=429")
    }

    @Test
    fun jwt_isRedacted() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val out = DiagnosticsRedactor.redact("token was $jwt end")
        assertThat(out).doesNotContain("eyJhbGci")
        assertThat(out).contains("end")
    }

    @Test
    fun email_isRedacted() {
        val out = DiagnosticsRedactor.redact("signed in as someone.name+tag@example.co.uk ok")
        assertThat(out).doesNotContain("someone.name")
        assertThat(out).contains(DiagnosticsRedactor.EMAIL)
    }

    @Test
    fun longOpaqueString_isRedacted_butIdentifiersAreNot() {
        val key = "AIzaSyD4k9QwErTyUiOpAsDfGhJkLzXcVbNm123"
        assertThat(DiagnosticsRedactor.redact("key in header $key")).doesNotContain(key)
        val identifier = "ERROR_CODE_IO_BAD_HTTP_STATUS PlaybackStartupFailureExceptionHandlerImpl"
        assertThat(DiagnosticsRedactor.redact(identifier)).isEqualTo(identifier)
    }

    @Test
    fun ordinaryMessageIsUntouched() {
        val line = "SpotifySyncWorker SUCCEEDED attempt=0 imported=1200 playlists=14"
        assertThat(DiagnosticsRedactor.redact(line)).isEqualTo(line)
    }

    @Test
    fun opaqueId_isStableShortAndHidesTheInput() {
        val a = DiagnosticsRedactor.opaqueId("Blinding Lights")
        assertThat(a).isEqualTo(DiagnosticsRedactor.opaqueId("  blinding lights "))
        assertThat(a).matches("#[0-9a-f]{6}")
        assertThat(a).isNotEqualTo(DiagnosticsRedactor.opaqueId("Starboy"))
        assertThat(a.lowercase()).doesNotContain("blinding")
        assertThat(DiagnosticsRedactor.opaqueId(null)).isEqualTo("#none")
    }

    @Test
    fun registeredTitlesAreReplacedEverywhereInThirdPartyText() {
        DiagnosticsRedactor.registerSensitive("Blinding Lights")
        val id = DiagnosticsRedactor.opaqueId("Blinding Lights")
        val out = DiagnosticsRedactor.redact("Error fetching lyrics for The Weeknd - BLINDING LIGHTS (404)")
        assertThat(out).doesNotContain("LIGHTS")
        assertThat(out).contains(id)
    }

    @Test
    fun emptyInputIsReturnedAsIs() {
        assertThat(DiagnosticsRedactor.redact("")).isEmpty()
    }
}
