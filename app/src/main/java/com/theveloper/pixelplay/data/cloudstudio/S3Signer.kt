package com.theveloper.pixelplay.data.cloudstudio

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// AWS Signature Version 4 query presigning for S3-compatible storage (Cloudflare R2; design §1, §4, §7.1), ported from
// the iOS app's `S3Signer.swift`. Every R2 transfer the phone and the worker make uses a presigned URL (`X-Amz-*` in
// the query, payload `UNSIGNED-PAYLOAD`, only `host` signed): a presigned URL carries no reusable secret, so it may sit
// in a RunPod job input, and the phone never sends an `Authorization` header to the bucket at all.
// Reference: docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html (its example is a unit test).
//
// The iOS signer also has header signing for the RunPod network-volume fallback; Android ships R2 only, like the
// settings screen on both apps, so that mode isn't ported.

/** An S3 access key pair (R2: a bucket-scoped API token's Access Key ID and Secret). */
class S3Credentials(val accessKeyId: String, val secretAccessKey: String) {
    val isComplete: Boolean get() = accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()

    override fun equals(other: Any?): Boolean =
        other is S3Credentials && other.accessKeyId == accessKeyId && other.secretAccessKey == secretAccessKey

    override fun hashCode(): Int = accessKeyId.hashCode() * 31 + secretAccessKey.hashCode()

    /** Never the secret: a stray log line must not carry it. */
    override fun toString(): String = "S3Credentials(accessKeyId=…, secretAccessKey=…)"
}

/** Where the bucket lives. R2: endpoint `https://<account-id>.r2.cloudflarestorage.com`, region `auto`, path-style. */
data class S3Location(
    /** `https://host[:port]` (a trailing slash or path is ignored). */
    val endpoint: String,
    val bucket: String,
    val region: String = "auto",
    /** `true`: `https://<bucket>.<host>/<key>` (AWS's examples); `false`: `https://<host>/<bucket>/<key>` (R2). */
    val virtualHosted: Boolean = false,
) {
    /** Host (with port) of [endpoint], or null when it isn't an `https://` URL with a host. */
    val endpointHost: String?
        get() {
            val trimmed = endpoint.trim()
            if (!trimmed.lowercase().startsWith("https://")) return null
            val rest = trimmed.substring("https://".length)
            val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            if (host.isEmpty() || '@' in host || ' ' in host) return null
            return host.lowercase()
        }

    /** A valid S3 bucket name (3–63 of `a-z 0-9 . -`, starting and ending with a letter or digit). */
    val hasValidBucket: Boolean
        get() {
            if (bucket.length !in 3..63) return false
            fun ok(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-'
            fun alnum(c: Char) = c in 'a'..'z' || c in '0'..'9'
            return bucket.all(::ok) && alnum(bucket.first()) && alnum(bucket.last())
        }

    val isValid: Boolean get() = endpointHost != null && hasValidBucket && region.isNotEmpty()

    /** The host requests go to. */
    internal val requestHost: String?
        get() {
            val host = endpointHost ?: return null
            return if (virtualHosted) "$bucket.$host" else host
        }

    /** The URI path of an object (`key` empty = the bucket itself, for ListObjectsV2). */
    internal fun path(key: String): String {
        val encodedKey = S3Signer.uriEncode(key, encodeSlash = false)
        if (virtualHosted) return "/$encodedKey"
        val encodedBucket = S3Signer.uriEncode(bucket, encodeSlash = true)
        return if (key.isEmpty()) "/$encodedBucket" else "/$encodedBucket/$encodedKey"
    }
}

/** SigV4 query presigner for one location and key pair. */
class S3Signer(
    private val credentials: S3Credentials,
    val location: S3Location,
    private val service: String = "s3",
) {
    /**
     * A presigned URL for [method] on [key] (empty key = the bucket, e.g. ListObjectsV2 with [query]), valid for
     * [expiresSeconds] (clamped to 1 s…7 d) from [nowSeconds] (Unix time). Only `host` is signed, so the client may
     * send any other header (Content-Type, Range).
     */
    fun presignedUrl(
        method: String,
        key: String,
        query: List<Pair<String, String>> = emptyList(),
        expiresSeconds: Int,
        nowSeconds: Long,
    ): String? {
        val host = location.requestHost ?: return null
        val amzDate = amzDate(nowSeconds)
        val date = amzDate.substring(0, 8)
        val scope = "$date/${location.region}/$service/aws4_request"
        val expires = expiresSeconds.coerceIn(1, MAXIMUM_EXPIRY_SECONDS)
        val parameters = query + listOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${credentials.accessKeyId}/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expires.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val canonicalQuery = canonicalQuery(parameters)
        val path = location.path(key)
        val canonicalRequest = listOf(method.uppercase(), path, canonicalQuery, "host:$host\n", "host", UNSIGNED_PAYLOAD)
            .joinToString("\n")
        val signature = sign(canonicalRequest, amzDate, scope, date)
        return "https://$host$path?$canonicalQuery&X-Amz-Signature=$signature"
    }

    internal fun sign(canonicalRequest: String, amzDate: String, scope: String, date: String): String {
        val stringToSign = listOf(ALGORITHM, amzDate, scope, hex(sha256(canonicalRequest.toByteArray()))).joinToString("\n")
        return hex(hmac(signingKey(date), stringToSign.toByteArray()))
    }

    internal fun signingKey(date: String): ByteArray {
        val kDate = hmac("AWS4${credentials.secretAccessKey}".toByteArray(), date.toByteArray())
        val kRegion = hmac(kDate, location.region.toByteArray())
        val kService = hmac(kRegion, service.toByteArray())
        return hmac(kService, "aws4_request".toByteArray())
    }

    companion object {
        /** R2 and S3 accept presigned URLs valid for at most 7 days. */
        const val MAXIMUM_EXPIRY_SECONDS = 604_800
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        private val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** `yyyyMMdd'T'HHmmss'Z'` in UTC for a Unix time. */
        fun amzDate(unixSeconds: Long): String = AMZ_DATE.format(Instant.ofEpochSecond(unixSeconds))

        /** Query parameters URI-encoded and sorted by encoded name, then value. */
        internal fun canonicalQuery(parameters: List<Pair<String, String>>): String = parameters
            .map { (name, value) -> uriEncode(name, encodeSlash = true) to uriEncode(value, encodeSlash = true) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { (name, value) -> "$name=$value" }

        /** AWS `UriEncode`: every byte except `A–Z a–z 0–9 - _ . ~` as `%XX` (upper-case hex); `/` kept in paths. */
        internal fun uriEncode(text: String, encodeSlash: Boolean): String {
            val out = StringBuilder(text.length)
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 0xFF
                if (isUnreserved(b) || (b == 0x2F && !encodeSlash)) {
                    out.append(b.toChar())
                } else {
                    out.append('%').append(HEX_UPPER[b shr 4]).append(HEX_UPPER[b and 0x0F])
                }
            }
            return out.toString()
        }

        private fun isUnreserved(b: Int): Boolean =
            b in 0x41..0x5A || b in 0x61..0x7A || b in 0x30..0x39 || b == 0x2D || b == 0x5F || b == 0x2E || b == 0x7E

        private const val HEX_UPPER = "0123456789ABCDEF"
        private const val HEX_LOWER = "0123456789abcdef"

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val b = byte.toInt() and 0xFF
                out.append(HEX_LOWER[b shr 4]).append(HEX_LOWER[b and 0x0F])
            }
            return out.toString()
        }

        fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        fun hmac(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }
    }
}

