package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    /** The notes of the queue from the current song on, by the id of the song each sits on; [current] is the note of the group the current song starts. */
    class Result(val bySongId: Map<String, String>, val current: String?) {
        companion object {
            val NONE = Result(emptyMap(), null)
        }
    }

    fun slotOf(song: Song): SetNotes.Slot = SetNotes.Slot(
        artist = BrainFeedback.artistOf(song),
        genre = SetNotes.firstGenre(song.genre),
        // A song the listener has: a library row. One the engine found outside the library is the "something new".
        known = !EngineTrack.isEngineOnly(song),
    )

    fun forQueue(queue: List<Song>, currentIndex: Int): Result {
        if (currentIndex < 0 || currentIndex >= queue.size) return Result.NONE
        val end = minOf(queue.size, currentIndex + LOOKAHEAD)
        val slots = ArrayList<SetNotes.Slot>(end - currentIndex)
        for (i in currentIndex until end) slots.add(slotOf(queue[i]))
        val notes = SetNotes.notes(slots)
        if (notes.isEmpty()) return Result.NONE
        val byId = HashMap<String, String>(notes.size * 2)
        var current: String? = null
        for (n in notes) {
            val text = SetNotes.text(n)
            if (n.index == 0) current = text
            byId[queue[currentIndex + n.index].id] = text
        }
        return Result(byId, current)
    }
}

/** The note under the player's title, or nothing. Reads the queue and the current index itself, so only this line recomposes. */
@Composable
fun SetNoteLine(playerViewModel: PlayerViewModel, color: Color, modifier: Modifier = Modifier) {
    val queue by remember(playerViewModel) { playerViewModel.queueFlow }.collectAsStateWithLifecycle()
    val index by remember(playerViewModel) {
        playerViewModel.stablePlayerState.map { it.currentMediaItemIndex }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = playerViewModel.stablePlayerState.value.currentMediaItemIndex)
    val note = remember(queue, index) { SetNoteLines.forQueue(queue, index).current }
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
