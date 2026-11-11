package com.theveloper.pixelplay.presentation.spatial

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialBlock
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRendition
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.data.spatial.SpatialStatusLine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

// What the Spatial audio UI shows (cloud-audio-spec §6.5; "§" below is a section of it), as pure functions of the
// state SpatialAudioStateHolder exposes, so every mapping is unit-tested (SpatialAudioUiTest) and the composables
// only lay it out. No rule lives here: what plays and why comes from the contract in data/spatial.

/** The words the UI shows for profiles and status lines (§6.5): one string resource each. */
object SpatialAudioUi {

    /** The sheet's choices, in order: Off, then the six profiles in the spec's order (§6.5). */
    val choiceOrder: ImmutableList<SpatialProfile?> = (listOf<SpatialProfile?>(null) + SpatialProfile.entries).toImmutableList()

    /** A profile's name (§6.5; `hall` is "Concert Hall"), or "Off" for null. */
    @StringRes
    fun nameRes(profile: SpatialProfile?): Int = when (profile) {
        null -> R.string.spatial_ui_off
        SpatialProfile.STUDIO -> R.string.spatial_ui_profile_studio
        SpatialProfile.WIDE -> R.string.spatial_ui_profile_wide
        SpatialProfile.INTIMATE -> R.string.spatial_ui_profile_intimate
        SpatialProfile.HALL -> R.string.spatial_ui_profile_hall
        SpatialProfile.SURROUND -> R.string.spatial_ui_profile_surround
        SpatialProfile.NIGHT -> R.string.spatial_ui_profile_night
    }

    /** A profile's one-liner (§6.5), or Off's own ("The normal stereo sound."). */
    @StringRes
    fun lineRes(profile: SpatialProfile?): Int = when (profile) {
        null -> R.string.spatial_ui_off_line
        SpatialProfile.STUDIO -> R.string.spatial_ui_profile_studio_line
        SpatialProfile.WIDE -> R.string.spatial_ui_profile_wide_line
        SpatialProfile.INTIMATE -> R.string.spatial_ui_profile_intimate_line
        SpatialProfile.HALL -> R.string.spatial_ui_profile_hall_line
        SpatialProfile.SURROUND -> R.string.spatial_ui_profile_surround_line
        SpatialProfile.NIGHT -> R.string.spatial_ui_profile_night_line
    }

    /** The sheet's status line (§6.5: "Every state has one"), or null for [SpatialStatusLine.NONE]: no line at all. */
    @StringRes
    fun statusLineRes(line: SpatialStatusLine): Int? = when (line) {
        SpatialStatusLine.NONE -> null
        SpatialStatusLine.ON_PHONE_FOR_NOW -> R.string.spatial_ui_status_on_phone_for_now
        SpatialStatusLine.WAITING_FOR_WIFI -> R.string.spatial_ui_status_waiting_for_wifi
        SpatialStatusLine.CLOUD_LIMIT_REACHED -> R.string.spatial_ui_status_cloud_limit_reached
        SpatialStatusLine.GETTING_CLOUD_VERSION -> R.string.spatial_ui_status_getting_cloud_version
        SpatialStatusLine.CLOUD_VERSION -> R.string.spatial_ui_status_cloud_version
        SpatialStatusLine.ON_PHONE_ONLY -> R.string.spatial_ui_status_on_phone_only
        SpatialStatusLine.CLOUD_FAILED -> R.string.spatial_ui_status_cloud_failed
        SpatialStatusLine.HEADPHONES_ONLY -> R.string.spatial_ui_status_headphones_only
        SpatialStatusLine.PAUSED_FOR_SING -> R.string.spatial_ui_status_paused_for_sing
    }

    /** The sheet's rows for the stored [selected] profile (null is Off): Off first, exactly one selected. */
    fun choices(selected: SpatialProfile?): ImmutableList<SpatialChoice> =
        choiceOrder.map { SpatialChoice(profile = it, selected = it == selected) }.toImmutableList()

