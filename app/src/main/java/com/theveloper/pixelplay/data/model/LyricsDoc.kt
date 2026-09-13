package com.theveloper.pixelplay.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** PixelPlay lyrics JSON v1. All times are absolute milliseconds; ends are exclusive. */
@Serializable
data class LyricsDoc(
    val format: String = "pixelplay-lyrics",
    val version: Int = 1,
    val metadata: LyricsMetadata = LyricsMetadata(),
    val voices: List<Voice> = listOf(Voice()),
    val lines: List<TimedLine>
) {
    fun toLyrics(): Lyrics = Lyrics(
        plain = lines.map { it.text },
        synced = lines.map { line ->
            var boundary = true
            SyncedLine(line.startMs.toInt(), line.text, line.syllables.takeIf { it.isNotEmpty() }?.mapNotNull { s ->
                val startsNew = boundary || s.text.firstOrNull()?.isWhitespace() == true
                boundary = s.text.lastOrNull()?.isWhitespace() == true
                if (s.text.isBlank()) null else SyncedWord(s.startMs.toInt(), s.text.trim(), startsNew,
                    (s.startMs + s.durationMs).toInt())
            }, endTime = line.endMs.toInt(), voiceRole = voices.find { it.id == line.voiceId }?.role ?: "lead")
        },
        document = this
    )
}

@Serializable
data class LyricsMetadata(val title: String = "", val artist: String = "", val album: String = "",
    val durationMs: Long? = null, val source: String? = null)

@Serializable
data class Voice(val id: String = "lead", val role: String = "lead", val name: String? = null)

@Serializable
data class TimedLine(val startMs: Long, val endMs: Long, val text: String,
    val voiceId: String = "lead", val syllables: List<TimedSyllable> = emptyList())

@Serializable
data class TimedSyllable(val startMs: Long, val durationMs: Long, val text: String)

fun TimedLine.currentSyllable(playbackMs: Long): TimedSyllable? = syllables.lastOrNull {
    playbackMs >= it.startMs && playbackMs < it.startMs + it.durationMs
}

fun LyricsDoc.activeLines(playbackMs: Long): List<TimedLine> = lines.filter {
    playbackMs >= it.startMs && playbackMs < it.endMs
}

/** Prefer the lead for scrolling while allowing background/duet lines to remain active. */
fun LyricsDoc.findActiveLine(playbackMs: Long): TimedLine? = activeLines(playbackMs).let { active ->
    active.lastOrNull { line -> voices.find { it.id == line.voiceId }?.role == "lead" } ?: active.lastOrNull()
}

object LyricsDocCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(doc: LyricsDoc): String = json.encodeToString(doc)
    fun decode(raw: String): LyricsDoc? = try {
        if (!isBoundedJson(raw)) null else json.decodeFromString<LyricsDoc>(raw).takeIf(::isValid)
    } catch (_: Exception) { null }

    fun isValid(doc: LyricsDoc): Boolean {
        if (doc.format != "pixelplay-lyrics" || doc.version != 1 || doc.lines.isEmpty() || doc.lines.size > 10_000) return false
        if (doc.voices.isEmpty() || doc.voices.size > 32 || doc.voices.map { it.id }.distinct().size != doc.voices.size) return false
        if (doc.voices.any { it.id.isBlank() || it.role !in setOf("lead", "background", "duet") }) return false
        val duration = doc.metadata.durationMs
        if (duration != null && duration !in 1..86_400_000L) return false
        if (doc.lines.zipWithNext().any { (a,b) -> a.startMs > b.startMs }) return false
        if (doc.lines.sumOf { it.syllables.size.toLong() } > 100_000) return false
        return doc.lines.all { line ->
            line.startMs >= 0 && line.endMs > line.startMs && line.endMs <= (duration ?: 86_400_000L) &&
                doc.voices.any { it.id == line.voiceId } &&
                line.syllables.zipWithNext().none { (a,b) -> a.startMs > b.startMs } &&
                line.syllables.all { s -> s.startMs >= line.startMs && s.startMs < line.endMs &&
                    s.durationMs > 0 && s.durationMs <= line.endMs - s.startMs } &&
                (line.syllables.isEmpty() || line.syllables.joinToString("") { it.text } == line.text)
        }
    }

    internal fun isBoundedJson(raw: String): Boolean {
        if (raw.length > 1_048_576) return false
        var depth = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
}
