package com.theveloper.pixelplay.brain

/**
 * The Home page rules (docs/brain-spec.md, 8.1, stage 2), in pure logic: which shelves the page shows, in
 * what order, with which items. The shelves arrive already built (each from its own arm and surface) in the
 * fixed order of the table in 8.1; this decides what the page can hold.
 *
 * Page rules:
 *  - a track appears once on the page;
 *  - an artist at most twice per shelf and four times per page;
 *  - two all-novel shelves are never adjacent;
 *  - at least one familiar shelf sits in the first three shelf slots (slots 4 to 6 of the page: the hero,
 *    the ask pill and Jump back in come first);
 *  - a shelf with too few playable items is hidden, not padded;
 *  - at most seven shelves.
 *
 * The order of the shelves is fixed in stage 2; the bandit that would reorder them is stage 3.
 */
object HomePlanner {
    // Shelf types, in the fixed order of the table in 8.1.
    const val MADE_FOR_YOU = 0
    const val BECAUSE_LISTENED = 1
    const val NEW_FROM_LOVED = 2
    const val ON_REPEAT = 3
    const val REDISCOVER = 4
    const val STATIONS = 5
    const val STEP_SIDEWAYS = 6
    const val MORE_LIKE_ALBUM = 7
    const val POPULAR = 8

    /** Stage 3: the daypart mix shelf (6.2, 8.1). Not in stage 2's table; the builder puts it right after "Because you listened to". */
    const val DAYPART_MIX = 9

    const val MAX_SHELVES = 7
    const val ITEMS_PER_SHELF = 12

    /** A shelf of tracks needs this many playable items to be shown (8.1). */
    const val MIN_TRACKS = 6

    /** A shelf of cards (mixes, stations) needs this many cards: each card is itself a list of playable music. */
    const val MIN_MIX_CARDS = 1
    const val MIN_STATION_CARDS = 4

    const val ARTIST_PER_SHELF = 2
    const val ARTIST_PER_PAGE = 4

    /** Hero kinds. */
    const val HERO_NONE = 0
    const val HERO_FIRST_LISTEN = 1
    const val HERO_RELEASE = 2
    const val HERO_DAILY = 3

    fun isCardShelf(type: Int): Boolean = type == MADE_FOR_YOU || type == STATIONS

    /** The shelves whose music is all new to the listener (novel share 1 in 8.1). */
    fun isAllNovel(type: Int): Boolean = type == NEW_FROM_LOVED || type == STEP_SIDEWAYS || type == POPULAR

    fun minItems(type: Int): Int = when (type) {
        MADE_FOR_YOU -> MIN_MIX_CARDS
        STATIONS -> MIN_STATION_CARDS
        else -> MIN_TRACKS
    }

    /** One card or track of a shelf. [key] is whatever the caller needs to find the item again (an index into its own table). */
    class Item(val rid: Long, val aid: Long, val key: Int)

    class ShelfIn(
        val type: Int,
        /** Tells two shelves of one type apart ("Because you listened to X" has up to two); free text for the caller. */
        val id: String,
        val items: List<Item>,
    )

    class Shelf(val type: Int, val id: String, val items: List<Item>)

    /** Applies the page rules to [shelves] (given in the fixed order) and returns the shelves the page shows. */
    fun plan(shelves: List<ShelfIn>): List<Shelf> {
        // 1. Viable shelves by their raw count; the order is fixed.
        var order = ArrayList<ShelfIn>(shelves.size)
        for (s in shelves) if (s.items.size >= minItems(s.type)) order.add(s)
        // 2. Order rules, then the item rules in that order; a shelf that ends too small leaves, and the order rules run again.
        var result: List<Shelf> = emptyList()
        var rounds = 0
        while (rounds <= shelves.size + 1) {
            order = arrangeOrder(order)
            val shown = ArrayList<Shelf>(order.size)
            val pageRids = HashSet<Long>()
            val pageArtists = HashMap<Long, Int>()
            val dropped = ArrayList<ShelfIn>()
            for (s in order) {
                if (shown.size >= MAX_SHELVES) {
                    dropped.add(s)
                    continue
                }
                val items = filterItems(s, pageRids, pageArtists)
                if (items.size < minItems(s.type)) {
                    dropped.add(s)
                    continue
                }
                if (!isCardShelf(s.type)) {
                    for (item in items) {
                        pageRids.add(item.rid)
                        if (item.aid != 0L) pageArtists[item.aid] = (pageArtists[item.aid] ?: 0) + 1
                    }
                }
                shown.add(Shelf(s.type, s.id, items))
            }
            result = shown
            if (dropped.isEmpty()) break
            val keep = HashSet<String>()
            for (s in shown) keep.add(key(s.type, s.id))
            val next = ArrayList<ShelfIn>(order.size)
            for (s in order) if (keep.contains(key(s.type, s.id))) next.add(s)
            order = next
            rounds++
        }
        return result
    }

    /**
     * Puts [shelves] (in the fixed order of stage 2) in the order of [typeOrder], the shelf types the shelf bandit drew (5.10): by the place of
     * each shelf's type in [typeOrder], shelves of one type keeping their own order; a type [typeOrder] does not hold goes after the others, in
     * its fixed order. The page rules of [plan] then apply to this order as before.
     */
    fun reorder(shelves: List<ShelfIn>, typeOrder: IntArray): List<ShelfIn> {
        fun place(type: Int): Int {
            for (i in typeOrder.indices) if (typeOrder[i] == type) return i
            return typeOrder.size
        }
        val idx = shelves.indices.sortedWith { a, b ->
            val pa = place(shelves[a].type)
            val pb = place(shelves[b].type)
            if (pa != pb) pa.compareTo(pb) else a.compareTo(b)
        }
        return idx.map { shelves[it] }
    }

