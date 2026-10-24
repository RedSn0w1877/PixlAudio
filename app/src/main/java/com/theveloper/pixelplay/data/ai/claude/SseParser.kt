package com.theveloper.pixelplay.data.ai.claude

/**
 * Server-sent events, line by line (the lines a BufferedReader gives, without their line
 * breaks). `event:` names the event, `data:` lines are joined with "\n", a blank line dispatches,
 * lines starting with ':' are comments. Pure (SseParserTest).
 */
class SseParser {
    data class Event(val name: String?, val data: String)

    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line; returns the event it completes, if any. */
    fun feed(line: String): Event? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** The event still pending when the stream ends without a final blank line. */
    fun finish(): Event? = dispatch()

    private fun dispatch(): Event? {
        val event = if (hasData) Event(eventName, data.toString()) else null
        eventName = null
        data.setLength(0)
        hasData = false
        return event
    }
}
