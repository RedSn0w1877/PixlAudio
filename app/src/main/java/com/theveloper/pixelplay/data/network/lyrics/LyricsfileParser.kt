package com.theveloper.pixelplay.data.network.lyrics

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Draft 1.0 Lyricsfile: absolute milliseconds and verbatim word whitespace. */
internal object LyricsfileParser {
    fun parse(raw: String?): Lyrics? {
        if (raw.isNullOrBlank() || raw.length > 512_000) return null
        return try {
            val options = LoaderOptions().apply {
                codePointLimit = 512_000
                maxAliasesForCollections = 0
                nestingDepthLimit = 20
                isAllowDuplicateKeys = false
            }
            // Lyricsfile uses YAML 1.2 text. YAML 1.1 implicit typing turns lyric words
            // such as "No"/"On" into booleans; preserve scalars and parse numeric fields explicitly.
            val dumper = DumperOptions()
            val resolver = object : Resolver() {
                override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag =
                    if (kind == NodeId.scalar) Tag.STR else super.resolve(kind, value, implicit)
            }
            val doc = Yaml(SafeConstructor(options), Representer(dumper), dumper, options, resolver)
                .load<Any>(raw) as? Map<*, *> ?: return null
            if (doc["version"].toString() != "1.0") return null
            // Draft offset semantics are unresolved; don't silently invent an interpretation.
            val offset = doc["offset_ms"] ?: (doc["metadata"] as? Map<*, *>)?.get("offset_ms")
            if (offset != null && offset.toString().toLongOrNull() != 0L) return null
            val sourceLines = doc["lines"] as? List<*> ?: return null
            if (sourceLines.size > 10_000) return null
            val lines = sourceLines.mapNotNull { value ->
                val line = value as? Map<*, *> ?: return null
                val text = line["text"] as? String ?: return null
                val time = millis(line["start_ms"]) ?: return null
                var previousSpace = true
                val words = (line["words"] as? List<*>)?.mapNotNull { wordValue ->
                    val word = wordValue as? Map<*, *> ?: return null
                    val wordText = word["text"] as? String ?: return null
                    val wordTime = millis(word["start_ms"]) ?: return null
                    val startsNew = previousSpace || wordText.firstOrNull()?.isWhitespace() == true
                    previousSpace = wordText.lastOrNull()?.isWhitespace() == true
                    if (wordText.isBlank()) null else SyncedWord(wordTime, wordText.trim(), startsNew)
                }?.takeIf { it.isNotEmpty() }
                if (words != null && words.zipWithNext().any { (a, b) -> a.time > b.time }) return null
                SyncedLine(time, text, words)
            }
            if (lines.isEmpty()) null else Lyrics(plain = lines.map { it.line }, synced = lines, areFromRemote = true)
        } catch (_: Exception) { null }
    }

    private fun millis(value: Any?): Int? {
        val d = value?.toString()?.toDoubleOrNull() ?: return null
        if (!d.isFinite() || d < 0 || d > 86_400_000 || d != d.toLong().toDouble()) return null
        return d.toInt()
    }
}
