package com.theveloper.pixelplay.data.brain.source

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wikidata (docs/brain-spec.md, 3.3 B): the genres of artists known by MusicBrainz id, one SPARQL query per 100 artists
 * (15 of 16 test artists were on Wikidata, 14 with genres). One query per 6 s and 3 a day: the host answered 429 at 2 s spacing.
 * CC0.
 */
@Singleton
class WikidataSource @Inject constructor(private val http: KnowledgeHttp) {
    private val host = KnowledgeHost.WIKIDATA

    suspend fun artists(mbids: List<String>): Lookup<List<WdArtist>> {
        val ids = mbids.filter { it.length == 36 && it.all { c -> c == '-' || c in '0'..'9' || c in 'a'..'f' } }.distinct().take(BATCH)
        if (ids.isEmpty()) return Lookup.NotFound
        val values = ids.joinToString(" ") { "\"$it\"" }
        val query = "SELECT ?mbid ?a (GROUP_CONCAT(DISTINCT ?gm; separator=\"|\") AS ?gmbids) (GROUP_CONCAT(DISTINCT ?gl; separator=\"|\") AS ?glabels) " +
            "WHERE { VALUES ?mbid { $values } ?a wdt:P434 ?mbid . OPTIONAL { ?a wdt:P136 ?g . ?g wdt:P8052 ?gm . ?g rdfs:label ?gl FILTER(LANG(?gl) = \"en\") } } " +
            "GROUP BY ?mbid ?a"
        val reply = http.post(
            host, "https://query.wikidata.org/sparql", "query=" + SourceSupport.enc(query),
            contentType = "application/x-www-form-urlencoded", accept = "application/sparql-results+json",
        )
        if (reply != null && reply.code == 429) http.backOff(host, maxOf(reply.retryAfterSec, 60) * 1000L)
        return SourceSupport.classify(reply) { KnowledgeParsers.wikidataArtists(it).takeIf { l -> l.isNotEmpty() } }
    }

    companion object {
        const val BATCH = 100
    }
}
