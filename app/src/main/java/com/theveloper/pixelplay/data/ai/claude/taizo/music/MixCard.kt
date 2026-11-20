package com.theveloper.pixelplay.data.ai.claude.taizo.music

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.brain.AssistantCards
import com.theveloper.pixelplay.brain.AssistantRules

/** One chip under a mix card: the delta it applies to the stored request and the words it wears. */
@Immutable
class MixCardChip(val chip: AssistantRules.Chip, val label: String)

/** One track shown on a mix card. */
@Immutable
class MixCardTrack(val title: String, val artist: String)

/**
 * What the chat shows for a mix `build_mix` made (docs/brain-spec.md, 9.3): "Late drive · 30 songs", Play, Save, and the chips under it.
 * A plain value built from the engine's own answer; the chips are deltas on [BuiltMix.query] applied on the device, with no model call.
 */
@Immutable
class MixCard(
    val mixId: String,
    val title: String,
    val total: Int,
    val chips: List<MixCardChip>,
    val preview: List<MixCardTrack>,
    /** Filters the request asked for that the data could not answer ("tempo"), so the card can say they were not applied. */
    val notApplied: List<String>,
) {
    companion object {
        const val PREVIEW_TRACKS = 4

        fun of(mix: BuiltMix): MixCard {
            val chips = AssistantCards.allChips(mix.query, mix.topArtistRef, mix.topGenreId, mix.unsupportedFilters).mapNotNull { chip ->
                val label = AssistantCards.chipLabel(chip, mix.topArtistName, mix.topGenreName)
                if (label.isEmpty()) null else MixCardChip(chip, label)
            }
            return MixCard(
                mixId = mix.mixId,
                title = mix.title,
                total = mix.tracks.size,
                chips = chips,
                preview = mix.tracks.take(PREVIEW_TRACKS).map { MixCardTrack(it.track.title, it.track.artist) },
                notApplied = mix.unsupportedFilters,
            )
        }
    }
}