    private fun key(type: Int, id: String): String = "$type:$id"

    private fun filterItems(shelf: ShelfIn, pageRids: Set<Long>, pageArtists: Map<Long, Int>): List<Item> {
        if (isCardShelf(shelf.type)) {
            val n = if (shelf.items.size < ITEMS_PER_SHELF) shelf.items.size else ITEMS_PER_SHELF
            return shelf.items.subList(0, n)
        }
        val out = ArrayList<Item>(ITEMS_PER_SHELF)
        val shelfArtists = HashMap<Long, Int>()
        val seen = HashSet<Long>()
        for (item in shelf.items) {
            if (out.size >= ITEMS_PER_SHELF) break
            if (item.rid == 0L || item.rid in pageRids || !seen.add(item.rid)) continue
            if (item.aid != 0L) {
                if ((shelfArtists[item.aid] ?: 0) >= ARTIST_PER_SHELF) continue
                if ((pageArtists[item.aid] ?: 0) + (shelfArtists[item.aid] ?: 0) >= ARTIST_PER_PAGE) continue
                shelfArtists[item.aid] = (shelfArtists[item.aid] ?: 0) + 1
            }
            out.add(item)
        }
        return out
    }

    /**
     * The order rules: no two all-novel shelves side by side (the next familiar shelf is pulled forward, or the
     * all-novel shelf leaves when none is left) and a familiar shelf among the first three.
     */
    private fun arrangeOrder(input: List<ShelfIn>): ArrayList<ShelfIn> {
        val order = ArrayList(input)
        // A familiar shelf in the first three slots: pull the first one forward when they are all new.
        val firstThree = if (order.size < 3) order.size else 3
        var anyFamiliarFirst = false
        for (i in 0 until firstThree) if (!isAllNovel(order[i].type)) anyFamiliarFirst = true
        if (!anyFamiliarFirst) {
            for (j in firstThree until order.size) {
                if (!isAllNovel(order[j].type)) {
                    val s = order.removeAt(j)
                    order.add(0, s)
                    break
                }
            }
        }
        var i = 1
        while (i < order.size) {
            if (isAllNovel(order[i].type) && isAllNovel(order[i - 1].type)) {
                var j = i + 1
                while (j < order.size && isAllNovel(order[j].type)) j++
                if (j < order.size) {
                    val s = order.removeAt(j)
                    order.add(i, s)
                } else {
                    order.removeAt(i)
                    continue
                }
            }
            i++
        }
        return order
    }

    // ---- the hero, the date line ---------------------------------------------------------------

    const val DAYPART_MORNING = 0
    const val DAYPART_AFTERNOON = 1
    const val DAYPART_EVENING = 2
    const val DAYPART_NIGHT = 3

    /** Morning 05-12, afternoon 12-17, evening 17-22, night otherwise, by the listener's local hour. */
    fun daypart(nowMs: Long, tzOffsetMin: Int): Int {
        val hour = BrainMath.floorMod(BrainMath.floorDiv(nowMs + tzOffsetMin * 60_000L, 3_600_000L), 24L).toInt()
        return when {
            hour >= 5 && hour < 12 -> DAYPART_MORNING
            hour >= 12 && hour < 17 -> DAYPART_AFTERNOON
            hour >= 17 && hour < 22 -> DAYPART_EVENING
            else -> DAYPART_NIGHT
        }
    }

    /** Monday is 0 (see [BrainMath.weekday]). */
    fun weekday(nowMs: Long, tzOffsetMin: Int): Int = BrainMath.weekday(BrainMath.localDay(nowMs, tzOffsetMin))

    /** The plan is rebuilt when this changes: the local day (it starts at 04:00) and the daypart. */
    fun planKey(nowMs: Long, tzOffsetMin: Int): Long = Repetition.mixDay(nowMs, tzOffsetMin) * 4L + daypart(nowMs, tzOffsetMin)

    class Hero(val kind: Int, /** Index of the Daily Mix for [HERO_DAILY]; 0 otherwise. */ val index: Int)

    /**
     * The hero (8.1 slot 1): First Listen on Mondays, else a new release from a top-5 artist, else one of the
     * Daily Mixes (rotating at each daypart boundary).
     */
    fun hero(
        hasFirstListen: Boolean,
        hasTopRelease: Boolean,
        dailyCount: Int,
        nowMs: Long,
        tzOffsetMin: Int,
        salt: Long,
    ): Hero {
        val monday = weekday(nowMs - 4L * 3_600_000L, tzOffsetMin) == 0
        if (hasFirstListen && monday) return Hero(HERO_FIRST_LISTEN, 0)
        if (hasTopRelease) return Hero(HERO_RELEASE, 0)
        if (dailyCount > 0) {
            val h = BrainHash.splitmix64(salt xor planKey(nowMs, tzOffsetMin))
            return Hero(HERO_DAILY, java.lang.Long.remainderUnsigned(h, dailyCount.toLong()).toInt())
        }
        if (hasFirstListen) return Hero(HERO_FIRST_LISTEN, 0)
        return Hero(HERO_NONE, 0)
    }

    /** The "Jump back in" tiles: the first [count] distinct contexts of [keys], newest first. */
    fun distinctContexts(keys: List<String>, count: Int): List<Int> {
        val seen = HashSet<String>()
        val out = ArrayList<Int>(count)
        for (i in keys.indices) {
            if (out.size >= count) break
            if (seen.add(keys[i])) out.add(i)
        }
        return out
    }
}