    /**
     * Developer mode's file-info label in the full player while a spatial rendition is audible: the string and the
     * profile it names, or null while the item plays in stereo.
     */
    fun fileInfo(audible: SpatialAudible): SpatialFileInfo? {
        val profile = audible.profile ?: return null
        return when (audible.rendition) {
            SpatialRendition.STEREO -> null
            SpatialRendition.ON_DEVICE -> SpatialFileInfo(R.string.spatial_ui_file_info_on_device, profile)
            SpatialRendition.CLOUD_RENDER -> SpatialFileInfo(R.string.spatial_ui_file_info_render, profile)
        }
    }
}

/** One row of the sheet's list (§6.5): a profile, or Off when [profile] is null. */
@Immutable
data class SpatialChoice(val profile: SpatialProfile?, val selected: Boolean) {
    /** A stable key for a lazy list: the wire id, or `off`. */
    val key: String get() = profile?.id ?: SpatialProfile.OFF_ID

    @get:StringRes
    val nameRes: Int get() = SpatialAudioUi.nameRes(profile)

    @get:StringRes
    val lineRes: Int get() = SpatialAudioUi.lineRes(profile)
}

/** What Developer mode's file-info label adds while Spatial audio is audible. */
@Immutable
data class SpatialFileInfo(@StringRes val res: Int, val profile: SpatialProfile)

/**
 * The full player's Spatial audio button (§6.5 "an entry in the full player"): icon only while Spatial audio is off,
 * like the output pill on the phone's own speaker; the profile's name while one is selected. [heldBack] is a selected
 * profile the listener can't hear right now for a reason the sheet's status line explains (the route rule, or Sing):
 * the button keeps the name but drops its emphasis.
 */
@Immutable
data class SpatialButtonUi(val profile: SpatialProfile?, val heldBack: Boolean) {
    val isOn: Boolean get() = profile != null

    /** "Off" or the profile's name: the button's text when on, and TalkBack's in both cases. */
    @get:StringRes
    val labelRes: Int get() = SpatialAudioUi.nameRes(profile)

    companion object {
        val OFF = SpatialButtonUi(profile = null, heldBack = false)

        /**
         * The button for the stored [profile] and what holds it back ([SpatialAudible.blockedBy]). Only the blocks
         * with a status line of their own dim it: [SpatialBlock.ROUTE] ("Headphones only. Plug in to hear it.") and
         * [SpatialBlock.SING] ("Paused while Sing is on."). [SpatialBlock.REMOTE] and [SpatialBlock.FORMAT] have no
         * line (§6.3, §6.5), so the button doesn't hint at something the sheet won't explain.
         */
        fun of(profile: SpatialProfile?, blockedBy: SpatialBlock?): SpatialButtonUi = SpatialButtonUi(
            profile = profile,
            heldBack = profile != null && (blockedBy == SpatialBlock.ROUTE || blockedBy == SpatialBlock.SING),
        )
    }
}

/**
 * Settings › Playback › Sound, the Spatial audio rows (§6.5): the profile, "Cloud renders", "Prepare ahead", and
 * "Songs ready: N · X GB on this phone" with "Remove downloaded renders".
 */
@Immutable
data class SpatialSettingsUi(
    /** The selected profile, null for Off. */
    val profile: SpatialProfile?,
    /** "Cloud renders": `spatial.cloud` only (§6.1 "Two switches"; the master stays on the Cloud processing screen). */
    val cloudChecked: Boolean,
    /**
     * The toggle is on but cloud renders can't happen: Cloud Studio's master switch is off (§6.1 "Two switches"). The
     * section then says so and links to the master, rather than look switched on and do nothing.
     */
    val masterOff: Boolean,
    /** "Prepare ahead" (`spatial.prepareAhead`). */
    val prepareAheadChecked: Boolean,
    /** "Prepare ahead" can only do something while "Cloud renders" is on (§6.4 sends cloud work). */
    val prepareAheadEnabled: Boolean,
    /** "Songs ready: N". */
    val songsReady: Int,
    /** "X" of "X GB on this phone". */
    val gbOnPhone: String,
    /** "Remove downloaded renders" has something to remove. */
    val canRemove: Boolean,
) {
    companion object {
        fun of(
            settings: SpatialSettings,
            stats: SpatialLibraryStats,
            cloudRendersOn: Boolean,
            locale: Locale = Locale.getDefault(),
        ): SpatialSettingsUi = SpatialSettingsUi(
            profile = settings.profile,
            cloudChecked = settings.cloud,
            masterOff = settings.cloud && !cloudRendersOn,
            prepareAheadChecked = settings.prepareAhead,
            prepareAheadEnabled = settings.cloud,
            songsReady = stats.songsReady.coerceAtLeast(0),
            gbOnPhone = SpatialFormat.gigabytes(stats.bytesOnPhone, locale),
            canRemove = stats.songsReady > 0 || stats.bytesOnPhone > 0L,
        )
    }
}

