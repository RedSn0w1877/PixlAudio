package com.theveloper.pixelplay.data.brain.source

import java.net.URLEncoder

/** What every source client shares: how a reply becomes a [Lookup], and how a term goes into a URL. */
internal object SourceSupport {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** HTTP status to a [Lookup]: not asked or a server error is [Lookup.Unavailable]; "no such thing" is [Lookup.NotFound]. */
    fun <T> classify(reply: KnowledgeHttp.Reply?, parse: (String?) -> T?): Lookup<T> {
        if (reply == null) return Lookup.Unavailable
        if (reply.code == 404) return Lookup.NotFound
        if (!reply.ok) return Lookup.Unavailable
        val value = parse(reply.body) ?: return Lookup.NotFound
        return Lookup.Found(value)
    }
}
