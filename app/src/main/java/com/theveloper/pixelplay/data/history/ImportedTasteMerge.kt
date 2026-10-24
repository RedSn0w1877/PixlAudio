package com.theveloper.pixelplay.data.history

import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine

/** Adds the imported contribution to this app's own engagement for the same song. */
object ImportedTasteMerge {
    fun history(
        local: MusicRecommendationEngine.History?,
        imported: MusicRecommendationEngine.History?
    ): MusicRecommendationEngine.History = when {
        imported == null -> local ?: MusicRecommendationEngine.History()
        local == null -> imported
        else -> MusicRecommendationEngine.History(
            plays = (local.plays.toLong().coerceAtLeast(0) + imported.plays.coerceAtLeast(0))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            listenedMs = local.listenedMs.coerceAtLeast(0) + imported.listenedMs.coerceAtLeast(0),
            lastPlayedMs = maxOf(local.lastPlayedMs, imported.lastPlayedMs)
        )
    }
}
