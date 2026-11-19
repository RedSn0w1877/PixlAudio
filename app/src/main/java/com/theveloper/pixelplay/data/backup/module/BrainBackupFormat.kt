package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The payload of the brain's backup module (docs/brain-spec.md, 12.2, 13.2): what cannot be worked out again and is the listener's own: the
 * random salt that makes their lists differ from other installs', the taste changes they made, and the plays and groups of plays they asked
 * the brain not to count. Everything else the brain holds (affinities, islands, embeddings, the learned weights, the saved plan) is a cache
 * that a replay of the event log makes again, so it is not in the backup. The event log itself is the listener's history and has its own
 * modules (playback history, imported history).
 *
 * Pure text in and out, so a unit test can hold the format without a database.
 */
object BrainBackupFormat {
    const val FORMAT = "brain-backup-v1"

    /** One row of the `feedback` table: an exclusion ("Don't count this") or another recorded choice. */
    class FeedbackRow(
        val id: Long,
        val kind: Int,
        val targetKind: Int,
        val targetId: Long,
        val createdAtMs: Long,
        val undoneAtMs: Long?,
    )

    class Parsed(
        /** The salt as decimal text; null when the install had none yet. */
        val salt: String?,
        /** The taste changes as the app stores them (`TasteAdjustmentCodec` text). */
        val adjustments: String,
        val feedback: List<FeedbackRow>,
    )

    /** Most rows a backup may hold: a payload with more is refused, so a damaged file cannot fill the database. */
    const val MAX_FEEDBACK = 20_000

    fun encode(gson: Gson, salt: Long?, adjustments: String, feedback: List<FeedbackRow>): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        if (salt != null) root.addProperty("salt", salt.toString())
        root.addProperty("adjustments", adjustments)
        root.add("feedback", gson.toJsonTree(feedback))
        return gson.toJson(root)
    }

    /** The parsed payload, or null for anything that is not a well-formed brain backup. Never throws. */
    fun decode(gson: Gson, payload: String): Parsed? = try {
        val root = JsonParser.parseString(payload).asJsonObject
        if (root.get("format")?.asString != FORMAT) null else {
            val salt = root.get("salt")?.takeUnless { it.isJsonNull }?.asString
            if (salt != null && salt.toLongOrNull() == null) null else {
                val adjustments = root.get("adjustments")?.asString.orEmpty()
                val array = root.getAsJsonArray("feedback")
                if (array.size() > MAX_FEEDBACK) null else {
                    val rows = ArrayList<FeedbackRow>(array.size())
                    var bad = false
                    for (e in array) {
                        val o = e.asJsonObject
                        val row = FeedbackRow(
                            id = o.get("id").asLong, kind = o.get("kind").asInt, targetKind = o.get("targetKind").asInt,
                            targetId = o.get("targetId").asLong, createdAtMs = o.get("createdAtMs").asLong,
                            undoneAtMs = o.get("undoneAtMs")?.takeUnless { it.isJsonNull }?.asLong,
                        )
                        if (row.createdAtMs < 0L) bad = true
                        rows.add(row)
                    }
                    if (bad) null else Parsed(salt, adjustments, rows)
                }
            }
        }
    } catch (e: RuntimeException) {
        null
    }
}
