package com.theveloper.pixelplay.songtap

/**
 * The "nearby phones" list of the Song Drop picker, and the sender's "Song drop to X?" prompt.
 *
 * Both come from advertisements only: no connection is made to list a phone. A phone is listed when its smoothed signal
 * is inside the broad list gate ([SongTapTuning.LIST_ENTER_DBM] to enter, [SongTapTuning.LIST_EXIT_DBM] to leave, so it does
 * not flicker) and it was heard in the last [STALE_MS]. Connecting to it and beaming still need the strict near gate
 * ([SongTapTuning.NEAR_ENTER_DBM]); the prompt appears only for a phone that is inside it.
 *
 * Pure and integer only: the transport feeds [update] with every scan result and calls [visible] / [promptFor] when it
 * publishes; the clock enters as an argument. Entries are by peer number (the transport's number for an address), so one
 * phone is one entry however often it advertises.
 */
data class NearbyEntry(
    val id: Int,
    val name: String,
    val role: Role,
    /** The smoothed signal, whole dBm. */
    val dbm: Int,
    val lastSeenMs: Long,
    /** Inside the list gate (with hysteresis). */
    val listed: Boolean,
    /** Inside the strict near gate (with hysteresis). */
    val near: Boolean,
)

class NearbyBook {
    private val entries = LinkedHashMap<Int, NearbyEntry>()
    private val ignoredUntil = HashMap<Int, Long>()

    /** One scan result of phone [id]: its signal smoothed by [SongTapDetector]'s fast average ([fastQ], dBm times 256). */
    fun update(id: Int, name: String, role: Role, fastQ: Int, near: Boolean, nowMs: Long) {
        val dbm = SongTapDetector.floorDiv(fastQ, RSSI_SCALE)
        val before = entries[id]
        val listed = if (before != null && before.listed) dbm >= SongTapTuning.LIST_EXIT_DBM else dbm >= SongTapTuning.LIST_ENTER_DBM
        entries[id] = NearbyEntry(id, name, role, dbm, nowMs, listed, near)
    }

    fun remove(id: Int) {
        entries.remove(id)
        ignoredUntil.remove(id)
    }

    fun clear() {
        entries.clear()
        ignoredUntil.clear()
    }

    /**
     * The picker's rows for phones of [role]: listed, heard within [STALE_MS], strongest first (ties by number), and one row per
     * phone: when two rows have the same name and one was last heard more than [SAME_PHONE_GAP_MS] after the other, it is the same
     * phone under a new random address and only the newer shows.
     */
    fun visible(role: Role, nowMs: Long): List<NearbyEntry> {
        val live = ArrayList<NearbyEntry>()
        for (e in entries.values) {
            if (e.role == role && e.listed && nowMs - e.lastSeenMs <= STALE_MS) live.add(e)
        }
        val keep = ArrayList<NearbyEntry>(live.size)
        for (e in live) {
            var superseded = false
            for (o in live) {
                if (o.id != e.id && o.name == e.name && o.lastSeenMs - e.lastSeenMs > SAME_PHONE_GAP_MS) { superseded = true; break }
            }
            if (!superseded) keep.add(e)
        }
        keep.sortWith { a, b -> if (a.dbm != b.dbm) b.dbm - a.dbm else a.id - b.id }
        return keep
    }

    /** "No" on the prompt: phone [id] is not asked about for [IGNORE_MS]. */
    fun ignore(id: Int, nowMs: Long) {
        ignoredUntil[id] = nowMs + IGNORE_MS
    }

    fun isIgnored(id: Int, nowMs: Long): Boolean {
        val until = ignoredUntil[id] ?: return false
        if (nowMs >= until) { ignoredUntil.remove(id); return false }
        return true
    }

    /**
     * The phone to ask "Song drop to X?" about: of [role], inside the strict near gate, heard within [STALE_MS], not ignored;
     * the strongest, ties by number. Null when none.
     */
    fun promptFor(role: Role, nowMs: Long): NearbyEntry? {
        var best: NearbyEntry? = null
        for (e in visible(role, nowMs)) {
            if (!e.near || isIgnored(e.id, nowMs)) continue
            if (best == null) best = e
        }
        return best
    }

    /** The entry of [id] as it stands, regardless of the list gate; null when unknown. */
    fun get(id: Int): NearbyEntry? = entries[id]

    companion object {
        /** A row disappears when its phone has not advertised for this long. */
        const val STALE_MS = 4_000L

        /** Two same-name rows whose last sightings are this far apart are one phone that changed address. */
        const val SAME_PHONE_GAP_MS = 1_500L

        /** How long "No" silences the prompt for that phone. */
        const val IGNORE_MS = 60_000L
    }
}
