package com.theveloper.pixelplay.brain

/**
 * The supporting facts under a reason (docs/brain-spec.md, 8.8, stage 2): "up to two supporting facts
 * ('Bedroom pop . 2024 . you finished 9 of the last 10 like it')". With the data stage 2a has there are
 * three kinds of fact, each a true statement read from the listener's own log or from the pick itself:
 *
 *  - [KEY_ARTIST_FINISHED]: how many of the last few plays by the pick's artist the listener finished;
 *  - [KEY_TRACK_FINISHED]: how often they finished this very track;
 *  - [KEY_YEAR]: the year the track came out, when the source said one.
 *
 * Genre and mood facts need the atlas of stage 2b and are not guessed. The words live in each app's
 * resources (D16); this picks which facts are worth showing and what their arguments are.
 */
object WhyFacts {
    const val KEY_ARTIST_FINISHED = "artist_finished"
    const val KEY_TRACK_FINISHED = "track_finished"
    const val KEY_YEAR = "year"

    const val MAX_FACTS = 2

    /** A rate over fewer plays than this says nothing. */
    const val MIN_ARTIST_PLAYS = 3

    /** A track finished fewer times than this is not worth a line. */
    const val MIN_TRACK_FINISHED = 2

    /** A year outside this range is a parsing error, not a fact. */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    /** [a] and [b] are the arguments of the sentence; for a year, [a] is the year and [b] is 0. */
    class Fact(val key: String, val a: Int, val b: Int)

    /**
     * [artistPlays] is how many of the listener's last plays by the artist were looked at (at most 10),
     * [artistFinished] how many of those they finished; [trackFinished] is `completions` of the track
     * (0 when it is unknown or never finished); [year] is 0 when unknown.
     *
     * The order is the order of how much each tells: the artist rate, then the track, then the year; a
     * fact that is not true or not worth saying is left out, and at most [MAX_FACTS] are returned.
     */
    fun pick(artistPlays: Int, artistFinished: Int, trackFinished: Int, year: Int): List<Fact> {
        val out = ArrayList<Fact>(MAX_FACTS)
        if (artistPlays >= MIN_ARTIST_PLAYS && artistFinished in 0..artistPlays) {
            out.add(Fact(KEY_ARTIST_FINISHED, artistFinished, artistPlays))
        }
        if (out.size < MAX_FACTS && trackFinished >= MIN_TRACK_FINISHED) {
            out.add(Fact(KEY_TRACK_FINISHED, trackFinished, 0))
        }
        if (out.size < MAX_FACTS && year in MIN_YEAR..MAX_YEAR) {
            out.add(Fact(KEY_YEAR, year, 0))
        }
        return out
    }
}
