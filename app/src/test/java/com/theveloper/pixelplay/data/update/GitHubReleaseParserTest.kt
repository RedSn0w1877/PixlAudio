package com.theveloper.pixelplay.data.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubReleaseParserTest {
    private val pixelAbis = listOf("arm64-v8a")
    private val olderPhoneAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun asset(name: String, size: Long = 100L, digest: String? = null) = GitHubReleaseAssetDto(
        name = name,
        size = size,
        downloadUrl = "https://github.com/RedSn0w1877/PixlAudio/releases/download/x/$name",
        digest = digest,
    )

    private fun release(tag: String, vararg assets: GitHubReleaseAssetDto, draft: Boolean = false, prerelease: Boolean = true) =
        GitHubReleaseDto(
            tagName = tag,
            body = "Notes for $tag",
            draft = draft,
            prerelease = prerelease,
            htmlUrl = "https://github.com/RedSn0w1877/PixlAudio/releases/tag/$tag",
            assets = assets.toList(),
        )

    @Test
    fun `offers the newest release and the APK for this phone's ABI`() {
        val releases = listOf(
            release("v0.7.7-beta3", asset("PixlAudio-v0.7.7-beta3-vc14-armeabi-v7a.apk"), asset("PixlAudio-v0.7.7-beta3-vc14-arm64-v8a.apk", digest = "sha256:ABCDEF")),
            release("v0.7.8-beta4", asset("PixlAudio-v0.7.8-beta4-vc15-armeabi-v7a.apk"), asset("PixlAudio-v0.7.8-beta4-vc15-arm64-v8a.apk", size = 170_000_000L)),
        )

        val update = GitHubReleaseParser.pickUpdate(releases, installedVersionCode = 13, supportedAbis = pixelAbis)

        requireNotNull(update)
        assertEquals(15L, update.versionCode)
        assertEquals("0.7.8-beta4", update.versionName)
        assertEquals("PixlAudio-v0.7.8-beta4-vc15-arm64-v8a.apk", update.apkFileName)
        assertEquals(170_000_000L, update.sizeBytes)
        assertEquals("Notes for v0.7.8-beta4", update.releaseNotes)
    }

    @Test
    fun `nothing is offered when the installed version is already current`() {
        val releases = listOf(release("v0.7.6-beta2", asset("PixlAudio-v0.7.6-beta2-vc13-arm64-v8a.apk")))

        assertNull(GitHubReleaseParser.pickUpdate(releases, installedVersionCode = 13, supportedAbis = pixelAbis))
    }

    @Test
    fun `drafts and APKs this phone cannot run are ignored`() {
        val releases = listOf(
            release("v0.8.0", asset("PixlAudio-v0.8.0-vc20-arm64-v8a.apk"), draft = true),
            release("v0.7.9", asset("PixlAudio-v0.7.9-vc16-x86_64.apk")),
        )

        assertNull(GitHubReleaseParser.pickUpdate(releases, installedVersionCode = 13, supportedAbis = pixelAbis))
    }

    @Test
    fun `files without a version code in the name are not guessed at`() {
        val releases = listOf(release("v0.7.7", asset("app-arm64-v8a-release.apk"), asset("PixlAudio.apk")))

        assertNull(GitHubReleaseParser.pickUpdate(releases, installedVersionCode = 1, supportedAbis = pixelAbis))
    }

    @Test
    fun `a matching ABI wins over the universal APK, which is still used as a fallback`() {
        val both = listOf(release("v1.0", asset("PixlAudio-v1.0-vc30-universal.apk"), asset("PixlAudio-v1.0-vc30-armeabi-v7a.apk")))
        val universalOnly = listOf(release("v1.0", asset("PixlAudio-v1.0-vc30-universal.apk")))

        assertEquals(
            "PixlAudio-v1.0-vc30-armeabi-v7a.apk",
            GitHubReleaseParser.pickUpdate(both, 13, olderPhoneAbis)?.apkFileName,
        )
        assertEquals(
            "PixlAudio-v1.0-vc30-universal.apk",
            GitHubReleaseParser.pickUpdate(universalOnly, 13, pixelAbis)?.apkFileName,
        )
    }

    @Test
    fun `GitHub's sha256 digest is normalised and missing digests stay null`() {
        val withDigest = listOf(release("v1.0", asset("PixlAudio-v1.0-vc30-arm64-v8a.apk", digest = "sha256:ABCDEF0123")))
        val withoutDigest = listOf(release("v1.0", asset("PixlAudio-v1.0-vc30-arm64-v8a.apk")))

        assertEquals("abcdef0123", GitHubReleaseParser.pickUpdate(withDigest, 13, pixelAbis)?.sha256)
        assertNull(GitHubReleaseParser.pickUpdate(withoutDigest, 13, pixelAbis)?.sha256)
    }
}
