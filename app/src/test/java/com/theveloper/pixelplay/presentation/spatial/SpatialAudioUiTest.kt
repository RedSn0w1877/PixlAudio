package com.theveloper.pixelplay.presentation.spatial

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
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the Spatial audio UI shows for the holder's state (cloud-audio-spec §6.5): the status line of every case word
 * for word, the profiles and their one-liners, the player's button, the settings rows and Developer mode's values.
 * The words are read from `strings_spatial_ui.xml` itself, so a changed string fails here before it ships.
 */
class SpatialAudioUiTest {

    /** §6.5's table, word for word: one line per state, and none for NONE. */
    private val specStatusLines = mapOf(
        SpatialStatusLine.ON_PHONE_FOR_NOW to "On your phone for now. The cloud version takes about a minute.",
        SpatialStatusLine.WAITING_FOR_WIFI to "On your phone for now. The cloud version waits for Wi-Fi.",
        SpatialStatusLine.CLOUD_LIMIT_REACHED to "On your phone only. This month's cloud limit is reached.",
        SpatialStatusLine.GETTING_CLOUD_VERSION to "Getting the cloud version.",
        SpatialStatusLine.CLOUD_VERSION to "Playing the cloud version.",
        SpatialStatusLine.ON_PHONE_ONLY to "On your phone only. Turn on cloud renders for the full version.",
        SpatialStatusLine.CLOUD_FAILED to "The cloud version didn't work this time. Still playing on your phone.",
        SpatialStatusLine.HEADPHONES_ONLY to "Headphones only. Plug in to hear it.",
        SpatialStatusLine.PAUSED_FOR_SING to "Paused while Sing is on.",
    )

    /** §6.5's profile names and one-liners, in §1's order. */
    private val specProfiles = listOf(
        SpatialProfile.STUDIO to ("Studio" to "Clean and close, like good speakers in a quiet room."),
        SpatialProfile.WIDE to ("Wide" to "Instruments pushed out to the sides."),
        SpatialProfile.INTIMATE to ("Intimate" to "The voice right in front of you."),
        SpatialProfile.HALL to ("Concert Hall" to "A big room, a few rows back."),
        SpatialProfile.SURROUND to ("Surround" to "Sound beside and behind you."),
        SpatialProfile.NIGHT to ("Night" to "Softer and gentler for late listening."),
    )

    @Test
    fun `every status line shows the spec's words and NONE shows nothing`() {
        assertNull(SpatialAudioUi.statusLineRes(SpatialStatusLine.NONE))
        val cases = SpatialStatusLine.entries.filter { it != SpatialStatusLine.NONE }
        assertEquals(cases.toSet(), specStatusLines.keys, "every case has a row in the table")
        for (line in cases) {
            val res = SpatialAudioUi.statusLineRes(line)
            assertNotNull(res, line.name)
            assertEquals(specStatusLines.getValue(line), text(res!!), line.name)
        }
        // No two cases share a line.
        assertEquals(cases.size, cases.map { SpatialAudioUi.statusLineRes(it) }.toSet().size)
    }

    @Test
    fun `the profiles have the spec's names and one-liners in the spec's order`() {
        assertEquals(specProfiles.map { it.first }, SpatialProfile.entries)
        for ((profile, words) in specProfiles) {
            assertEquals(words.first, text(SpatialAudioUi.nameRes(profile)), profile.id)
            assertEquals(words.second, text(SpatialAudioUi.lineRes(profile)), profile.id)
        }
        assertEquals("Off", text(SpatialAudioUi.nameRes(null)))
        assertEquals("Spatial audio", text(R.string.spatial_ui_audio))
        assertEquals("Spatial plays a little quieter so the peaks stay clean.", text(R.string.spatial_ui_quieter_note))
    }

