package com.theveloper.pixelplay.data.brain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The songs whose note was shown or spoken in Endless Listening, newest last. The note window is made again at every track, so without a memory
 * the current track would always be free for a note and one would land on almost every track; [com.theveloper.pixelplay.brain.HostNotes.tracksSinceNote]
 * reads this to keep "at most one note per five tracks" true across index changes and rebuilt windows. One memory for the card, the queue rows and
 * the voice, so what is shown and what is said agree. Holds a few ids only; safe from any thread.
 */
@Singleton
class HostNoteMemory @Inject constructor() {
    private val noted = LinkedHashSet<String>()

    /** The song [id] had its note shown or spoken. Idempotent. */
    fun mark(id: String) {
        synchronized(noted) {
            noted.remove(id)
            noted.add(id)
            while (noted.size > CAPACITY) noted.remove(noted.first())
        }
    }

    /** True when [id] had its note shown or spoken. */
    fun has(id: String): Boolean = synchronized(noted) { noted.contains(id) }

    /** A copy of the ids, for the pure rule. */
    fun snapshot(): Set<String> = synchronized(noted) { HashSet(noted) }

    private companion object {
        // Only the four tracks before the current one matter; a few more cover going back and forth.
        const val CAPACITY = 24
    }
}
