package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.brain.HostNotes
import com.theveloper.pixelplay.brain.SetNotes
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Set notes (docs/brain-spec.md, 9.3 "What the listener gets"): one line about what is coming, in the queue and under the player title:
 * "Three from your V-pop side, then something new from HIEUTHUHAI." The words are a template over the queue ([SetNotes]); there is no model
 * and no network, and a line is true of the queue it was made from: it is made again whenever the queue or the current song changes, and
 * a queue it cannot say anything true about has none.
 */
object SetNoteLines {

    /** At most this many upcoming tracks are looked at: a note describes the next few, and a long queue is not walked. */
    const val LOOKAHEAD = 40

    /**
     * The notes of the queue from the current song on, by the position in the queue of the row each sits on (a song can be in a queue twice,
     * and a note belongs to one row); [current] is the note of the group the current song starts.
     */
    class Result(val byIndex: Map<Int, String>, val current: String?) {
        companion object {
            val NONE = Result(emptyMap(), null)
        }
    }

    /**
     * One upcoming song as a note sees it. [heard] is the ids of the songs with a recorded play (see `SetNoteHistory`): only those are "your side",
     * and only an engine track with no recorded play is "something new". A library song with no play is neither, so no note claims it.
     */
    fun slotOf(song: Song, heard: Set<String>): SetNotes.Slot {
        val played = song.id in heard
        val fresh = !played && EngineTrack.isEngineOnly(song)
        return SetNotes.Slot(
            artist = if (played || fresh) BrainFeedback.artistOf(song) else "",
            genre = SetNotes.firstGenre(song.genre),
            known = played,
        )
    }

    /**
     * What the host of Endless Listening knows about the window's songs, all of it real: the name of the taste island a library song sits in,
     * and the reason key of an engine pick (`WhyLine` name). [genreFact] is the atlas' description of a genre ("" when the atlas does not describe it).
     */
    class HostFacts(
        val islandOf: Map<String, String>,
        val whyKeyOf: Map<String, String>,
        val genreFact: (String) -> String,
    )

    /**
     * The notes of [queue] from [currentIndex]. [heard] null means the history is not loaded yet: no note is made (a note is true or absent).
     * With [host] (Endless Listening, the host's notes on) the host-only notes of [HostNotes] join the set notes: never on a track that already
     * has one, and at most one note of either kind per five tracks.
     */
    fun forQueue(queue: List<Song>, currentIndex: Int, heard: Set<String>?, host: HostFacts? = null): Result {
        if (heard == null || currentIndex < 0 || currentIndex >= queue.size) return Result.NONE
        val end = minOf(queue.size, currentIndex + LOOKAHEAD)
        val slots = ArrayList<SetNotes.Slot>(end - currentIndex)
        for (i in currentIndex until end) slots.add(slotOf(queue[i], heard))
        val notes = SetNotes.notes(slots)
        val hostNotes = if (host == null) emptyList() else HostNotes.notes(hostSlots(queue, currentIndex, end, host), taken(notes))
        if (notes.isEmpty() && hostNotes.isEmpty()) return Result.NONE
        val byIndex = HashMap<Int, String>((notes.size + hostNotes.size) * 2)
        var current: String? = null
        for (n in notes) {
            val text = SetNotes.text(n)
            if (n.index == 0) current = text
            byIndex[currentIndex + n.index] = text
        }
        for (n in hostNotes) {
            val text = HostNotes.text(n)
            if (n.index == 0) current = text
            byIndex[currentIndex + n.index] = text
        }
        return Result(byIndex, current)
    }

    /** The set notes as the tracks they describe: a note's own tracks, and the new track it ends on. */
    fun taken(notes: List<SetNotes.Note>): List<HostNotes.Taken> =
        notes.map { HostNotes.Taken(it.index, it.count + if (it.newArtist.isNotEmpty()) 1 else 0) }

    private fun hostSlots(queue: List<Song>, from: Int, end: Int, host: HostFacts): List<HostNotes.Slot> {
        val out = ArrayList<HostNotes.Slot>(end - from)
        for (i in from until end) {
            val song = queue[i]
            val genre = SetNotes.firstGenre(song.genre)
            val why = host.whyKeyOf[song.id]
            out.add(
                HostNotes.Slot(
                    artist = BrainFeedback.artistOf(song),
                    genre = genre,
                    genreFact = if (genre.isEmpty()) "" else host.genreFact(genre),
                    year = song.year,
                    island = host.islandOf[song.id].orEmpty(),
                    // "New from an artist you play": the engine's own reason for the pick (the saved releases hold only artists the listener plays).
                    newFromPlayedArtist = why == "new_release",
                    sideways = why == "adjacent_genre",
                )
            )
        }
        return out
    }

    /** The songs a note looks at (the current one and the next few), so the history is read for those only. */
    fun window(queue: List<Song>, currentIndex: Int): List<Song> =
        if (currentIndex < 0 || currentIndex >= queue.size) emptyList() else queue.subList(currentIndex, minOf(queue.size, currentIndex + LOOKAHEAD))
}

/**
 * The host's facts for [queue] while Endless Listening is the queue's station and its host notes are on; null otherwise (then only the set notes
 * show). Read off the main thread for the few songs a note looks at, and only when the queue or the current song changes.
 */
@Composable
fun rememberHostFacts(playerViewModel: PlayerViewModel, queue: List<Song>, index: Int): SetNoteLines.HostFacts? {
    val endless = playerViewModel.brainSession.endless
    val active by endless.active.collectAsStateWithLifecycle()
    val enabled by endless.hostNotesEnabled.collectAsStateWithLifecycle()
    val facts by produceState<SetNoteLines.HostFacts?>(null, queue, index, active, enabled) {
        value = if (active && enabled) endless.hostFacts(SetNoteLines.window(queue, index), playerViewModel.brainSession.queueWhy.value) else null
    }
    return facts
}

/** The note under the player's title, or nothing. Reads the queue and the current index itself, so only this line recomposes. */
@Composable
fun SetNoteLine(playerViewModel: PlayerViewModel, color: Color, modifier: Modifier = Modifier) {
    val queue by remember(playerViewModel) { playerViewModel.queueFlow }.collectAsStateWithLifecycle()
    val index by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentMediaItemIndex }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = playerViewModel.stablePlayerState.value.currentMediaItemIndex)
    val heard by produceState<Set<String>?>(null, queue, index) {
        value = playerViewModel.brainSession.setNoteHistory.heardSongIds(SetNoteLines.window(queue, index))
    }
    val host = rememberHostFacts(playerViewModel, queue, index)
    val note = remember(queue, index, heard, host) { SetNoteLines.forQueue(queue, index, heard, host).current }
    val endlessActive = rememberEndlessActive(playerViewModel)
    if (endlessActive) {
        // Endless Listening: the note is a small card with "Ask the host" beside it; with no note the button stands alone.
        val open = remember(playerViewModel) { { playerViewModel.brainSession.endless.openHostSheet() } }
        if (note != null) {
            HostNoteCard(
                text = note,
                onAskHost = open,
                containerColor = color.copy(alpha = 0.14f),
                contentColor = color,
                modifier = modifier.padding(top = 6.dp),
            )
        } else {
            AskHostChip(onClick = open, modifier = modifier.padding(top = 6.dp))
        }
    } else if (note != null) {
        Text(
            text = note,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

/** The note above one queue row. */
@Composable
fun SetNoteRowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