/** Numbers as the Spatial audio rows print them. Binary units, like `cloudAudio.storageMB` (1 MB = 1,048,576 bytes). */
object SpatialFormat {
    private const val BYTES_PER_GB = 1_073_741_824.0
    private const val MB_PER_GB = 1024

    /**
     * "X" of "X GB on this phone": "0" for nothing, one decimal otherwise, and never "0.0" for a store that holds
     * something (anything under 0.1 GB reads 0.1).
     */
    fun gigabytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0L) return "0"
        val gb = (bytes / BYTES_PER_GB).coerceAtLeast(0.1)
        return String.format(locale, "%.1f", gb)
    }

    /**
     * A storage budget for its row: whole megabytes under 1 GB ([StorageLabel.megabytes]), else gigabytes with one
     * decimal only when it isn't whole ("1.5", "3", "64").
     */
    fun storage(megabytes: Int, locale: Locale = Locale.getDefault()): StorageLabel {
        if (megabytes < MB_PER_GB) return StorageLabel(megabytes = megabytes, gigabytes = null)
        val text = if (megabytes % MB_PER_GB == 0) {
            (megabytes / MB_PER_GB).toString()
        } else {
            String.format(locale, "%.1f", megabytes / MB_PER_GB.toDouble())
        }
        return StorageLabel(megabytes = null, gigabytes = text)
    }

    /** One of the two: "512 MB" or "3 GB". */
    @Immutable
    data class StorageLabel(val megabytes: Int?, val gigabytes: String?)
}

/**
 * Developer mode's storage budget slider (§6.1 `cloudAudio.storageMB`, kept within 256..65536): fixed stops from
 * 256 MB to 64 GB, so the slider moves in steps that mean something at both ends.
 */
object SpatialStorageStops {
    val megabytes: ImmutableList<Int> = persistentListOf(
        256, 512, 1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768, 49152, 65536,
    )

    val lastIndex: Int get() = megabytes.lastIndex

    /** Stops strictly between the slider's two ends (Material's `steps`). */
    val stepsBetween: Int get() = megabytes.size - 2

    /** The stop for a stored budget: the nearest one, so a value set elsewhere still lands somewhere sensible. */
    fun indexOf(storedMb: Int): Int = megabytes.indices.minBy { abs(megabytes[it] - storedMb) }

    /** The budget at a slider position. */
    fun megabytesAt(position: Float): Int = megabytes[position.roundToInt().coerceIn(0, megabytes.lastIndex)]
}

/**
 * Developer mode's monthly limit for library jobs (§6.1 `cloudAudio.monthlyCapUSD`: default 30, at most 200, or
 * "No limit"). A choice list rather than a slider, because "No limit" is not a number.
 */
object SpatialCapOptions {
    /** The amounts offered, in whole dollars. */
    val dollars: ImmutableList<Int> = persistentListOf(5, 10, 15, 20, 30, 50, 75, 100, 150, SpatialSettings.MAX_MONTHLY_CAP_USD)

    /** The key of "No limit" in the choice list. */
    const val NO_LIMIT_KEY = "none"

    /** The choice list's key for a stored cap (null is "No limit"). */
    fun keyOf(usd: Int?): String = usd?.toString() ?: NO_LIMIT_KEY