    @Test
    fun `the sheet lists Off then the six profiles, with exactly the stored one selected`() {
        val expectedKeys = listOf("off", "studio", "wide", "intimate", "hall", "surround", "night")
        for (stored in listOf<SpatialProfile?>(null) + SpatialProfile.entries) {
            val choices = SpatialAudioUi.choices(stored)
            assertEquals(expectedKeys, choices.map { it.key })
            assertEquals(listOf(stored), choices.filter { it.selected }.map { it.profile })
            for (choice in choices) {
                assertEquals(SpatialAudioUi.nameRes(choice.profile), choice.nameRes)
                assertEquals(SpatialAudioUi.lineRes(choice.profile), choice.lineRes)
            }
        }
        assertEquals("The normal stereo sound.", text(SpatialAudioUi.choices(null).first().lineRes))
    }

    @Test
    fun `the player's button names the stored profile and dims only for a block the sheet explains`() {
        assertEquals(SpatialButtonUi.OFF, SpatialButtonUi.of(null, null))
        assertFalse(SpatialButtonUi.OFF.isOn)
        assertEquals("Off", text(SpatialButtonUi.OFF.labelRes))
        // Off stays Off whatever the player reports.
        for (block in SpatialBlock.entries) {
            assertEquals(SpatialButtonUi.OFF, SpatialButtonUi.of(null, block), block.name)
        }

        val studio = SpatialButtonUi.of(SpatialProfile.STUDIO, null)
        assertTrue(studio.isOn)
        assertFalse(studio.heldBack)
        assertEquals("Studio", text(studio.labelRes))
        assertEquals("Concert Hall", text(SpatialButtonUi.of(SpatialProfile.HALL, null).labelRes))

        // "Headphones only. Plug in to hear it." and "Paused while Sing is on." explain a dimmed button.
        assertTrue(SpatialButtonUi.of(SpatialProfile.STUDIO, SpatialBlock.ROUTE).heldBack)
        assertTrue(SpatialButtonUi.of(SpatialProfile.STUDIO, SpatialBlock.SING).heldBack)
        // Another device and a rate the stage doesn't take have no line (§6.3, §6.5): the button stays as it is.
        assertFalse(SpatialButtonUi.of(SpatialProfile.STUDIO, SpatialBlock.REMOTE).heldBack)
        assertFalse(SpatialButtonUi.of(SpatialProfile.STUDIO, SpatialBlock.FORMAT).heldBack)
    }

    @Test
    fun `until the engine lands nothing is audible and the button only follows the stored pick`() {
        // SpatialPlayback's no-op reports SpatialAudible.NOTHING: no block, so a stored profile shows plainly.
        val ui = SpatialButtonUi.of(SpatialProfile.NIGHT, SpatialAudible.NOTHING.blockedBy)
        assertEquals(SpatialButtonUi(SpatialProfile.NIGHT, heldBack = false), ui)
        assertNull(SpatialAudioUi.fileInfo(SpatialAudible.NOTHING))
        assertTrue(SpatialDiagnostics.rows(SpatialAudible.NOTHING, SpatialRenditionStatus.NONE, null).isEmpty())
    }

