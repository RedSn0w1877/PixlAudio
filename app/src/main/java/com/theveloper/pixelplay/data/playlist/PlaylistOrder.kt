package com.theveloper.pixelplay.data.playlist

/** Applies a visible drag order without deleting unavailable or concurrently added entries. */
internal fun mergePlaylistOrder(currentIds: List<String>, requestedIds: List<String>): List<String> {
    val current = currentIds.toSet()
    val requested = requestedIds.filter { it in current }.distinct()
    val requestedSet = requested.toSet()
    return requested + currentIds.filterNot { it in requestedSet }.distinct()
}