    /**
     * The choices to show for a stored cap, in order: the amounts, the stored one among them if it is not one of
     * them (so the row never shows a value the list lacks), then "No limit" (null).
     */
    fun choices(stored: Int?): ImmutableList<Int?> {
        val amounts = if (stored != null && stored !in dollars) (dollars + stored).sorted() else dollars
        return (amounts + listOf<Int?>(null)).toImmutableList()
    }

    /** What picking [key] stores: [Pick.usd] null for "No limit"; null for a key that is not a choice. */
    fun pick(key: String): Pick? = when (key) {
        NO_LIMIT_KEY -> Pick(usd = null)
        else -> key.toIntOrNull()
            ?.takeIf { it in 0..SpatialSettings.MAX_MONTHLY_CAP_USD }
            ?.let { Pick(usd = it) }
    }

    @Immutable
    data class Pick(val usd: Int?)
}

/** Developer mode's "songs a day ahead" slider (§6.1 `cloudAudio.dailyAhead`, kept within 1..100). */
object SpatialDailyAhead {
    val range: ClosedFloatingPointRange<Float> =
        SpatialSettings.MIN_DAILY_AHEAD.toFloat()..SpatialSettings.MAX_DAILY_AHEAD.toFloat()

    fun songsAt(position: Float): Int = SpatialSettings.clampDailyAhead(position.roundToInt())
}

/** One line of Developer mode's per-song diagnostics: a label from the strings and a technical value. */
@Immutable
data class SpatialDiagnosticRow(@StringRes val labelRes: Int, val value: String)

/**
 * Developer mode's per-song diagnostics for the playing song (§6.5: rendition, source codec, stem mode, loudness and
 * sample rate). The values are technical words, not translated, like the player's file-info label.
 */
object SpatialDiagnostics {
    /** The rows for what is audible, its status under the selected profile and its local render; empty when nothing is loaded. */
    fun rows(
        audible: SpatialAudible,
        status: SpatialRenditionStatus,
        render: SpatialRender?,
        locale: Locale = Locale.US,
    ): ImmutableList<SpatialDiagnosticRow> {
        if (audible.songId == null) return persistentListOf()
        val rows = ArrayList<SpatialDiagnosticRow>(8)
        rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_rendition, rendition(audible))
        audible.blockedBy?.let { rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_held, block(it)) }
        rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_status, words(status.name))
        if (render == null) {
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_render, NONE)
        } else {
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_render, render.profile.id)
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_codec, render.sourceCodec ?: UNKNOWN)
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_stems, render.stemMode ?: UNKNOWN)
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_loudness, loudness(render, locale))
            rows += SpatialDiagnosticRow(R.string.spatial_ui_dev_diag_rate, "${render.sampleRate} Hz")
        }
        return rows.toImmutableList()
    }

    private const val NONE = "none"
    private const val UNKNOWN = "?"

    private fun rendition(audible: SpatialAudible): String {
        val profile = audible.profile?.id
        return when (audible.rendition) {
            SpatialRendition.STEREO -> "stereo"
            SpatialRendition.ON_DEVICE -> if (profile != null) "on device · $profile" else "on device"
            SpatialRendition.CLOUD_RENDER -> if (profile != null) "cloud render · $profile" else "cloud render"
        }
    }

    private fun block(block: SpatialBlock): String = when (block) {
        SpatialBlock.ROUTE -> "route"
        SpatialBlock.SING -> "sing"
        SpatialBlock.REMOTE -> "another device"
        SpatialBlock.FORMAT -> "sample rate"
    }

    private fun words(name: String): String = name.lowercase(Locale.US).replace('_', ' ')

    private fun loudness(render: SpatialRender, locale: Locale): String {
        val integrated = render.integratedLufs?.let { String.format(locale, "%.1f LUFS", it) } ?: "$UNKNOWN LUFS"
        val source = render.sourceLufs?.let { String.format(locale, ", source %.1f", it) }.orEmpty()
        val headroom = String.format(locale, ", headroom %.1f dB", render.headroomDb)
        return integrated + source + headroom
    }
}
