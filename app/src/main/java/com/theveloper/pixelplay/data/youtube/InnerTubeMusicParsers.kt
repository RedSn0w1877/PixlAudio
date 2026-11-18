package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.brain.BrainText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * YouTube Music response JSON to plain rows (docs/brain-spec.md, 3.3 "Parser rules").
 *
 * Works on [JsonElement] so it runs in plain JVM unit tests against the saved responses in
 * `app/src/test/resources/innertube`. The rules, each one a break that happened to some client:
 *
 *  1. Renderers are found by name with a tree walk, never by array index. Byline runs are classified
 *     by their `pageType`; years and play counts by their shape.
 *  2. A response without the expected renderer is "no data" (an empty page), not an error.
 *  3. `playlistPanelVideoWrapperRenderer` is unwrapped to its primary renderer; rows with
 *     `unplayableText` are skipped; a missing `musicVideoType` is UNKNOWN, not official audio.
 *  4. Shelves without a header are skipped.
 */
object InnerTubeMusicParsers {

    private const val PAGE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val PAGE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val PAGE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val PAGE_TRACK_RELATED = "MUSIC_PAGE_TYPE_TRACK_RELATED"
    private const val BULLET = "•"

    // ── Radio (`next`) ───────────────────────────────────────────────────────

    /** A radio's first page or a continuation page. Never throws on an unexpected shape. */
    fun parseRadio(root: JsonElement): RadioPage {
        // No panel is "no rows", not "nothing": the response can still name the seed's Related tab, and
        // that tab is the first fallback of an empty or junk radio.
        val panel = findFirst(root, "playlistPanelRenderer")
            ?: findFirst(root, "playlistPanelContinuation")

        val rows = ArrayList<RadioRow>(50)
        panel?.array("contents")?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val renderer = obj.obj("playlistPanelVideoRenderer")
                ?: obj.obj("playlistPanelVideoWrapperRenderer")?.obj("primaryRenderer")?.obj("playlistPanelVideoRenderer")
                ?: return@forEach
            parsePanelRow(renderer)?.let(rows::add)
        }

        var continuation: String? = null
        panel?.array("continuations")?.forEach { item ->
            (item as? JsonObject)?.obj("nextRadioContinuationData")?.string("continuation")
                ?.takeIf { it.isNotBlank() }?.let { continuation = it }
        }

        val chips = ArrayList<RadioChip>()
        findFirst(root, "musicQueueRenderer")?.obj("subHeaderChipCloud")?.let { cloud ->
            collect(cloud, "chipCloudChipRenderer") { chip ->
                val endpoint = findFirst(chip["navigationEndpoint"], "watchEndpoint")
                val playlistId = endpoint?.string("playlistId")
                if (!playlistId.isNullOrBlank()) {
                    chips.add(RadioChip(runsText(chip.obj("text")), playlistId, endpoint.string("params")))
                }
            }
        }

