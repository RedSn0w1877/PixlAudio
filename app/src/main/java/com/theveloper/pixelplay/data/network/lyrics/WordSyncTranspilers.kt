package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.model.*
import kotlin.math.roundToLong

/** Format conversion only: provider authentication and catalog matching live in data sources. */
object WordSyncTranspilers {
    fun richSync(raw: String, metadata: LyricsMetadata = LyricsMetadata(source = "Musixmatch")): LyricsDoc? = try {
        if (!LyricsDocCodec.isBoundedJson(raw)) null else {
            val array = JsonParser.parseString(raw).asJsonArray
            if (array.size() > 10_000) null else {
                val lines = array.map { element ->
                    val line = element.asJsonObject
                    val start = seconds(line.get("ts").asDouble)
                    val end = seconds(line.get("te").asDouble)
                    val text = line.get("x").asString
                    val fragments = line.getAsJsonArray("l")?.map { it.asJsonObject }.orEmpty()
                    val starts = fragments.map { start + seconds(it.get("o").asDouble) }
                    val syllables = fragments.mapIndexed { i, fragment ->
                        TimedSyllable(starts[i], (starts.getOrNull(i + 1) ?: end) - starts[i], fragment.get("c").asString)
                    }
                    TimedLine(start, end, text, syllables = syllables)
                }
                LyricsDoc(metadata = metadata, lines = lines).takeIf(LyricsDocCodec::isValid)
            }
        }
    } catch (_: Exception) { null }

    private val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val wordPattern = Regex("\\((\\d+),(\\d+),0\\)")

    fun yrc(raw: String, metadata: LyricsMetadata = LyricsMetadata(source = "NetEase")): LyricsDoc? = try {
        if (raw.length > 1_048_576) null else {
            val lines = raw.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("{") }.toList().map { rawLine ->
                val match = linePattern.matchEntire(rawLine.trimEnd('\r')) ?: return null
                val start = match.groupValues[1].toLong()
                val duration = match.groupValues[2].toLong()
                if (start !in 0..86_400_000L || duration !in 1..86_400_000L) return null
                val body = match.groupValues[3]
                val tags = wordPattern.findAll(body).toList()
                if (tags.isNotEmpty() && tags.first().range.first != 0) return null
                val fragments = tags.mapIndexed { i, tag ->
                    val text = body.substring(tag.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: body.length)
                    TimedSyllable(tag.groupValues[1].toLong(), tag.groupValues[2].toLong(), text)
                }
                // YRC uses zero-duration punctuation/censor markers alongside a real
                // fragment at the same onset. Join those markers without inventing times
                // or dropping the entire recording. Positive fragment timing is retained.
                val syllables = mutableListOf<TimedSyllable>()
                var index = 0
                while (index < fragments.size) {
                    val first = fragments[index]
                    var end = index + 1
                    while (end < fragments.size && fragments[end].startMs == first.startMs) end++
                    val group = fragments.subList(index, end)
                    if (group.any { it.durationMs == 0L }) {
                        if (group.any { it.durationMs == 0L && it.text.any(Char::isLetterOrDigit) }) return null
                        val timed = group.singleOrNull { it.durationMs > 0 } ?: return null
                        syllables += timed.copy(text = group.joinToString("") { it.text })
                    } else syllables += group
                    index = end
                }
                // A malformed timing marker must not silently become display text.
                if (body.contains(Regex("\\(<?-?\\d+,")) && tags.isEmpty()) return null
                TimedLine(start, start + duration, if (syllables.isEmpty()) body else syllables.joinToString("") { it.text },
                    syllables = syllables)
            }.toList()
            LyricsDoc(metadata = metadata, lines = lines).takeIf(LyricsDocCodec::isValid)
        }
    } catch (_: Exception) { null }

    private fun seconds(value: Double): Long {
        require(value.isFinite() && value >= 0 && value <= 86_400)
        return (value * 1000).roundToLong()
    }
}
