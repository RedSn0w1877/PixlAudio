package com.theveloper.pixelplay.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    /** "sha256:<hex>". GitHub lo calcula al subir el archivo; puede faltar en releases antiguas. */
    val digest: String? = null,
)

/** A version newer than the installed one, with the APK that fits this phone. */
data class AvailableUpdate(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkFileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val releaseUrl: String,
)

/**
 * Picks the update to offer from a GitHub releases listing.
 *
 * The version code is read from the **asset name** (`…-vc14-arm64-v8a.apk`, which is what the
 * `prepareGitHubRelease` Gradle task produces) rather than from the tag or the release text: a
 * tag like `v0.7.7-beta3` cannot be compared reliably, and it lets us decide "is this newer?"
 * before downloading ~170 MB. The downloaded APK's real version code is verified again before
 * installing, so a mislabeled file cannot slip through.
 */
object GitHubReleaseParser {
    private val APK_ASSET = Regex(
        """-vc(\d+)-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)\.apk$""",
        RegexOption.IGNORE_CASE,
    )

    fun pickUpdate(
        releases: List<GitHubReleaseDto>,
        installedVersionCode: Long,
        supportedAbis: List<String>,
    ): AvailableUpdate? = releases
        .asSequence()
        .filterNot { it.draft }
        .mapNotNull { toUpdate(it, supportedAbis) }
        .filter { it.versionCode > installedVersionCode }
        .maxByOrNull { it.versionCode }

    private fun toUpdate(release: GitHubReleaseDto, supportedAbis: List<String>): AvailableUpdate? {
        var best: GitHubReleaseAssetDto? = null
        var bestVersionCode = 0L
        var bestRank = Int.MAX_VALUE
        for (asset in release.assets) {
            if (asset.downloadUrl.isBlank()) continue
            val match = APK_ASSET.find(asset.name) ?: continue
            val versionCode = match.groupValues[1].toLongOrNull() ?: continue
            val rank = abiRank(match.groupValues[2], supportedAbis) ?: continue
            if (versionCode > bestVersionCode || (versionCode == bestVersionCode && rank < bestRank)) {
                best = asset
                bestVersionCode = versionCode
                bestRank = rank
            }
        }
        val asset = best ?: return null
        return AvailableUpdate(
            versionCode = bestVersionCode,
            versionName = versionNameOf(release),
            releaseNotes = release.body.orEmpty().trim(),
            apkUrl = asset.downloadUrl,
            apkFileName = asset.name,
            sizeBytes = asset.size,
            sha256 = asset.digest
                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.lowercase(),
            releaseUrl = release.htmlUrl,
        )
    }

    /**
     * Position in the phone's ABI list (`Build.SUPPORTED_ABIS`, preferred first). A universal APK
     * runs anywhere but is ranked last; an ABI the phone cannot run returns null.
     */
    internal fun abiRank(abi: String, supportedAbis: List<String>): Int? {
        if (abi.equals("universal", ignoreCase = true)) return supportedAbis.size
        val index = supportedAbis.indexOfFirst { it.equals(abi, ignoreCase = true) }
        return if (index >= 0) index else null
    }

    private fun versionNameOf(release: GitHubReleaseDto): String {
        val fromTag = release.tagName.trim().removePrefix("v").removePrefix("V")
        return fromTag.ifBlank { release.name.orEmpty().trim() }
    }
}