        var related: String? = null
        collect(root, "tabRenderer") { tab ->
            val endpoint = tab.obj("endpoint")
            if (pageTypeOf(endpoint) == PAGE_TRACK_RELATED) {
                browseIdOf(endpoint)?.let { related = it }
            }
        }
        return RadioPage(rows, continuation, chips, related)
    }

    private fun parsePanelRow(renderer: JsonObject): RadioRow? {
        if (renderer.containsKey("unplayableText")) return null
        val videoId = renderer.string("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = runsText(renderer.obj("title")).takeIf { it.isNotBlank() } ?: return null
        val byline = parseByline(renderer.obj("longBylineText")?.array("runs"))
        return RadioRow(
            videoId = videoId,
            title = title,
            artists = byline.artists,
            album = byline.album,
            albumBrowseId = byline.albumBrowseId,
            year = byline.year,
            durationSec = parseClock(runsText(renderer.obj("lengthText"))),
            thumbnailUrl = largestThumbnail(renderer.obj("thumbnail")?.array("thumbnails")),
            explicit = hasExplicitBadge(renderer.array("badges")),
            kind = MusicVideoKind.from(findFirstString(renderer["navigationEndpoint"], "musicVideoType")),
        )
    }

    // ── Related tab (`browse MPTRt_...`) ─────────────────────────────────────

    fun parseRelated(root: JsonElement): RelatedPage {
        var alsoLike: List<RadioRow> = emptyList()
        val otherPerformances = ArrayList<RadioRow>()
        var similar: List<RadioArtistRef> = emptyList()
        for (shelf in shelves(root)) {
            val songs = shelf.items.mapNotNull { it.obj("musicResponsiveListItemRenderer")?.let(::parseListItemRow) }
            if (songs.isNotEmpty()) {
                // hl=en is pinned for exactly this reason: the only thing that tells "Other performances"
                // (same song, a dedupe list) from "You might also like" is the header text.
                if (shelf.header.contains("performances", ignoreCase = true)) {
                    otherPerformances.addAll(songs)
                } else if (alsoLike.isEmpty()) {
                    alsoLike = songs
                }
                continue
            }
            val artists = shelf.items.mapNotNull(::parseTwoRowArtist)
            if (artists.isNotEmpty() && artists.size == shelf.items.size && similar.isEmpty()) similar = artists
        }
        return RelatedPage(alsoLike, otherPerformances, similar)
    }

    // ── Artist page (`browse UC...`) ─────────────────────────────────────────

    fun parseArtistPage(root: JsonElement): ArtistPage {
        val rootObj = root as? JsonObject ?: return ArtistPage.EMPTY
        val headerRoot = rootObj["header"]
        val header = findFirst(headerRoot, "musicImmersiveHeaderRenderer")
            ?: findFirst(headerRoot, "musicVisualHeaderRenderer")
        val radio = findFirst(header?.get("startRadioButton"), "watchEndpoint")?.let { endpoint ->
            val playlistId = endpoint.string("playlistId")?.takeIf { it.isNotBlank() } ?: return@let null
            RadioEndpoint(endpoint.string("videoId"), playlistId, endpoint.string("params"))
        }

        var topSongs: List<RadioRow> = emptyList()
        val albums = ArrayList<ReleaseRef>()
        val singles = ArrayList<ReleaseRef>()
        var fans: List<RadioArtistRef> = emptyList()
        for (shelf in shelves(root)) {
            val songs = shelf.items.mapNotNull { it.obj("musicResponsiveListItemRenderer")?.let(::parseListItemRow) }
            if (songs.isNotEmpty()) {
                if (topSongs.isEmpty()) topSongs = songs
                continue
            }
            val releases = shelf.items.mapNotNull(::parseTwoRowRelease)
            if (releases.isNotEmpty() && releases.size == shelf.items.size) {
                if (shelf.header.contains("single", ignoreCase = true)) singles.addAll(releases) else albums.addAll(releases)
                continue
            }
            val artists = shelf.items.mapNotNull(::parseTwoRowArtist)
            if (artists.isNotEmpty() && artists.size == shelf.items.size && fans.isEmpty()) fans = artists
        }
        return ArtistPage(
            name = runsText(header?.obj("title")),
            radio = radio,
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            fansAlsoLike = fans,
        )
    }

    // ── Shelves and their items ──────────────────────────────────────────────

    private class Shelf(val header: String, val items: List<JsonObject>)

    /** Every headed shelf of the page's section list, under either browse-results layout. */
    private fun shelves(root: JsonElement): List<Shelf> {
        val sections = findFirst(root, "sectionListRenderer")?.array("contents") ?: return emptyList()
        val out = ArrayList<Shelf>(sections.size)
        for (section in sections) {
            val obj = section as? JsonObject ?: continue
            for (name in SHELF_RENDERERS) {
                val body = obj.obj(name) ?: continue
                val header = runsText(findFirst(body["header"], "title") ?: body.obj("title"))
                if (header.isBlank()) continue
                val items = body.array("contents")?.mapNotNull { it as? JsonObject }.orEmpty()
                out.add(Shelf(header, items))
            }
        }
        return out
    }

    private val SHELF_RENDERERS = arrayOf("musicCarouselShelfRenderer", "musicShelfRenderer")
    private val PLAYS_PATTERN = Regex("""([\d.]+)([KMB]?) (?:plays|views)""")
    private val YEAR_PATTERN = Regex("""\d{4}""")
    private val CLOCK_PATTERN = Regex("""\d+(:\d{1,2}){1,2}""")

    private fun parseListItemRow(renderer: JsonObject): RadioRow? {
        val columns = renderer.array("flexColumns")?.mapNotNull {
            (it as? JsonObject)?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
        }.orEmpty()
        val titleColumn = columns.firstOrNull() ?: return null
        val watch = findFirst(titleColumn, "watchEndpoint")
        val videoId = watch?.string("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = runsText(titleColumn).takeIf { it.isNotBlank() } ?: return null

        // The remaining columns are one byline split across columns.
        val runs = ArrayList<JsonElement>()
        var playCount: Long? = null
        for (i in 1 until columns.size) {
            val columnRuns = columns[i].array("runs") ?: continue
            if (runs.isNotEmpty()) runs.add(SEPARATOR_RUN)
            for (run in columnRuns) {
                val text = (run as? JsonObject)?.string("text")?.trim().orEmpty()
                val plays = parsePlayCount(text)
                if (plays != null) playCount = plays else runs.add(run)
            }
        }
        val byline = parseByline(runs)
        return RadioRow(
            videoId = videoId,
            title = title,
            artists = byline.artists,
            album = byline.album,
            albumBrowseId = byline.albumBrowseId,
            year = byline.year,
            durationSec = parseClock(runsText(findFirst(renderer["fixedColumns"], "text"))),
            thumbnailUrl = largestThumbnail(findFirst(renderer["thumbnail"], "thumbnail")?.array("thumbnails")
                ?: findFirstArray(renderer["thumbnail"], "thumbnails")),
            explicit = hasExplicitBadge(renderer.array("badges")),
            kind = MusicVideoKind.from(findFirstString(watch, "musicVideoType")),
            playCount = playCount,
        )
    }

    private val SEPARATOR_RUN: JsonElement = JsonObject(mapOf("text" to JsonPrimitive(" $BULLET ")))

    private fun parseTwoRowArtist(item: JsonObject): RadioArtistRef? {
        val renderer = item.obj("musicTwoRowItemRenderer") ?: return null
        val endpoint = renderer.obj("navigationEndpoint")
        if (pageTypeOf(endpoint) != PAGE_ARTIST) return null
        val name = runsText(renderer.obj("title")).takeIf { it.isNotBlank() } ?: return null
        return RadioArtistRef(name, browseIdOf(endpoint))
    }

    private fun parseTwoRowRelease(item: JsonObject): ReleaseRef? {
        val renderer = item.obj("musicTwoRowItemRenderer") ?: return null
        val endpoint = renderer.obj("navigationEndpoint")
        if (pageTypeOf(endpoint) != PAGE_ALBUM) return null
        val browseId = browseIdOf(endpoint) ?: return null
        var year = 0
        renderer.obj("subtitle")?.array("runs")?.forEach { run ->
            val text = (run as? JsonObject)?.string("text")?.trim().orEmpty()
            if (YEAR_PATTERN.matches(text)) year = text.toInt()
        }
        return ReleaseRef(browseId, runsText(renderer.obj("title")), year)
    }

    // ── Bylines ──────────────────────────────────────────────────────────────

    private class Byline(
        val artists: List<RadioArtistRef>,
        val album: String?,
        val albumBrowseId: String?,
        val year: Int,
    )

    /**
     * "Artist & Artist • Album • 2020" as runs. Linked runs are classified by their page type. When no
     * run links to an artist, the plain runs before the first bullet are the artists, without ids.
     */
    private fun parseByline(runs: List<JsonElement>?): Byline {
        val artists = ArrayList<RadioArtistRef>(2)
        val plainBeforeBullet = ArrayList<String>(1)
        var album: String? = null
        var albumBrowseId: String? = null
        var year = 0
        var seenBullet = false
        runs?.forEach { element ->
            val run = element as? JsonObject ?: return@forEach
            val text = run.string("text").orEmpty()
            val trimmed = text.trim()
            val endpoint = run.obj("navigationEndpoint")
            when (pageTypeOf(endpoint)) {
                PAGE_ARTIST, PAGE_USER_CHANNEL -> artists.add(RadioArtistRef(text, browseIdOf(endpoint)))
                PAGE_ALBUM -> {
                    album = text
                    albumBrowseId = browseIdOf(endpoint)
                }
                else -> when {
                    YEAR_PATTERN.matches(trimmed) -> year = trimmed.toInt()
                    trimmed == BULLET -> seenBullet = true
                    !seenBullet && trimmed != "&" && trimmed != "," && trimmed.isNotEmpty() -> plainBeforeBullet.add(trimmed)
                }
            }
        }
        if (artists.isEmpty()) plainBeforeBullet.mapTo(artists) { RadioArtistRef(it, null) }
        return Byline(artists, album, albumBrowseId, year)
    }

    /** "3.4B plays" to 3,400,000,000; null when [text] is not a play count. */
    internal fun parsePlayCount(text: String): Long? {
        val match = PLAYS_PATTERN.matchEntire(text) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val scale = when (match.groupValues[2]) {
            "K" -> 1e3
            "M" -> 1e6
            "B" -> 1e9
            else -> 1.0
        }
        return Math.round(number * scale)
    }

    /** "5:02" to 302, "1:02:03" to 3723; 0 when [text] is not a clock. */
    internal fun parseClock(text: String): Int {
        if (!CLOCK_PATTERN.matches(text)) return 0
        var total = 0
        for (part in text.split(':')) total = total * 60 + part.toInt()
        return total
    }

    // ── Junk detector and page merging ───────────────────────────────────────

    /** Fewer rows than this in a radio's first page means the radio is junk. */
    const val JUNK_MIN_ROWS = 40

    /** The seed's artist must show up again within this many rows after the seed. */
    const val JUNK_ARTIST_WINDOW = 25

    /**
     * True when a radio's first page should not be stored (rule 6): fewer than 40 rows, or no row among
     * rows 1..25 shares an artist with the seed. [seedArtistIds] are channel ids; a row without an
     * artist id is kept but can never satisfy the test. When the seed has no channel id at all (a row
     * whose byline is not linked) the test falls back to the folded artist name, so such a seed is
     * not junk by construction.
     */
    fun isJunkRadio(
        rows: List<RadioRow>,
        seedVideoId: String?,
        seedArtistIds: Set<String>,
        seedArtistNames: Collection<String> = emptyList(),
    ): Boolean {
        if (rows.size < JUNK_MIN_ROWS) return true
        val seedIndex = rows.indexOfFirst { it.videoId == seedVideoId }.coerceAtLeast(0)
        val seedRow = rows[seedIndex]
        val ids = seedArtistIds.ifEmpty { seedRow.artists.mapNotNullTo(HashSet()) { it.channelId } }
        val names = if (ids.isEmpty()) {
            (seedArtistNames + seedRow.artists.map { it.name }).mapTo(HashSet()) { BrainText.fold(it) }
                .apply { remove("") }
        } else {
            emptySet()
        }
        if (ids.isEmpty() && names.isEmpty()) return true
        var checked = 0
        for (i in rows.indices) {
            if (i == seedIndex) continue
            if (checked >= JUNK_ARTIST_WINDOW) break
            checked++
            val row = rows[i]
            if (ids.isNotEmpty()) {
                if (row.artists.any { it.channelId != null && it.channelId in ids }) return false
            } else if (row.artists.any { BrainText.fold(it.name) in names }) {
                return false
            }
        }
        return true
    }

    /**
     * Merges a radio's pages (rule 5): continuation pages repeat earlier rows, so a page counts by its
     * new video ids, never by its length. Stop on a missing token, after [MAX_CONTINUATIONS]
     * continuations, or when two pages in a row add fewer than [MIN_NEW_PER_PAGE] new ids.
     *
     * Rows are deduplicated by video id here; the caller dedupes again by recording id, because one
     * recording appears under several audio ids.
     */
    class RadioPager {
        private val seen = HashSet<String>()
        private val merged = ArrayList<RadioRow>(100)
        private var continuationsTaken = 0
        private var poorPagesInARow = 0
        private var pages = 0

        val rows: List<RadioRow> get() = merged

        /** Token to request next, or null when the chain is over. */
        var nextToken: String? = null
            private set

        /** New ids each accepted page added, in order. */
        val newPerPage = ArrayList<Int>(4)

        /** Adds one page and returns how many new rows it brought. */
        fun accept(page: RadioPage): Int {
            var added = 0
            for (row in page.rows) {
                if (seen.add(row.videoId)) {
                    merged.add(row)
                    added++
                }
            }
            newPerPage.add(added)
            if (pages > 0) {
                continuationsTaken++
                poorPagesInARow = if (added < MIN_NEW_PER_PAGE) poorPagesInARow + 1 else 0
            }
            pages++
            nextToken = page.continuation?.takeIf {
                continuationsTaken < MAX_CONTINUATIONS && poorPagesInARow < 2
            }
            return added
        }

        companion object {
            const val MAX_CONTINUATIONS = 3
            const val MIN_NEW_PER_PAGE = 5
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun runsText(text: JsonObject?): String {
        val runs = text?.array("runs") ?: return ""
        if (runs.size == 1) return (runs[0] as? JsonObject)?.string("text").orEmpty()
        val out = StringBuilder()
        for (run in runs) (run as? JsonObject)?.string("text")?.let(out::append)
        return out.toString()
    }

    private fun pageTypeOf(endpoint: JsonObject?): String? =
        endpoint?.obj("browseEndpoint")
            ?.obj("browseEndpointContextSupportedConfigs")
            ?.obj("browseEndpointContextMusicConfig")
            ?.string("pageType")

    private fun browseIdOf(endpoint: JsonObject?): String? =
        endpoint?.obj("browseEndpoint")?.string("browseId")?.takeIf { it.isNotBlank() }

    private fun largestThumbnail(thumbnails: JsonArray?): String? {
        var best: String? = null
        var bestWidth = -1
        thumbnails?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val url = obj.string("url") ?: return@forEach
            val width = (obj["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (width >= bestWidth) {
                bestWidth = width
                best = url
            }
        }
        return best
    }

    private fun hasExplicitBadge(badges: JsonArray?): Boolean {
        badges?.forEach { badge ->
            val type = (badge as? JsonObject)?.obj("musicInlineBadgeRenderer")?.obj("icon")?.string("iconType")
            if (type == "MUSIC_EXPLICIT_BADGE") return true
        }
        return false
    }

    /** Depth-first: the first object stored under [key] anywhere below [root]. */
    private fun findFirst(root: JsonElement?, key: String): JsonObject? {
        when (root) {
            is JsonObject -> {
                (root[key] as? JsonObject)?.let { return it }
                for (value in root.values) findFirst(value, key)?.let { return it }
            }
            is JsonArray -> for (value in root) findFirst(value, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findFirstArray(root: JsonElement?, key: String): JsonArray? {
        when (root) {
            is JsonObject -> {
                (root[key] as? JsonArray)?.let { return it }
                for (value in root.values) findFirstArray(value, key)?.let { return it }
            }
            is JsonArray -> for (value in root) findFirstArray(value, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findFirstString(root: JsonElement?, key: String): String? {
        when (root) {
            is JsonObject -> {
                root.string(key)?.let { return it }
                for (value in root.values) findFirstString(value, key)?.let { return it }
            }
            is JsonArray -> for (value in root) findFirstString(value, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    /** Depth-first: every object stored under [key], in document order. Does not look inside a match. */
    private fun collect(root: JsonElement?, key: String, onFound: (JsonObject) -> Unit) {
        when (root) {
            is JsonObject -> for ((name, value) in root) {
                if (name == key && value is JsonObject) onFound(value) else collect(value, key, onFound)
            }
            is JsonArray -> for (value in root) collect(value, key, onFound)
            else -> Unit
        }
    }
}
