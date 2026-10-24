package com.theveloper.pixelplay.data.diagnostics

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Scrubs text before it is written to the shareable diagnostics log. The log leaves the phone
 * (the owner sends it to a developer), so the rule is "when in doubt, drop it":
 *
 *  - URLs keep scheme, host and path only: query strings, fragments and `user:pass@` are removed,
 *    because signatures, tokens and OAuth codes live there.
 *  - `Bearer`/`Basic` credentials, `token=...`/`key: ...` pairs, JWTs and any long opaque
 *    letters-and-digits string become `<redacted>`.
 *  - e-mail addresses become `<email>`.
 *  - Track titles and artists are never written on purpose ([opaqueId] gives a short stable id
 *    instead). As a safety net for third-party messages that mention a song anyway, names passed
 *    to [registerSensitive] are replaced by their id wherever they appear.
 *
 * Pure Kotlin, no Android types, so it is unit tested on the JVM.
 */
object DiagnosticsRedactor {

    const val REDACTED = "<redacted>"
    const val EMAIL = "<email>"

    private val URL_REGEX = Regex("""(?i)\b(?:https?|wss?|pixelplay|content|file)://[^\s"'<>)\]}]+""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val AUTH_SCHEME_REGEX = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=\-]{6,}""")
    private val JWT_REGEX = Regex("""\beyJ[A-Za-z0-9_\-]{5,}\.[A-Za-z0-9_\-]{5,}\.?[A-Za-z0-9_\-]*""")
    private val SECRET_PAIR_REGEX = Regex(
        """(?i)\b(access[_\-]?token|refresh[_\-]?token|id[_\-]?token|token|code[_\-]?verifier|code|client[_\-]?secret|""" +
            """api[_\-]?key|apikey|key|secret|password|passwd|sig|signature|authorization|cookie|set-cookie|""" +
            """visitor[_\-]?data|po[_\-]?token|sapisid|sid|auth)(\s*[=:]\s*)("?)([^\s,;&"'}\]]+)"""
    )
    private val LONG_TOKEN_REGEX = Regex("""\b[A-Za-z0-9_\-]{32,}""")
    /** Anthropic API keys (the built-in Claude key): always dropped, whatever surrounds them. */
    private val ANTHROPIC_KEY_REGEX = Regex("""sk-ant-[A-Za-z0-9_\-]{8,}""")

    private const val MAX_SENSITIVE_NAMES = 128
    private val sensitiveNames = ConcurrentHashMap<String, String>()

    /** Cleans [text] and returns it; never throws. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return runCatching { redactUnsafe(text) }.getOrDefault(REDACTED)
    }

    private fun redactUnsafe(text: String): String {
        var out = ANTHROPIC_KEY_REGEX.replace(text, REDACTED)
        out = URL_REGEX.replace(out) { stripUrl(it.value) }
        out = AUTH_SCHEME_REGEX.replace(out) { "Bearer $REDACTED" }
        out = JWT_REGEX.replace(out, REDACTED)
        out = SECRET_PAIR_REGEX.replace(out) { m ->
            // "code=403" is an HTTP status, not a credential: short all-digit values stay.
            if (m.groupValues[4].length <= 6 && m.groupValues[4].all { it.isDigit() }) m.value
            else "${m.groupValues[1]}${m.groupValues[2]}$REDACTED"
        }
        out = LONG_TOKEN_REGEX.replace(out) { m -> if (looksLikeSecret(m.value)) REDACTED else m.value }
        out = EMAIL_REGEX.replace(out, EMAIL)
        if (sensitiveNames.isNotEmpty()) {
            for ((name, id) in sensitiveNames) {
                if (out.contains(name, ignoreCase = true)) out = out.replace(name, id, ignoreCase = true)
            }
        }
        return out
    }

    /** scheme://host/path with query, fragment and credentials removed; long path segments hidden. */
    internal fun stripUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return REDACTED
        val scheme = url.substring(0, schemeEnd)
        var rest = url.substring(schemeEnd + 3)
        val hadQuery = rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) rest = rest.substring(0, cut)
        val slash = rest.indexOf('/')
        var authority = if (slash >= 0) rest.substring(0, slash) else rest
        var path = if (slash >= 0) rest.substring(slash) else ""
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (path.isNotEmpty()) {
            path = path.split('/').joinToString("/") { seg ->
                if (seg.length >= 32 && looksLikeSecret(seg)) REDACTED else seg
            }
        }
        return buildString {
            append(scheme).append("://").append(authority).append(path)
            if (hadQuery) append("?…")
        }
    }

    /** At least 32 chars and a mix of letters and digits: a token, key or hash, not an identifier. */
    private fun looksLikeSecret(candidate: String): Boolean {
        var letters = false
        var digits = false
        for (c in candidate) {
            if (c.isLetter()) letters = true else if (c.isDigit()) digits = true
        }
        return letters && digits
    }

    /**
     * A short, stable, non-reversible-in-practice stand-in for a title, artist or other personal
     * string: `#` plus the first 6 hex digits of its SHA-256. Same input, same id, so a reader
     * can tell "the same track again" without learning what it was.
     */
    fun opaqueId(value: String?): String {
        if (value.isNullOrBlank()) return "#none"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("pixlaudio-diag:" + value.trim().lowercase()).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(6)
        for (i in 0 until 3) hex.append("%02x".format(digest[i].toInt() and 0xff))
        return "#$hex"
    }

    /**
     * Teaches the redactor a personal string (a track title or artist the app is working with) so
     * any third-party log line that happens to contain it is rewritten to its [opaqueId].
     */
    fun registerSensitive(value: String?) {
        val name = value?.trim().orEmpty()
        if (name.length < 3) return
        if (sensitiveNames.size >= MAX_SENSITIVE_NAMES && !sensitiveNames.containsKey(name)) {
            sensitiveNames.keys.firstOrNull()?.let { sensitiveNames.remove(it) }
        }
        sensitiveNames[name] = opaqueId(name)
    }

    internal fun clearSensitiveForTest() = sensitiveNames.clear()
}
