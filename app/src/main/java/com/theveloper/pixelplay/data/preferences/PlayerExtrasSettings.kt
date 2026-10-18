package com.theveloper.pixelplay.data.preferences

/**
 * Per-gesture switches (Settings > Playback > Gestures). Defaults are chosen so nothing that worked
 * before changes: gestures that would collide with an existing one (the mini player's swipe already
 * dismisses it, song rows sit in horizontally swipeable tabs) start off.
 */
data class PlayerGestureSettings(
    /** Swipe the full player artwork sideways to change song. */
    val playerSwipeSkip: Boolean = true,
    /** Double-tap the full player artwork to like / unlike. */
    val playerDoubleTapLike: Boolean = true,
    /** Long-press the full player artwork for the quick-actions sheet. */
    val playerLongPressActions: Boolean = true,
    /** Swipe the mini player sideways to skip (replaces swipe-to-dismiss, so off by default). */
    val miniSwipeSkip: Boolean = false,
    /** Swipe the mini player up to open the full player (always available; kept for the switch). */
    val miniSwipeUpOpen: Boolean = true,
    /** Long-press the mini player for the quick-actions sheet. */
    val miniLongPressActions: Boolean = true,
    /** Swipe a song row for Play next / Add to queue (off by default: rows live in swipeable tabs). */
    val rowSwipeQueue: Boolean = false,
)

enum class PlayerGesture {
    PLAYER_SWIPE_SKIP,
    PLAYER_DOUBLE_TAP_LIKE,
    PLAYER_LONG_PRESS,
    MINI_SWIPE_SKIP,
    MINI_SWIPE_UP,
    MINI_LONG_PRESS,
    ROW_SWIPE_QUEUE,
}

/** What the phone's vibration follows (Settings > Playback > Music haptics). */
enum class MusicHapticsFollows(val key: String) {
    /** Taps on kick and snare plus a soft rumble under the bass. */
    EVERYTHING("everything"),
    /** Taps only. */
    BEAT("beat"),
    /** Kick taps plus a rumble. */
    BASS("bass");

    companion object {
        fun fromKey(key: String?): MusicHapticsFollows =
            entries.firstOrNull { it.key == key } ?: EVERYTHING
    }
}

/** Which engine produces the vibration. */
enum class MusicHapticsEngineChoice(val key: String) {
    /** The app listens to the audio and drives the motor itself (honours Strength and Follows). */
    APP("app"),

    /** Android HapticGenerator on the player audio session (experimental). */
    SYSTEM("system");

    companion object {
        fun fromKey(key: String?): MusicHapticsEngineChoice = entries.firstOrNull { it.key == key } ?: APP
    }
}

data class MusicHapticsSettings(
    val enabled: Boolean = false,
    val strengthPercent: Int = DEFAULT_STRENGTH,
    val follows: MusicHapticsFollows = MusicHapticsFollows.EVERYTHING,
    val engine: MusicHapticsEngineChoice = MusicHapticsEngineChoice.APP,
) {
    companion object {
        const val MIN_STRENGTH = 20
        const val MAX_STRENGTH = 200
        const val DEFAULT_STRENGTH = 100
    }
}
