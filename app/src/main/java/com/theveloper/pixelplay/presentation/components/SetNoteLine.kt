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

    /** The notes of [queue] from [currentIndex]. [heard] null means the history is not loaded yet: no note is made (a note is true or absent). */
    fun forQueue(queue: List<Song>, currentIndex: Int, heard: Set<String>?): Result {
        if (heard == null || currentIndex < 0 || currentIndex >= queue.size) return Result.NONE
        val end = minOf(queue.size, currentIndex + LOOKAHEAD)
        val slots = ArrayList<SetNotes.Slot>(end - currentIndex)
        for (i in currentIndex until end) slots.add(slotOf(queue[i], heard))
        val notes = SetNotes.notes(slots)
        if (notes.isEmpty()) return Result.NONE
        val byIndex = HashMap<Int, String>(notes.size * 2)
        var current: String? = null
        for (n in notes) {
            val text = SetNotes.text(n)
            if (n.index == 0) current = text
            byIndex[currentIndex + n.index] = text
        }
        return Result(byIndex, current)
    }

    /** The songs a note looks at (the current one and the next few), so the history is read for those only. */
    fun window(queue: List<Song>, currentIndex: Int): List<Song> =
        if (currentIndex < 0 || currentIndex >= queue.size) emptyList() else queue.subList(currentIndex, minOf(queue.size, currentIndex + LOOKAHEAD))
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
    val note = remember(queue, index, heard) { SetNoteLines.forQueue(queue, index, heard).current }
    if (note != null) {
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
