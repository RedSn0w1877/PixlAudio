package com.theveloper.pixelplay.data.youtube

import android.net.Uri
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.youtube.newpipe.NewPipeStreamResolver
import com.theveloper.pixelplay.data.youtube.potoken.PixelPlayPoTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debugging tool: fires MANY request variants at googlevideo and records what each one gets
 * back. Output strings are in English on purpose — this is text the user reads and pastes
 * back, unlike the rest of this package's code comments which follow the codebase's
 * Spanish convention.
 *
 * Temporary: remove once playback is stable.
 */
@Singleton
class StreamDebugProbe @Inject constructor(
    private val spotifyDao: SpotifyDao,
    private val innerTubeClient: InnerTubeClient,
    private val trackMatcher: TrackMatcher,
    private val cipherSolver: SignatureCipherSolver,
    private val newPipeStreamResolver: NewPipeStreamResolver,
    private val poTokenProvider: PixelPlayPoTokenProvider,
    @param:com.theveloper.pixelplay.di.YouTubeOkHttpClient private val okHttpClient: OkHttpClient
) {

    private val ranges: List<Pair<String, String?>> = listOf(
        "no-range" to null,
        "bytes=0-1" to "bytes=0-1",
        "bytes=0-1023" to "bytes=0-1023",
        "bytes=0-1MB" to "bytes=0-1048575",
        "bytes=0-open" to "bytes=0-"
    )

    suspend fun run(): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()

        val song = runCatching { spotifyDao.getAnySong() }.getOrNull()
        if (song == null) {
            return@withContext "No imported tracks yet. Sync first."
        }
        out.appendLine("DEEP PROBE")
        out.appendLine("Song: ${song.title} — ${song.artist}")

        val match = runCatching { trackMatcher.findMatch(song) }.getOrNull()
        if (match == null) {
            out.appendLine("Matcher: no match. Can't probe the stream.")
            return@withContext out.toString()
        }
        val videoId = match.videoId
        out.appendLine("videoId: $videoId  (match ${"%.2f".format(match.score)})")

        out.appendLine("POTOKEN (direct, bypassing NewPipeExtractor's own try/catch):")
        val poTokenStart = System.currentTimeMillis()
        val poTokenResult = runCatching { poTokenProvider.getWebClientPoTokenOrThrow(videoId) }
        val poTokenMs = System.currentTimeMillis() - poTokenStart
        poTokenResult.fold(
            onSuccess = { result ->
                if (result == null) {
                    out.appendLine("  returned null after ${poTokenMs}ms (no exception thrown)")
                } else {
                    out.appendLine(
                        "  OK after ${poTokenMs}ms: visitorData len=${result.visitorData.length}, " +
                            "playerRequestPoToken len=${result.playerRequestPoToken.length}, " +
                            "streamingDataPoToken=${if (result.streamingDataPoToken != null) "present" else "null"}"
                    )
                }
            },
            onFailure = { e ->
                out.appendLine("  THREW after ${poTokenMs}ms: ${e.javaClass.name}: ${e.message}")
                e.cause?.let { out.appendLine("    caused by: ${it.javaClass.name}: ${it.message}") }
            }
        )
        out.appendLine("─".repeat(40))

        out.appendLine("NEWPIPE EXTRACTOR:")
        val newPipeResolved = runCatching { newPipeStreamResolver.resolve(videoId) }.getOrNull()
        if (newPipeResolved == null) {
            out.appendLine("  failed: ${newPipeStreamResolver.lastDetail}")
        } else {
            out.appendLine("  resolved: ${newPipeStreamResolver.lastDetail}")
            out.appendLine("  params: ${urlParams(newPipeResolved.url)}")
            val probe = fire(newPipeResolved.url, newPipeResolved.userAgent, "bytes=0-1023")
            out.appendLine("  bytes=0-1023 -> $probe")
            val probeOpen = fire(newPipeResolved.url, newPipeResolved.userAgent, null)
            out.appendLine("  no-range -> $probeOpen")

            // Descarga seguida desde el byte 0 hasta que se rompa, exactamente lo que hace
            // el proxy real al reproducir. Dice a qué número de bytes aparece el muro.
            out.appendLine("  SEQUENTIAL fetch from 0 (300 KB chunks):")
            var seqPos = 0L
            val seqChunk = 300_000L
            var brokeAt: Long? = null
            for (i in 0 until 8) {
                val end = seqPos + seqChunk - 1
                val result = fire(newPipeResolved.url, newPipeResolved.userAgent, "bytes=$seqPos-$end")
                out.appendLine("    [$i] bytes=$seqPos-$end -> $result")
                if (!result.startsWith("HTTP 200") && !result.startsWith("HTTP 206")) {
                    brokeAt = seqPos
                    break
                }
                seqPos += seqChunk
            }

            // La pregunta que de verdad importa: si se rompe, ¿un enlace COMPLETAMENTE
            // NUEVO puede seguir desde ese mismo punto medio, o solo sirve desde el byte 0?
            // De la respuesta depende si "pedir un enlace nuevo a mitad de canción" (lo que
            // ya hace el proxy) puede funcionar alguna vez, o si hace falta otra estrategia.
            if (brokeAt != null) {
                val fresh = runCatching { newPipeStreamResolver.resolve(videoId) }.getOrNull()
                if (fresh != null) {
                    val sameUrl = fresh.url == newPipeResolved.url
                    val zeroTest = fire(fresh.url, fresh.userAgent, "bytes=0-1023")
                    val midTest = fire(fresh.url, fresh.userAgent, "bytes=$brokeAt-${brokeAt + 1023}")
                    out.appendLine(
                        "  RESUME TEST — fresh link ${if (sameUrl) "(same url)" else "(different url)"}:"
                    )
                    out.appendLine("    byte 0          -> $zeroTest")
                    out.appendLine("    byte $brokeAt (where it broke) -> $midTest")
                    out.appendLine(
                        if (midTest.startsWith("HTTP 200") || midTest.startsWith("HTTP 206")) {
                            "  => Fresh links CAN resume mid-file. The existing retry-on-403 should work — if real playback still dies, something else is wrong with how it's wired."
                        } else {
                            "  => Fresh links can ONLY start from byte 0, never resume mid-file. Retrying mid-stream can never work; the proxy needs to re-fetch from 0 and skip forward, or the per-link budget needs to be respected proactively instead of reactively."
                        }
                    )
                } else {
                    out.appendLine("  RESUME TEST — could not get a fresh link to test")
                }
            }
        }
        out.appendLine("─".repeat(40))

        out.appendLine("base.js (own decoder, fallback path):")
        val baseJsDiag = runCatching { cipherSolver.diagnose() }
            .getOrElse { "  failed: ${it.message}" }
        baseJsDiag.lines().forEach { out.appendLine("  $it") }
        out.appendLine("─".repeat(40))

        for (profile in InnerTubeContexts.PLAYER_PROFILES) {
            out.appendLine("=== ${profile.name} ===")
            val response = runCatching { innerTubeClient.fetchPlayer(videoId, profile) }.getOrNull()
            if (response == null) {
                out.appendLine("  player: no response (${innerTubeClient.lastFailureReason ?: "?"})")
                out.appendLine()
                continue
            }
            if (!response.isPlayable) {
                out.appendLine("  player: ${response.status}${response.reason?.let { " — $it" }.orEmpty()}")
                out.appendLine()
                continue
            }
            val best = response.formats.pickBestAudio()
            if (best == null) {
                out.appendLine("  player: OK but 0 audio formats (PoToken signal)")
                out.appendLine()
                continue
            }
            out.appendLine(
                "  best: itag ${best.itag}, ${best.bitrate / 1000} kbps, " +
                    "${best.mimeType?.substringBefore(';') ?: "?"}, clen=${best.contentLength ?: "?"}"
            )

            val rawUrl = best.url ?: run {
                val cipher = best.signatureCipher
                if (cipher == null) {
                    out.appendLine("  no url or cipher; skipping")
                    out.appendLine()
                    return@run null
                }
                out.appendLine("  (came ciphered; decrypting with base.js)")
                runCatching { cipherSolver.resolveCipheredUrl(cipher) }.getOrNull()
            }
            if (rawUrl == null) {
                out.appendLine("  could not obtain a URL")
                out.appendLine()
                continue
            }

            val ua = profile.userAgent
            out.appendLine("  params(raw): ${urlParams(rawUrl)}")

            out.appendLine("  RAW url:")
            for ((label, range) in ranges) {
                out.appendLine("    ${label.padEnd(13)} -> ${fire(rawUrl, ua, range)}")
            }
            best.contentLength?.let { clen ->
                out.appendLine("    full 0-${clen - 1}".padEnd(17) + " -> ${fire(rawUrl, ua, "bytes=0-${clen - 1}")}")
            }

            // Back-to-back downloads from byte 0, each one asking for the next slice —
            // exactly what the real proxy does while playing. If there's a byte cap (per
            // URL, per video, or per session), this says the exact number where it appears.
            out.appendLine("  SEQUENTIAL fetch from 0 (300 KB chunks, no delay):")
            var seqPos = 0L
            val seqChunk = 300_000L
            var brokeAt: Long? = null
            for (i in 0 until 8) {
                val end = seqPos + seqChunk - 1
                val result = fire(rawUrl, ua, "bytes=$seqPos-$end")
                out.appendLine("    [$i] bytes=$seqPos-$end -> $result")
                if (!result.startsWith("HTTP 200") && !result.startsWith("HTTP 206")) {
                    out.appendLine("    (broke after ~$seqPos accumulated bytes)")
                    brokeAt = seqPos
                    break
                }
                seqPos += seqChunk
            }

            if (brokeAt != null) {
                val breakPoint = brokeAt

                // Test A: does a FRESH url work starting from byte 0? (proves whether the
                // cap resets per-url at all, vs. following the video/IP regardless of url)
                val freshA = runCatching { innerTubeClient.fetchPlayer(videoId, profile) }.getOrNull()
                val freshUrlA = freshA?.formats?.pickBestAudio()?.url
                if (freshUrlA != null) {
                    val sameUrl = freshUrlA == rawUrl
                    val tiny = fire(freshUrlA, ua, "bytes=0-1023")
                    out.appendLine(
                        "  TEST A — fresh url ${if (sameUrl) "(identical to previous)" else "(different)"}, " +
                            "1 KB at byte 0 -> $tiny"
                    )
                } else {
                    out.appendLine("  TEST A — could not get a fresh url to compare")
                }

                // Test B: does a FRESH url work starting from the SAME mid-file position
                // where the original broke? This is what the app's real retry-on-403 logic
                // actually needs — Test A alone doesn't prove this.
                val freshB = runCatching { innerTubeClient.fetchPlayer(videoId, profile) }.getOrNull()
                val freshUrlB = freshB?.formats?.pickBestAudio()?.url
                if (freshUrlB != null) {
                    val midResult = fire(freshUrlB, ua, "bytes=$breakPoint-${breakPoint + 1023}")
                    out.appendLine(
                        "  TEST B — fresh url, 1 KB starting at byte $breakPoint (where it broke) -> $midResult"
                    )
                    out.appendLine(
                        if (midResult.startsWith("HTTP 200") || midResult.startsWith("HTTP 206")) {
                            "  => A fresh url CAN resume from the middle. Retry-with-fresh-url should work; if it doesn't in the real app, the bug is in how the retry is wired, not the theory."
                        } else {
                            "  => A fresh url CANNOT resume from the middle — only from byte 0. Retrying mid-file with a new url will never work; the fix has to restart from 0 and skip the already-sent prefix, or avoid needing more than ~$breakPoint bytes per url."
                        }
                    )
                } else {
                    out.appendLine("  TEST B — could not get a fresh url to test the middle")
                }

                // Test C: does slowing down avoid the wall? If the cap is really about burst
                // rate (anti-scraping) rather than a hard byte count, spacing requests out
                // should push the break point much further, or remove it.
                out.appendLine("  TEST C — PACED sequential fetch from 0 (300 KB chunks, 1.5s apart):")
                var pacedPos = 0L
                for (i in 0 until 8) {
                    if (i > 0) delay(1500)
                    val end = pacedPos + seqChunk - 1
                    val result = fire(rawUrl, ua, "bytes=$pacedPos-$end")
                    out.appendLine("    [$i] bytes=$pacedPos-$end -> $result")
                    if (!result.startsWith("HTTP 200") && !result.startsWith("HTTP 206")) {
                        out.appendLine("    (broke after ~$pacedPos accumulated bytes, same as unpaced: ${pacedPos == breakPoint})")
                        break
                    }
                    pacedPos += seqChunk
                }
            }

            val transformed = runCatching { cipherSolver.applyNTransform(rawUrl) }.getOrNull()
            if (transformed != null && transformed != rawUrl) {
                out.appendLine("  params(n-fix): ${urlParams(transformed)}")
                out.appendLine("  N-TRANSFORMED url:")
                for ((label, range) in ranges) {
                    out.appendLine("    ${label.padEnd(13)} -> ${fire(transformed, ua, range)}")
                }
                best.contentLength?.let { clen ->
                    out.appendLine("    full 0-${clen - 1}".padEnd(17) + " -> ${fire(transformed, ua, "bytes=0-${clen - 1}")}")
                }
            } else {
                out.appendLine("  (n-transform did not change the url)")
            }
            out.appendLine()
        }

        out.appendLine("Copy this and send it. Look for which row shows HTTP 200/206.")
        out.toString()
    }

    private fun urlParams(url: String): String {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return "unreadable url"
        fun has(p: String) = if (u.getQueryParameter(p) != null) "yes" else "no"
        return "n=${has("n")} pot=${has("pot")} sig=${has("sig")} " +
            "clen=${u.getQueryParameter("clen") ?: "?"} " +
            "mime=${u.getQueryParameter("mime")?.substringBefore(';') ?: "?"} " +
            "host=${u.host?.substringBefore(".googlevideo") ?: "?"}"
    }

    /**
     * Fires one request and returns a short result line. Doesn't download the body on
     * success (only looks at status/headers) so it doesn't swallow the whole file.
     */
    private suspend fun fire(url: String, userAgent: String, range: String?): String =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                range?.let { builder.header("Range", it) }
                InnerTubeContexts.streamHeaders(userAgent).forEach { (k, v) -> builder.header(k, v) }
                okHttpClient.newCall(builder.build()).execute().use { r ->
                    val ct = r.header("Content-Type")?.substringBefore(';') ?: "?"
                    val len = r.header("Content-Length") ?: "?"
                    if (r.code == 200 || r.code == 206) {
                        "HTTP ${r.code} [$ct] len=$len"
                    } else {
                        val body = runCatching { r.peekBody(200).string() }.getOrNull()
                            ?.replace('\n', ' ')?.trim()?.take(120)
                        "HTTP ${r.code} [$ct] ${body.orEmpty()}"
                    }
                }
            } catch (e: Exception) {
                "ERR ${e.javaClass.simpleName}: ${e.message?.take(80).orEmpty()}"
            }
        }
}