    @Test
    fun `the settings rows follow the settings, the library's stats and the two switches`() {
        val stats = SpatialLibraryStats(songsReady = 12, bytesOnPhone = 1_503_238_553L) // 1.4 GB
        val on = SpatialSettingsUi.of(
            settings = SpatialSettings(profile = SpatialProfile.WIDE, cloud = true),
            stats = stats,
            cloudRendersOn = true,
            locale = Locale.US,
        )
        assertEquals(SpatialProfile.WIDE, on.profile)
        assertTrue(on.cloudChecked)
        assertFalse(on.masterOff)
        assertTrue(on.prepareAheadChecked)
        assertTrue(on.prepareAheadEnabled)
        assertEquals(12, on.songsReady)
        assertEquals("1.4", on.gbOnPhone)
        assertTrue(on.canRemove)
        assertEquals("Songs ready: %1\$d · %2\$s GB on this phone", text(R.string.spatial_ui_settings_songs_ready))

        // The toggle is on but cloud renders can't happen: Cloud Studio's master switch is off.
        val masterOff = SpatialSettingsUi.of(SpatialSettings(cloud = true), SpatialLibraryStats(), cloudRendersOn = false)
        assertTrue(masterOff.cloudChecked)
        assertTrue(masterOff.masterOff)

        // The toggle itself is off: no note about the master, and prepare ahead has nothing to do.
        val cloudOff = SpatialSettingsUi.of(
            SpatialSettings(cloud = false, prepareAhead = true),
            SpatialLibraryStats(),
            cloudRendersOn = false,
            locale = Locale.US,
        )
        assertFalse(cloudOff.cloudChecked)
        assertFalse(cloudOff.masterOff)
        assertTrue(cloudOff.prepareAheadChecked)
        assertFalse(cloudOff.prepareAheadEnabled)
        assertEquals(0, cloudOff.songsReady)
        assertEquals("0", cloudOff.gbOnPhone)
        assertFalse(cloudOff.canRemove)
        assertEquals("Cloud renders", text(R.string.spatial_ui_settings_cloud_title))
        assertEquals("Remove downloaded renders", text(R.string.spatial_ui_settings_remove))
    }

    @Test
    fun `gigabytes on the phone never read 0 for a store that holds something`() {
        assertEquals("0", SpatialFormat.gigabytes(0L, Locale.US))
        assertEquals("0", SpatialFormat.gigabytes(-5L, Locale.US))
        assertEquals("0.1", SpatialFormat.gigabytes(1L, Locale.US))
        assertEquals("0.1", SpatialFormat.gigabytes(40L * 1_048_576L, Locale.US))
        assertEquals("1.0", SpatialFormat.gigabytes(1_073_741_824L, Locale.US))
        assertEquals("1.5", SpatialFormat.gigabytes(1_610_612_736L, Locale.US))
        assertEquals("1,5", SpatialFormat.gigabytes(1_610_612_736L, Locale.GERMANY))
    }

    @Test
    fun `the storage budget moves over fixed stops inside the stored range`() {
        val stops = SpatialStorageStops.megabytes
        assertEquals(SpatialSettings.MIN_STORAGE_MB, stops.first())
        assertEquals(SpatialSettings.MAX_STORAGE_MB, stops.last())
        assertTrue(stops.zipWithNext().all { (a, b) -> a < b })
        assertTrue(SpatialSettings.DEFAULT_STORAGE_MB in stops)
        assertEquals(stops.size - 2, SpatialStorageStops.stepsBetween)
        for ((index, mb) in stops.withIndex()) {
            assertEquals(index, SpatialStorageStops.indexOf(mb))
            assertEquals(mb, SpatialStorageStops.megabytesAt(index.toFloat()))
        }
        // A budget set elsewhere lands on the nearest stop; a position off the ends is held to them.
        assertEquals(256, SpatialStorageStops.megabytesAt(SpatialStorageStops.indexOf(300).toFloat()))
        assertEquals(3072, SpatialStorageStops.megabytesAt(SpatialStorageStops.indexOf(3000).toFloat()))
        assertEquals(256, SpatialStorageStops.megabytesAt(-3f))
        assertEquals(65536, SpatialStorageStops.megabytesAt(99f))

        assertEquals(SpatialFormat.StorageLabel(megabytes = 512, gigabytes = null), SpatialFormat.storage(512, Locale.US))
        assertEquals(SpatialFormat.StorageLabel(megabytes = null, gigabytes = "3"), SpatialFormat.storage(3072, Locale.US))
        assertEquals(SpatialFormat.StorageLabel(megabytes = null, gigabytes = "64"), SpatialFormat.storage(65536, Locale.US))
        assertEquals(SpatialFormat.StorageLabel(megabytes = null, gigabytes = "1.5"), SpatialFormat.storage(1536, Locale.US))
    }

