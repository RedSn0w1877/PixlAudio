package com.theveloper.pixelplay.presentation.viewmodel

/**
 * The engine's end of the queue, as shuffle has to treat it (docs/brain-spec.md, 7.1 "Ownership").
 *
 * Tracks under the Autoplay line belong to the engine. Shuffle is about the listener's own queue: it
 * mixes what they queued and leaves the engine's picks where they are, at the end. Switching shuffle
 * off puts the listener's tracks back in their order and keeps the picks that are there now, which
 * the stored original order has never heard of (Autoplay appended them later).
 */
internal object EngineTail {

    /** How many items at the very end of a queue of [size] are the engine's: an unbroken run. */
    fun count(size: Int, engineOwned: (Int) -> Boolean): Int {
        var n = 0
        var i = size - 1
        while (i >= 0 && engineOwned(i)) {
            n++
            i--
        }
        return n
    }

    /**
     * The order to go back to when shuffle is switched off: [original] without anything that is in
     * the engine's tail now, followed by that [tail] as it is.
     */
    fun <T> restoreOrder(original: List<T>, tail: List<T>, idOf: (T) -> String): List<T> {
        if (tail.isEmpty()) return original
        val tailIds = HashSet<String>(tail.size * 2)
        for (item in tail) tailIds.add(idOf(item))
        val out = ArrayList<T>(original.size + tail.size)
        for (item in original) if (idOf(item) !in tailIds) out.add(item)
        out.addAll(tail)
        return out
    }

    /**
     * Shuffles the listener's part of [queue] around the track at [currentIndex] and keeps the last
     * [tailCount] items in place. When the track that is playing is already one of the engine's,
     * nothing of the listener's is left ahead to shuffle, and the queue stays as it is.
     */
    suspend fun <T> shuffleOwnPart(
        queue: List<T>,
        currentIndex: Int,
        tailCount: Int,
        shuffle: suspend (List<T>, Int) -> List<T>,
    ): List<T> {
        if (tailCount <= 0) return shuffle(queue, currentIndex)
        val ownCount = queue.size - tailCount
        if (ownCount <= 0 || currentIndex >= ownCount) return queue
        return shuffle(queue.subList(0, ownCount), currentIndex) + queue.subList(ownCount, queue.size)
    }
}
