package com.theveloper.pixelplay.data.service

object MusicNotificationProvider {
    const val CUSTOM_COMMAND_CLOSE_PLAYER = "com.theveloper.pixelplay.CLOSE_PLAYER"
    const val CUSTOM_COMMAND_TOGGLE_SHUFFLE = "com.theveloper.pixelplay.TOGGLE_SHUFFLE"
    const val CUSTOM_COMMAND_SHUFFLE_ON = "com.theveloper.pixelplay.SHUFFLE_ON"
    const val CUSTOM_COMMAND_SHUFFLE_OFF = "com.theveloper.pixelplay.SHUFFLE_OFF"
    const val CUSTOM_COMMAND_SET_SHUFFLE_STATE = "com.theveloper.pixelplay.SET_SHUFFLE_STATE"
    const val EXTRA_SHUFFLE_ENABLED = "com.theveloper.pixelplay.extra.SHUFFLE_ENABLED"
    const val CUSTOM_COMMAND_CYCLE_REPEAT_MODE = "com.theveloper.pixelplay.CYCLE_REPEAT"
    const val CUSTOM_COMMAND_LIKE = "com.theveloper.pixelplay.LIKE"
    const val CUSTOM_COMMAND_SET_FAVORITE_STATE = "com.theveloper.pixelplay.SET_FAVORITE_STATE"
    const val EXTRA_FAVORITE_ENABLED = "com.theveloper.pixelplay.extra.FAVORITE_ENABLED"
    const val CUSTOM_COMMAND_COUNTED_PLAY = "com.theveloper.pixelplay.COUNTED_PLAY"
    const val CUSTOM_COMMAND_CANCEL_COUNTED_PLAY = "com.theveloper.pixelplay.CANCEL_COUNTED_PLAY"
    const val CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION = "com.theveloper.pixelplay.SET_SLEEP_TIMER_DURATION"
    const val CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK = "com.theveloper.pixelplay.SET_SLEEP_TIMER_END_OF_TRACK"
    const val CUSTOM_COMMAND_CANCEL_SLEEP_TIMER = "com.theveloper.pixelplay.CANCEL_SLEEP_TIMER"
    const val EXTRA_SLEEP_TIMER_MINUTES = "com.theveloper.pixelplay.extra.SLEEP_TIMER_MINUTES"
    const val EXTRA_END_OF_TRACK_ENABLED = "com.theveloper.pixelplay.extra.END_OF_TRACK_ENABLED"
    /** Swaps the current item's audio source for a TAIS Studio render. Extras: [EXTRA_STUDIO_AUDIO_PATH] (absolute file path, or absent/null to revert to the original source). Handled server-side because [MediaSession.Callback.onSetMediaItems] always re-resolves items by mediaId, discarding any URI a client sets on the MediaItem it sends. */
    const val CUSTOM_COMMAND_SET_STUDIO_AUDIO_OVERRIDE = "com.theveloper.pixelplay.SET_STUDIO_AUDIO_OVERRIDE"
    const val EXTRA_STUDIO_AUDIO_PATH = "com.theveloper.pixelplay.extra.STUDIO_AUDIO_PATH"
}