    @Test
    fun `the monthly limit offers the default, up to 200, and No limit`() {
        val dollars = SpatialCapOptions.dollars
        assertTrue(SpatialSettings.DEFAULT_MONTHLY_CAP_USD in dollars)
        assertEquals(SpatialSettings.MAX_MONTHLY_CAP_USD, dollars.last())
        assertTrue(dollars.zipWithNext().all { (a, b) -> a < b })

        val forDefault = SpatialCapOptions.choices(30)
        assertEquals(dollars + listOf<Int?>(null), forDefault)
        assertNull(SpatialCapOptions.choices(null).last())
        // A stored amount the list lacks is shown in its place, once.
        val odd = SpatialCapOptions.choices(42)
        assertEquals(listOf(5, 10, 15, 20, 30, 42, 50, 75, 100, 150, 200, null), odd)

        assertEquals("none", SpatialCapOptions.keyOf(null))
        assertEquals("30", SpatialCapOptions.keyOf(30))
        assertEquals(SpatialCapOptions.Pick(usd = null), SpatialCapOptions.pick("none"))
        assertEquals(SpatialCapOptions.Pick(usd = 30), SpatialCapOptions.pick("30"))
        assertEquals(SpatialCapOptions.Pick(usd = 200), SpatialCapOptions.pick("200"))
        assertNull(SpatialCapOptions.pick("201"))
        assertNull(SpatialCapOptions.pick("-1"))
        assertNull(SpatialCapOptions.pick("thirty"))
        for (choice in forDefault) {
            assertEquals(SpatialCapOptions.Pick(choice), SpatialCapOptions.pick(SpatialCapOptions.keyOf(choice)))
        }
        assertEquals("$%1\$d", text(R.string.spatial_ui_dev_cap_usd))
        assertEquals("No limit", text(R.string.spatial_ui_dev_cap_none))
    }

    @Test
    fun `songs a day ahead stay within the stored range`() {
        assertEquals(1f, SpatialDailyAhead.range.start)
        assertEquals(100f, SpatialDailyAhead.range.endInclusive)
        assertEquals(1, SpatialDailyAhead.songsAt(0f))
        assertEquals(25, SpatialDailyAhead.songsAt(25.4f))
        assertEquals(26, SpatialDailyAhead.songsAt(25.6f))
        assertEquals(100, SpatialDailyAhead.songsAt(250f))
    }

    @Test
    fun `developer diagnostics name the rendition, the block, the status and the render's facts`() {
        val onDevice = SpatialAudible(
            songId = "42",
            videoId = "J7p4bzqLvCw",
            rendition = SpatialRendition.ON_DEVICE,
            profile = SpatialProfile.HALL,
        )
        assertEquals(
            listOf(
                R.string.spatial_ui_dev_diag_rendition to "on device · hall",
                R.string.spatial_ui_dev_diag_status to "waiting for network",
                R.string.spatial_ui_dev_diag_render to "none",
            ),
            SpatialDiagnostics.rows(onDevice, SpatialRenditionStatus.WAITING_FOR_NETWORK, null).map { it.labelRes to it.value },
        )

        val blocked = SpatialAudible(songId = "42", videoId = "J7p4bzqLvCw", blockedBy = SpatialBlock.ROUTE)
        assertEquals(
            listOf(
                R.string.spatial_ui_dev_diag_rendition to "stereo",
                R.string.spatial_ui_dev_diag_held to "route",
                R.string.spatial_ui_dev_diag_status to "none",
                R.string.spatial_ui_dev_diag_render to "none",
            ),
            SpatialDiagnostics.rows(blocked, SpatialRenditionStatus.NONE, null).map { it.labelRes to it.value },
        )

        val render = SpatialRender(
            videoId = "J7p4bzqLvCw",
            profile = SpatialProfile.HALL,
            file = File("sp-hall.flac"),
            sampleRate = 48_000,
            samples = 9_600_000L,
            durationMs = 200_000L,
            headroomDb = 4.0f,
            sourceCodec = "opus",
            stemMode = "stems6",
            integratedLufs = -18.04f,
            sourceLufs = -14.0f,
        )
        val playing = onDevice.copy(rendition = SpatialRendition.CLOUD_RENDER)
        assertEquals(
            listOf(
                R.string.spatial_ui_dev_diag_rendition to "cloud render · hall",
                R.string.spatial_ui_dev_diag_status to "ready",
                R.string.spatial_ui_dev_diag_render to "hall",
                R.string.spatial_ui_dev_diag_codec to "opus",
                R.string.spatial_ui_dev_diag_stems to "stems6",
                R.string.spatial_ui_dev_diag_loudness to "-18.0 LUFS, source -14.0, headroom 4.0 dB",
                R.string.spatial_ui_dev_diag_rate to "48000 Hz",
            ),
            SpatialDiagnostics.rows(playing, SpatialRenditionStatus.READY, render, Locale.US).map { it.labelRes to it.value },
        )
        // A manifest without its optional facts still reads.
        val bare = render.copy(sourceCodec = null, stemMode = null, integratedLufs = null, sourceLufs = null)
        assertEquals(
            listOf("cloud render · hall", "ready", "hall", "?", "?", "? LUFS, headroom 4.0 dB", "48000 Hz"),
            SpatialDiagnostics.rows(playing, SpatialRenditionStatus.READY, bare, Locale.US).map { it.value },
        )
    }