// ─── ListObjectsV2 ──────────────────────────────────────────────────────────────────────────────

/** One page of `ListObjectsV2` (`list-type=2`). */
data class S3ListResult(
    val objects: List<S3Object>,
    /** `CommonPrefixes/Prefix` (with `delimiter=/`: the `out/<jobKey>/` folders). */
    val commonPrefixes: List<String>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
) {
    data class S3Object(val key: String, val size: Long, val lastModified: String?)

    companion object {
        /** The query of a ListObjectsV2 request. */
        fun query(prefix: String, delimiter: String?, continuationToken: String?, maxKeys: Int = 1000): List<Pair<String, String>> {
            val query = mutableListOf("list-type" to "2", "prefix" to prefix, "max-keys" to maxKeys.toString())
            if (delimiter != null) query += "delimiter" to delimiter
            if (continuationToken != null) query += "continuation-token" to continuationToken
            return query
        }

        /** Parses the XML body: a tiny scanner (S3 list bodies are flat and well-formed), as on iOS. */
        fun parse(xml: String): S3ListResult? {
            if (!xml.contains("<ListBucketResult")) return null
            val objects = elements("Contents", xml).mapNotNull { block ->
                val key = elements("Key", block).firstOrNull()?.let(::xmlUnescape) ?: return@mapNotNull null
                val size = elements("Size", block).firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                S3Object(key, size, elements("LastModified", block).firstOrNull())
            }
            val prefixes = elements("CommonPrefixes", xml).mapNotNull { block ->
                elements("Prefix", block).firstOrNull()?.let(::xmlUnescape)
            }
            // Top-level flags only (a <Contents> never holds these names).
            val truncated = elements("IsTruncated", xml).firstOrNull()?.trim()?.lowercase() == "true"
            val token = elements("NextContinuationToken", xml).firstOrNull()?.let(::xmlUnescape)
            return S3ListResult(objects, prefixes, truncated, token)
        }

        /** The inner text of every `<name>…</name>` (no attributes expected; `<name/>` yields ""). */
        internal fun elements(name: String, xml: String): List<String> {
            val out = mutableListOf<String>()
            val open = "<$name>"
            val close = "</$name>"
            val empty = "<$name/>"
            var index = 0
            while (true) {
                val openAt = xml.indexOf(open, index)
                val emptyAt = xml.indexOf(empty, index)
                if (emptyAt >= 0 && (openAt < 0 || emptyAt < openAt)) {
                    out += ""
                    index = emptyAt + empty.length
                    continue
                }
                if (openAt < 0) break
                val closeAt = xml.indexOf(close, openAt + open.length)
                if (closeAt < 0) break
                out += xml.substring(openAt + open.length, closeAt)
                index = closeAt + close.length
            }
            return out
        }

        internal fun xmlUnescape(text: String): String {
            if ('&' !in text) return text
            return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&")
        }
    }
}
