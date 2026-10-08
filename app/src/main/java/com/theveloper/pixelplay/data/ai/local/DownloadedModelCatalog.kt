package com.theveloper.pixelplay.data.ai.local

/** A model file the app can download, pinned to one revision, size and checksum. */
data class DownloadedModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String,
)

/**
 * The downloadable model behind Settings › AI features › "Use downloaded AI model".
 *
 * Gemma 4 E2B-it in LiteRT-LM's generic `.litertlm` build (CPU + GPU), from Google's own
 * `litert-community` organisation on Hugging Face: Apache-2.0 and ungated (no login, no
 * click-through licence), checked against the HF API on 2026-10-08. The URL pins the repository
 * revision because the file has been re-uploaded before (for speculative decoding); the size and
 * SHA-256 are the LFS values of that revision, and a download that doesn't match both is deleted.
 *
 * It can't be mirrored to the project's own GitHub release: release assets must be under 2 GiB.
 * The Tensor G5 NPU build (3.1 GB, `Backend.GOOGLE_TENSOR`) is out of scope for now.
 */
object DownloadedModelCatalog {
    private const val REVISION = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"

    val GEMMA_4_E2B = DownloadedModelSpec(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REVISION/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        licenseName = "Apache License 2.0",
        licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    )

    val current: DownloadedModelSpec get() = GEMMA_4_E2B
}
