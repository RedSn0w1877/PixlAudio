package com.theveloper.pixelplay.data.cloudstudio

// Settings › Developer options › Experimental › Cloud processing (design §3.5 E, §7.2), ported from the iOS app's
// `CloudConfig.swift`: what the person types, checked and normalised before anything is signed or sent. The six
// fields, in screen order: Endpoint ID, RunPod key (Restricted), R2 endpoint or account ID, bucket, access key ID,
// secret access key.

/** The values of the Cloud processing fields. `toString` never prints a key. */
class CloudConfigInput(
    val endpointId: String,
    val runpodKey: String,
    /** `https://<account-id>.r2.cloudflarestorage.com`, or the bare 32-hex account ID. */
    val endpoint: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    val trimmedEndpointId: String get() = endpointId.trim()
    val trimmedRunpodKey: String get() = runpodKey.trim()
    val trimmedBucket: String get() = bucket.trim()

    /** The S3 key pair. */
    val credentials: S3Credentials get() = S3Credentials(accessKeyId.trim(), secretAccessKey.trim())

    /** Where the bucket lives (path-style, region `auto`), or null when the endpoint or bucket is unusable. */
    val location: S3Location?
        get() {
            val normalized = CloudConfig.normalizedEndpoint(endpoint) ?: return null
            return S3Location(normalized, trimmedBucket).takeIf { it.isValid }
        }

    /** RunPod is filled in well enough to call. */
    val hasRunPod: Boolean get() = RunPodJobsClient.isValidEndpointId(trimmedEndpointId) && trimmedRunpodKey.isNotEmpty()

    /** Storage is filled in well enough to sign. */
    val hasStorage: Boolean get() = location != null && credentials.isComplete

    val isComplete: Boolean get() = hasRunPod && hasStorage

    private val identity: List<String>
        get() = listOf(trimmedEndpointId, trimmedRunpodKey, endpoint.trim(), trimmedBucket, accessKeyId.trim(), secretAccessKey.trim())

    override fun equals(other: Any?): Boolean = other is CloudConfigInput && other.identity == identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "CloudConfigInput(endpointId=$trimmedEndpointId, bucket=$trimmedBucket, keys=…)"

    companion object {
        /** No fields at all (built-in keys chosen but not opened, or unusable): never complete, so nothing is sent. */
        val EMPTY = CloudConfigInput("", "", "", "", "", "")
    }
}

object CloudConfig {
    /** The bucket name the owner creates (design §3.5 D7). */
    const val DEFAULT_BUCKET = "pixl-cloud-studio"

    /** R2's S3 endpoint for a Cloudflare account. */
    fun r2Endpoint(accountId: String): String = "https://${accountId.lowercase()}.r2.cloudflarestorage.com"

    /** A Cloudflare account ID: 32 hex characters. */
    fun isAccountId(text: String): Boolean =
        text.length == 32 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * The endpoint for what was typed: a bare account ID becomes R2's URL; `https://host[:port][/…]` keeps only its
     * scheme and host; a bare `host` gets `https://`. Null for anything else (plain `http://`, spaces, an empty field).
     */
    fun normalizedEndpoint(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (isAccountId(trimmed)) return r2Endpoint(trimmed)
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://")) return null
        val candidate = if (lower.startsWith("https://")) trimmed else "https://$trimmed"
        // An explicit :443 is dropped: OkHttp leaves the default port out of the Host header, so a signature over
        // "host:443" would never match.
        val host = S3Location(candidate, DEFAULT_BUCKET).endpointHost?.removeSuffix(":443") ?: return null
        if ('.' !in host || host.startsWith('.') || host.endsWith('.')) return null
        if (!host.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' || it == ':' }) return null
        return "https://$host"
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** The account ID inside an R2 endpoint, if it is one (shown under the field so a wrong paste is easy to spot). */
    fun r2AccountId(endpoint: String): String? {
        val normalized = normalizedEndpoint(endpoint) ?: return null
        val host = normalized.removePrefix("https://")
        val suffix = ".r2.cloudflarestorage.com"
        if (!host.endsWith(suffix)) return null
        return host.removeSuffix(suffix).takeIf(::isAccountId)
    }

    /** What is still missing or wrong, in plain English and field order (empty = ready to test). */
    fun problems(input: CloudConfigInput): List<String> = buildList {
        val endpointId = input.trimmedEndpointId
        if (endpointId.isEmpty()) {
            add("Paste the RunPod Endpoint ID.")
        } else if (!RunPodJobsClient.isValidEndpointId(endpointId)) {
            add("The Endpoint ID should be letters and digits only (no https://, no slashes).")
        }
        if (input.trimmedRunpodKey.isEmpty()) add("Paste the Restricted RunPod key.")
        val endpoint = input.endpoint.trim()
        if (endpoint.isEmpty()) {
            add("Paste the R2 endpoint or your Cloudflare account ID.")
        } else if (normalizedEndpoint(endpoint) == null) {
            add("The R2 endpoint should look like https://<account-id>.r2.cloudflarestorage.com.")
        }
        val bucket = input.trimmedBucket
        if (bucket.isEmpty()) {
            add("Fill in the bucket name.")
        } else if (!S3Location("https://example.com", bucket).hasValidBucket) {
            add("Bucket names are 3–63 lower-case letters, digits, dots or dashes.")
        }
        if (input.accessKeyId.isBlank()) add("Paste the R2 access key ID.")
        if (input.secretAccessKey.isBlank()) add("Paste the R2 secret access key.")
    }
}