    @Test
    fun `developer mode's file-info label says what spatial rendition plays`() {
        val base = SpatialAudible(songId = "42", videoId = "J7p4bzqLvCw")
        assertNull(SpatialAudioUi.fileInfo(base))
        val onDevice = SpatialAudioUi.fileInfo(base.copy(rendition = SpatialRendition.ON_DEVICE, profile = SpatialProfile.STUDIO))
        assertEquals(SpatialFileInfo(R.string.spatial_ui_file_info_on_device, SpatialProfile.STUDIO), onDevice)
        val render = SpatialAudioUi.fileInfo(base.copy(rendition = SpatialRendition.CLOUD_RENDER, profile = SpatialProfile.NIGHT))
        assertEquals(SpatialFileInfo(R.string.spatial_ui_file_info_render, SpatialProfile.NIGHT), render)
        // A spatial rendition without its profile says nothing rather than guess.
        assertNull(SpatialAudioUi.fileInfo(base.copy(rendition = SpatialRendition.ON_DEVICE)))
    }

    @Test
    fun `no user-visible Spatial audio text uses a word the feature must never use`() {
        val forbidden = listOf("lossless", "hi-res", "hi res", "dolby", "atmos", "360 reality", "claude", "anthropic", "binilyrics", "enhanced")
        val strings = uiStrings()
        assertTrue(strings.isNotEmpty())
        for ((name, value) in strings) {
            val lower = value.lowercase(Locale.US)
            for (word in forbidden) {
                assertFalse(word in lower, "$name contains \"$word\"")
            }
        }
    }

    @Test
    fun `every UI string is in the UI's own namespace`() {
        // ENGINE and CLOUD add their own strings files; one prefix each keeps the merged resources free of clashes.
        for (name in uiStrings().keys) {
            assertTrue(name.startsWith("spatial_ui_"), name)
        }
    }

    /** The English text of a string resource of strings_spatial_ui.xml, with Android's escapes undone. */
    private fun text(res: Int): String {
        val name = R.string::class.java.fields.firstOrNull { it.getInt(null) == res }?.name
        assertNotNull(name, "no R.string field for $res")
        val raw = uiStrings()[name]
        assertNotNull(raw, "$name is not in strings_spatial_ui.xml")
        return raw!!
    }

    private fun uiStrings(): Map<String, String> = cachedStrings

    private companion object {
        val cachedStrings: Map<String, String> by lazy {
            // Gradle runs unit tests from the module directory.
            val file = listOf(
                File("src/main/res/values/strings_spatial_ui.xml"),
                File("app/src/main/res/values/strings_spatial_ui.xml"),
            ).first { it.isFile }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
            }
        }

        fun unescape(value: String): String = value
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
    }
}
