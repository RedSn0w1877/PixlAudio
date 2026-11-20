package com.theveloper.pixelplay.data.tais.dj

import java.util.Locale

/**
 * "Start a station from Radiohead" with no network (docs/brain-spec.md, 14 stage 4: "with no network the on-device model still starts a station
 * from a sentence"). A station request is a small fixed shape, so it is read by rules, with no model and no connection: the name it asks for is
 * then looked up in the listener's own library and the genre atlas, which are on the phone. The phone's model is not needed to read this, and
 * is never asked to name a track: the engine fills the station.
 *
 * Returns the name asked for, or null for a sentence that is not a station request (those go the old way).
 */
object StationSentence {
    private val VERBS = listOf("start", "play", "put on", "begin", "make", "build", "give me", "open", "launch", "turn on")
    private val FILLERS = setOf("me", "a", "an", "the", "some", "my", "new", "little", "quick", "radio", "station")
    private val LINKS = listOf("from", "of", "for", "based on", "built from", "built on", "around", "on", "like", "with", "using")
    private const val MAX_NAME = 60

    fun parse(prompt: String): String? {
        var text = prompt.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null
        text = text.trimEnd('.', '!', '?', ' ')
        if (text.endsWith(" please")) text = text.removeSuffix(" please").trimEnd()
        if (text.startsWith("please ")) text = text.removePrefix("please ").trimStart()
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        // "<name> radio" / "play <name> station": the request ends with the word.
        val last = words.last()
        if ((last == "radio" || last == "station") && words.size >= 3 && VERBS.any { text.startsWith("$it ") }) {
            val verb = VERBS.first { text.startsWith("$it ") }
            val middle = text.removePrefix("$verb ").removeSuffix(" $last").trim()
            val name = cleanName(middle)
            if (name != null && !middle.contains(" station ") && !middle.contains(" radio ")) return name
        }

        // "[verb] [a] [..] station|radio <link> <name>": find the first station/radio word, then a link word, then the name.
        val at = words.indexOfFirst { it == "station" || it == "radio" }
        if (at < 0) return null
        // What stands before the word must be a verb phrase and fillers only (or nothing).
        val before = words.subList(0, at).joinToString(" ")
        if (before.isNotEmpty()) {
            val verb = VERBS.firstOrNull { before == it || before.startsWith("$it ") } ?: return null
            val rest = before.removePrefix(verb).trim()
            if (rest.isNotEmpty() && rest.split(' ').any { it !in FILLERS }) return null
        }
        val after = words.subList(at + 1, words.size).joinToString(" ")
        val link = LINKS.firstOrNull { after.startsWith("$it ") } ?: return null
        return cleanName(after.removePrefix(link).trim())
    }

    /** The name without quotes or a leading article, or null when nothing useful is left. */
    private fun cleanName(raw: String): String? {
        var t = raw.trim().trim('"', '\'', '\u201C', '\u201D').trim()
        for (article in listOf("the ", "some ", "my ")) if (t.startsWith(article) && t.length > article.length + 1) { t = t.removePrefix(article); break }
        if (t.length < 2 || t.length > MAX_NAME) return null
        if (t in FILLERS) return null
        if (t in setOf("this", "that", "it", "this song", "this artist", "that song")) return null
        return t
    }
}
