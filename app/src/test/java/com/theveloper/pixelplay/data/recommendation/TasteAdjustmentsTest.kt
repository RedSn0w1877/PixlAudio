package com.theveloper.pixelplay.data.recommendation

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Explicit taste adjustments: the maths and its caps, expiry, matching, how requests apply, and the engine under them. */
class TasteAdjustmentsTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun song(id: String, artist: String, title: String = "Song $id", genre: String? = null, album: String = "", year: Int = 0) =
        Song.emptySong().copy(id = id, title = title, artist = artist, artistId = -1, genre = genre, album = album, year = year, duration = 180_000)

    private fun adjustment(
        kind: TasteKind,
        target: TasteTarget,
        key: String,
        strength: Int = 2,
        id: String = "$kind-$key",
        expiresAtMs: Long? = null,
        altKey: String? = null,
    ) = TasteAdjustment(
        id = id, kind = kind, target = target, key = key, altKey = altKey, label = key,
        strength = strength, createdAtMs = now, expiresAtMs = expiresAtMs,
    )

    private fun artistKey(name: String) = TasteAdjustments.nameKey(name)

    private val songs = listOf(
        song("a", "Phoebe Bridgers", genre = "Indie"),
        song("b", "Some Band", genre = "Rock"),
        song("c", "Dua Lipa, DaBaby", genre = "Pop"),
        song("d", "Another Act", genre = "Pop"),
        song("e", "Some Band", title = "Other track", genre = "Rock"),
    )

    private fun rank(adjustments: TasteAdjustments = TasteAdjustments.NONE) =
        MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 7, adjustments = adjustments)

    private fun scoreOf(picks: List<MusicRecommendationEngine.Pick>, id: String) = picks.first { it.song.id == id }.score

    // --- the maths ---

    @Test
    fun `strength 1 2 3 is about x1,25 x1,6 x2,2 and reduce is the inverse`() {
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.BOOST, 1)).isEqualTo(1.25)
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.BOOST, 2)).isEqualTo(1.6)
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.BOOST, 3)).isEqualTo(2.2)
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.REDUCE, 2)).isWithin(1e-9).of(1 / 1.6)
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.MUTE, 3)).isEqualTo(1.0)
        // Out-of-range strengths are clamped, not trusted.
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.BOOST, 99)).isEqualTo(2.2)
        assertThat(TasteAdjustmentMath.multiplier(TasteKind.BOOST, -4)).isEqualTo(1.25)
    }

    @Test
    fun `a boost never lowers an affinity and a reduce never raises one`() {
        val affinities = (-10..10).map { it / 10.0 }
        for (kind in listOf(TasteKind.BOOST, TasteKind.REDUCE)) {
            for (strength in 1..3) {
                val m = TasteAdjustmentMath.multiplier(kind, strength)
                for (a in affinities) {
                    val adjusted = TasteAdjustmentMath.adjustAffinity(a, m)
                    if (kind == TasteKind.BOOST) assertThat(adjusted).isAtLeast(a) else assertThat(adjusted).isAtMost(a)
                    assertThat(adjusted).isAtMost(TasteAdjustmentMath.MAX_AFFINITY)
                    assertThat(adjusted).isAtLeast(-TasteAdjustmentMath.MAX_AFFINITY)
                }
            }
        }
        assertThat(TasteAdjustmentMath.adjustAffinity(0.37, 1.0)).isEqualTo(0.37)
    }

    @Test
    fun `stacked adjustments on one dimension never pass the cap`() {
        val stacked = TasteAdjustments.of(
            listOf(
                adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Some Band"), 3, id = "1"),
                adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Some Band"), 3, id = "2"),
                adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Some Band"), 3, id = "3"),
            ),
            now,
        )
        val lift = stacked.liftFor(songs[1])
        assertThat(lift.artist).isEqualTo(TasteAdjustmentMath.CAP)
        val reduced = TasteAdjustments.of(
            List(4) { adjustment(TasteKind.REDUCE, TasteTarget.ARTIST, artistKey("Some Band"), 3, id = "r$it") },
            now,
        )
        assertThat(reduced.liftFor(songs[1]).artist).isEqualTo(1.0 / TasteAdjustmentMath.CAP)
        assertThat(reduced.combinedMultiplier(songs[1])).isAtLeast(1.0 / TasteAdjustmentMath.CAP)
    }

    @Test
    fun `expired adjustments are not active`() {
        val list = listOf(
            adjustment(TasteKind.MUTE, TasteTarget.ARTIST, artistKey("Some Band"), id = "old", expiresAtMs = now - 1),
            adjustment(TasteKind.BOOST, TasteTarget.GENRE, "pop", id = "new", expiresAtMs = now + day),
            adjustment(TasteKind.BLOCK, TasteTarget.SONG, "d", id = "forever"),
        )
        val active = TasteAdjustments.of(list, now)
        assertThat(active.active.map { it.id }).containsExactly("new", "forever")
        assertThat(active.excludes(songs[1])).isFalse() // the mute has run out
        assertThat(TasteAdjustments.of(list, now + 2 * day).active.map { it.id }).containsExactly("forever")
        assertThat(TasteAdjustments.of(list.take(1), now)).isSameInstanceAs(TasteAdjustments.NONE)
    }

    // --- matching ---

    @Test
    fun `artists match any credit, ignoring case and Topic channels`() {
        val adj = TasteAdjustments.of(listOf(adjustment(TasteKind.BLOCK, TasteTarget.ARTIST, artistKey("DABABY"))), now)
        assertThat(adj.blocks(songs[2])).isTrue()
        assertThat(adj.blocks(songs[0])).isFalse()
        val topic = song("t", "dababy - Topic")
        assertThat(adj.blocks(topic)).isTrue()
        val withRefs = song("r", "x").copy(artists = listOf(ArtistRef(1, "DaBaby", isPrimary = true)))
        assertThat(adj.blocks(withRefs)).isTrue()
    }

    @Test
    fun `a song matches by library id and by its spotify id alias`() {
        val adj = TasteAdjustments.of(
            listOf(adjustment(TasteKind.MUTE, TasteTarget.SONG, "-123", altKey = "SPOT22")),
            now,
        )
        assertThat(adj.excludes(song("-123", "x"))).isTrue()
        assertThat(adj.excludes(song("spotify_SPOT22", "x"))).isTrue()
        assertThat(adj.excludes(song("9", "x").copy(spotifyId = "SPOT22"))).isTrue()
        assertThat(adj.excludes(song("9", "x"))).isFalse()
        assertThat(adj.blocks(song("-123", "x"))).isFalse() // muted, not blocked
    }

    @Test
    fun `genre and album targets match`() {
        val adj = TasteAdjustments.of(
            listOf(
                adjustment(TasteKind.MUTE, TasteTarget.GENRE, "pop"),
                adjustment(TasteKind.BLOCK, TasteTarget.ALBUM, TasteAdjustments.nameKey("Bad Album")),
            ),
            now,
        )
        assertThat(adj.excludes(songs[2])).isTrue()
        assertThat(adj.excludes(songs[0])).isFalse()
        assertThat(adj.blocks(song("z", "q", album = "BAD  album"))).isTrue()
        assertThat(adj.excludes(song("m", "q", genre = "Rock, Pop"))).isTrue() // one of several genres
    }

    // --- the engine ---

    @Test
    fun `with zero adjustments the ranking is exactly the same`() {
        val plain = MusicRecommendationEngine.rank(songs, emptySet(), emptyMap(), emptyMap(), now, 7)
        assertThat(rank()).isEqualTo(plain)
        assertThat(rank(TasteAdjustments.of(emptyList(), now))).isEqualTo(plain)
        val expired = TasteAdjustments.of(
            listOf(adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Some Band"), expiresAtMs = now - 5)),
            now,
        )
        assertThat(rank(expired)).isEqualTo(plain)
        // Same through the Muselle tiers and the Home planner entry points.
        assertThat(Muselle.rank(MuselleVariant.BASIC, songs, emptySet(), emptyMap(), emptyMap(), now, 7)).isEqualTo(plain)
        assertThat(
            Muselle.rank(MuselleVariant.PLUS, songs, emptySet(), emptyMap(), emptyMap(), now, 7, adjustments = TasteAdjustments.NONE)
        ).isEqualTo(Muselle.rank(MuselleVariant.PLUS, songs, emptySet(), emptyMap(), emptyMap(), now, 7))
    }

    @Test
    fun `boosting an artist moves its songs up and reducing moves them down`() {
        val base = rank()
        val boosted = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Some Band"), 3)), now))
        val reduced = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.REDUCE, TasteTarget.ARTIST, artistKey("Some Band"), 3)), now))
        for (id in listOf("b", "e")) {
            assertThat(scoreOf(boosted, id)).isGreaterThan(scoreOf(base, id))
            assertThat(scoreOf(reduced, id)).isLessThan(scoreOf(base, id))
        }
        // Other artists are untouched.
        assertThat(scoreOf(boosted, "a")).isEqualTo(scoreOf(base, "a"))
        assertThat(boosted.first().song.artist).isEqualTo("Some Band")
        assertThat(reduced.last().song.artist).isEqualTo("Some Band")
        assertThat(boosted.first { it.song.id == "b" }.reason).isEqualTo("More of this, as you asked")
        assertThat(reduced.first { it.song.id == "b" }.reason).startsWith(MusicRecommendationEngine.REASON_PREFIX_DEMOTED)
    }

    @Test
    fun `a stronger boost moves a song further`() {
        fun score(strength: Int) = scoreOf(
            rank(TasteAdjustments.of(listOf(adjustment(TasteKind.BOOST, TasteTarget.ARTIST, artistKey("Another Act"), strength)), now)),
            "d",
        )
        assertThat(score(1)).isLessThan(score(2))
        assertThat(score(2)).isLessThan(score(3))
    }

    @Test
    fun `genre and song boosts and reductions move the right way`() {
        val base = rank()
        val genreUp = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.BOOST, TasteTarget.GENRE, "indie", 3)), now))
        assertThat(scoreOf(genreUp, "a")).isGreaterThan(scoreOf(base, "a"))
        val songDown = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.REDUCE, TasteTarget.SONG, "d", 3)), now))
        assertThat(scoreOf(songDown, "d")).isLessThan(scoreOf(base, "d"))
        assertThat(scoreOf(songDown, "c")).isEqualTo(scoreOf(base, "c"))
        val songUp = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.BOOST, TasteTarget.SONG, "d", 3)), now))
        assertThat(scoreOf(songUp, "d")).isGreaterThan(scoreOf(base, "d"))
    }

    @Test
    fun `muted and blocked songs never reach the ranking`() {
        val muted = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.MUTE, TasteTarget.ARTIST, artistKey("Some Band"))), now))
        assertThat(muted.map { it.song.id }).containsExactly("a", "c", "d")
        val blocked = rank(TasteAdjustments.of(listOf(adjustment(TasteKind.BLOCK, TasteTarget.GENRE, "pop")), now))
        assertThat(blocked.map { it.song.id }).containsExactly("a", "b", "e")
        // The other songs keep exactly the score they had.
        val base = rank()
        assertThat(scoreOf(muted, "a")).isEqualTo(scoreOf(base, "a"))
    }

    @Test
    fun `home shelves leave out muted songs`() {
        val library = (1..12).map { song("s$it", if (it % 2 == 0) "Even Band" else "Odd Band", genre = "Rock") }
        val plan = HomeRecommendationPlanner.plan(
            library, emptySet(), emptyMap(), emptyMap(), nowMs = now, seed = 3,
            adjustments = TasteAdjustments.of(listOf(adjustment(TasteKind.MUTE, TasteTarget.ARTIST, artistKey("Even Band"))), now),
        )
        val shown = (plan.mixes + plan.shelves).flatMap { it.songs }
        assertThat(shown).isNotEmpty()
        assertThat(shown.none { it.artist == "Even Band" }).isTrue()
    }

    // --- applying requests ---

    private fun request(
        action: TasteAction,
        target: TasteTarget = TasteTarget.ARTIST,
        key: String = "radiohead",
        strength: Int = 2,
        expiresDays: Int? = null,
    ) = TasteRequest(action, target, key, null, key.replaceFirstChar { it.uppercase() }, strength, "because", expiresDays)

    private fun applyTo(current: List<TasteAdjustment>, vararg requests: TasteRequest): TasteOutcome {
        var n = 0
        return TasteAdjustmentLogic.apply(current, requests.toList(), now, TasteSetBy.TAIZO) { "id${n++}" }
    }

    @Test
    fun `a boost and a reduce on the same target replace each other`() {
        val first = applyTo(emptyList(), request(TasteAction.BOOST, strength = 3))
        assertThat(first.adjustments.map { it.kind }).containsExactly(TasteKind.BOOST)
        val second = applyTo(first.adjustments, request(TasteAction.REDUCE, strength = 1))
        assertThat(second.adjustments).hasSize(1)
        assertThat(second.adjustments.single().kind).isEqualTo(TasteKind.REDUCE)
        assertThat(second.adjustments.single().strength).isEqualTo(1)
        assertThat(second.changed).isTrue()
    }

    @Test
    fun `block covers mute, and mute on something blocked changes nothing`() {
        val muted = applyTo(emptyList(), request(TasteAction.MUTE))
        val blocked = applyTo(muted.adjustments, request(TasteAction.BLOCK))
        assertThat(blocked.adjustments.map { it.kind }).containsExactly(TasteKind.BLOCK)
        val again = applyTo(blocked.adjustments, request(TasteAction.MUTE))
        assertThat(again.adjustments).isEqualTo(blocked.adjustments)
        assertThat(again.changed).isFalse()
    }

    @Test
    fun `unmute and unblock take back only that kind`() {
        val both = applyTo(emptyList(), request(TasteAction.BOOST), request(TasteAction.MUTE, key = "drake"), request(TasteAction.BLOCK, key = "nickelback"))
        assertThat(both.adjustments).hasSize(3)
        val unmuted = applyTo(both.adjustments, request(TasteAction.UNMUTE, key = "drake"), request(TasteAction.UNBLOCK, key = "radiohead"))
        assertThat(unmuted.adjustments.map { it.key }).containsExactly("radiohead", "nickelback")
        assertThat(unmuted.changes.map { it.changed }).containsExactly(true, false).inOrder() // nothing blocked on radiohead
        val unblocked = applyTo(unmuted.adjustments, request(TasteAction.UNBLOCK, key = "nickelback"))
        assertThat(unblocked.adjustments.map { it.key }).containsExactly("radiohead")
    }

    @Test
    fun `expiry and who set it are stored`() {
        val outcome = applyTo(emptyList(), request(TasteAction.REDUCE, expiresDays = 7))
        val stored = outcome.adjustments.single()
        assertThat(stored.expiresAtMs).isEqualTo(now + 7 * day)
        assertThat(stored.setBy).isEqualTo(TasteSetBy.TAIZO)
        assertThat(stored.createdAtMs).isEqualTo(now)
        assertThat(TasteAdjustments.of(outcome.adjustments, now + 8 * day).isEmpty).isTrue()
    }

    @Test
    fun `the number of active changes is capped`() {
        val full = List(TasteAdjustmentLogic.MAX_ACTIVE) { adjustment(TasteKind.MUTE, TasteTarget.ARTIST, "artist$it", id = "x$it") }
        val outcome = applyTo(full, request(TasteAction.BOOST, key = "one more"))
        assertThat(outcome.adjustments).hasSize(TasteAdjustmentLogic.MAX_ACTIVE)
        assertThat(outcome.changed).isFalse()
        // Taking one back is still possible when full.
        assertThat(applyTo(full, request(TasteAction.UNMUTE, key = "artist3")).adjustments).hasSize(TasteAdjustmentLogic.MAX_ACTIVE - 1)
    }

    @Test
    fun `the stored form round trips and skips what it cannot read`() {
        val list = listOf(
            adjustment(TasteKind.BOOST, TasteTarget.ARTIST, "radiohead", 3, id = "a1").copy(reason = "loved it", setBy = TasteSetBy.USER),
            adjustment(TasteKind.BLOCK, TasteTarget.SONG, "-5", id = "b2", expiresAtMs = now + day, altKey = "SPOT"),
        )
        assertThat(TasteAdjustmentCodec.decode(TasteAdjustmentCodec.encode(list))).isEqualTo(list)
        assertThat(TasteAdjustmentCodec.decode(null)).isEmpty()
        assertThat(TasteAdjustmentCodec.decode("not json")).isEmpty()
        assertThat(TasteAdjustmentCodec.decode("""[{"id":"x"},{"id":"ok","kind":"mute","target":"genre","key":"pop"}]""").map { it.id })
            .containsExactly("ok")
    }

    // --- targets ---

    @Test
    fun `an artist is keyed by name and labelled with the library spelling`() {
        val resolved = TasteTargetResolver.resolve(TasteTarget.ARTIST, "  PHOEBE bridgers ", songs)
        assertThat(resolved.key).isEqualTo(artistKey("Phoebe Bridgers"))
        assertThat(resolved.label).isEqualTo("Phoebe Bridgers")
        // An artist that isn't in the library can still be muted.
        assertThat(TasteTargetResolver.resolve(TasteTarget.ARTIST, "Nobody Yet", songs).label).isEqualTo("Nobody Yet")
        assertThat(TasteTargetResolver.resolve(TasteTarget.GENRE, "pop", songs).label).isEqualTo("Pop")
    }

    @Test
    fun `a song resolves by id, spotify id or a clear title and refuses guesses`() {
        val library = songs + song("-9", "Radiohead", title = "Creep").copy(spotifyId = "SPOT1")
        assertThat(TasteTargetResolver.resolve(TasteTarget.SONG, "-9", library).key).isEqualTo("-9")
        val bySpotify = TasteTargetResolver.resolve(TasteTarget.SONG, "spotify:track:SPOT1", library)
        assertThat(bySpotify.key).isEqualTo("-9")
        assertThat(bySpotify.altKey).isEqualTo("SPOT1")
        assertThat(TasteTargetResolver.resolve(TasteTarget.SONG, "creep radiohead", library).key).isEqualTo("-9")
        assertThat(TasteTargetResolver.resolve(TasteTarget.SONG, "Creep", library).label).isEqualTo("Creep - Radiohead")
        assertThrows(IllegalArgumentException::class.java) { TasteTargetResolver.resolve(TasteTarget.SONG, "Song", library) } // fits many
        assertThrows(IllegalArgumentException::class.java) { TasteTargetResolver.resolve(TasteTarget.SONG, "no such track anywhere", library) }
        assertThrows(IllegalArgumentException::class.java) { TasteTargetResolver.resolve(TasteTarget.ARTIST, "   ", library) }
    }
}
