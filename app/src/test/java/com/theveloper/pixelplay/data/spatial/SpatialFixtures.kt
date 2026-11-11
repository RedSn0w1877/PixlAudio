package com.theveloper.pixelplay.data.spatial

import java.io.File

/**
 * The worker's two generated files as the tests read them (cloud-audio-spec §7): the on-device table the app ships
 * (`app/src/main/assets/spatial_preview_v1.json`, read from the source tree because assets are not on the unit-test
 * classpath) and its golden file (`src/test/resources/spatial/spatial_preview_golden_v1.json`). Both are byte-identical
 * copies of `cloud/runpod-worker/schema/v1/spatial/` in the PixlAudio-iOS repo; a third copy sits in
 * `handoff/2026-11-10-plans/spatial/`.
 *
 * The test of the on-device stage against the golden file (§7, §8) reads both through here.
 */
internal object SpatialFixtures {
    private const val GOLDEN_RESOURCE = "spatial/spatial_preview_golden_v1.json"
    private const val GOLDEN_NAME = "spatial_preview_golden_v1.json"
    private const val HANDOFF_DIR = "handoff/2026-11-10-plans/spatial"

    /** The table in the app's assets, as bytes. Gradle runs unit tests from `app/`; an IDE may run them from the root. */
    fun previewTableBytes(): ByteArray = sourceFile("src/main/assets/${SpatialContract.PREVIEW_ASSET}").readBytes()

    /** The table in the app's assets, as text. */
    fun previewTable(): String = previewTableBytes().toString(Charsets.UTF_8)

    /** The golden file, as bytes. */
    fun goldenBytes(): ByteArray {
        val stream = requireNotNull(SpatialFixtures::class.java.classLoader?.getResourceAsStream(GOLDEN_RESOURCE)) {
            "missing fixture $GOLDEN_RESOURCE"
        }
        return stream.use { it.readBytes() }
    }

    /** The golden file, as text. */
    fun golden(): String = goldenBytes().toString(Charsets.UTF_8)

    /** The handoff folder's copy of the table, or null when the folder isn't there. */
    fun handoffPreviewTableBytes(): ByteArray? = handoffFile(SpatialContract.PREVIEW_ASSET)?.readBytes()

    /** The handoff folder's copy of the golden file, or null when the folder isn't there. */
    fun handoffGoldenBytes(): ByteArray? = handoffFile(GOLDEN_NAME)?.readBytes()

    private fun sourceFile(relativeToApp: String): File =
        listOf(File(relativeToApp), File("app/$relativeToApp")).firstOrNull { it.isFile }
            ?: error("missing $relativeToApp (looked from ${File("").absolutePath})")

    private fun handoffFile(name: String): File? =
        listOf(File("../$HANDOFF_DIR/$name"), File("$HANDOFF_DIR/$name")).firstOrNull { it.isFile }
}
