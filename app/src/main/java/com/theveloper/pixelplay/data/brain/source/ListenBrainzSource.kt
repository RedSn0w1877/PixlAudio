package com.theveloper.pixelplay.data.brain.source

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ListenBrainz (docs/brain-spec.md, 3.3 B and 3.4): recording and artist genre tags with MusicBrainz genre ids, for a batch of
 * recordings in one request (the batch size is 16; larger sizes are unverified), and a popularity score as the number of listeners
 * (last resort; it scores Vietnamese pop far too low). One request per second, 150 a day.
 */
@Singleton
class ListenBrainzSource @Inject constructor(private val http: KnowledgeHttp) {
    private val host = KnowledgeHost.LISTENBRAINZ

    /** `POST /1/metadata/recording/ {"recording_mbids": [...], "inc": "artist tag release"}`. */
    suspend fun recordings(mbids: List<String>): Lookup<Map<String, LbRecording>> {
        if (mbids.isEmpty()) return Lookup.NotFound
        val body = buildJsonObject {
            put("recording_mbids", buildJsonArray { mbids.take(BATCH).forEach { add(JsonPrimitive(it)) } })
            put("inc", "artist tag release")
        }.toString()
        return SourceSupport.classify(http.post(host, "https://api.listenbrainz.org/1/metadata/recording/", body)) {
            KnowledgeParsers.lbRecordings(it).takeIf { m -> m.isNotEmpty() }
        }
    }

    /** `POST /1/popularity/recording {"recording_mbids": [...]}`. */
    suspend fun popularity(mbids: List<String>): Lookup<Map<String, LbPopularity>> {
        if (mbids.isEmpty()) return Lookup.NotFound
        val body = buildJsonObject { put("recording_mbids", buildJsonArray { mbids.take(BATCH).forEach { add(JsonPrimitive(it)) } }) }.toString()
        return SourceSupport.classify(http.post(host, "https://api.listenbrainz.org/1/popularity/recording", body)) {
            KnowledgeParsers.lbPopularity(it).takeIf { m -> m.isNotEmpty() }
        }
    }

    companion object {
        const val BATCH = 16
    }
}
