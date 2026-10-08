package com.theveloper.pixelplay.data.cloudstudio

import kotlin.coroutines.cancellation.CancellationException

// Small-object access to the Cloud Studio bucket through short-lived presigned URLs (design §2.5, §7.2, §7.5), ported
// from the iOS app's `CloudObjectStore.swift`: HEAD and GET of manifests, the KB-sized lyrics.json, the connection
// probe, DELETE after import, and ListObjectsV2 of `out/`. The big transfers (the song upload, the instrumental
// download) go through [CloudTransfers] with a presigned URL from [CloudObjectStoring.presignedUrl].

/** What the engine needs from the bucket. [CloudObjectClient] is the real one; unit tests use fakes. */
interface CloudObjectStoring {
    /** A presigned URL for one object and method, valid for [expiresSeconds]. */
    fun presignedUrl(method: String, key: String, expiresSeconds: Int): String?
    /** The object's size, or null when it doesn't exist (404). */
    suspend fun head(key: String): Long?
    /** The object's bytes, or null when it doesn't exist (404). */
    suspend fun get(key: String): ByteArray?
    suspend fun put(key: String, data: ByteArray, contentType: String)
    /** Deleting a missing object succeeds (S3 answers 204 either way). */
    suspend fun delete(key: String)
    /** Every key under [prefix] (all pages), or with a delimiter the common prefixes too. */
    suspend fun list(prefix: String, delimiter: String?): S3ListResult
}

/** A bucket request failed. Messages never carry a signature. */
sealed class CloudStorageError(message: String) : Exception(message) {
    /** 401/403: the token is wrong, revoked, or not scoped to this bucket. */
    class Unauthorized(val status: Int) : CloudStorageError("Storage refused the key (HTTP $status)")
    /** The endpoint or bucket doesn't exist (404 on a bucket-level request, or `NoSuchBucket`). */
    class BucketNotFound : CloudStorageError("Bucket not found")
    class Http(val status: Int) : CloudStorageError("Storage answered HTTP $status")
    class Network(detail: String) : CloudStorageError("Network error: $detail")
    class BadResponse(detail: String) : CloudStorageError("Unexpected storage answer: $detail")
    /** The endpoint, bucket or keys aren't filled in correctly. */
    class NotConfigured : CloudStorageError("Storage isn't set up")
}

/** [CloudObjectStoring] over [CloudHttp] with presigned URLs (no `Authorization` header is ever sent). */
class CloudObjectClient(
    private val http: CloudHttp,
    private val signer: S3Signer,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : CloudObjectStoring {

    override fun presignedUrl(method: String, key: String, expiresSeconds: Int): String? =
        signer.presignedUrl(method, key, expiresSeconds = expiresSeconds, nowSeconds = nowSeconds())

    override suspend fun head(key: String): Long? {
        val response = send("HEAD", key)
        if (response.statusCode == 404) return null
        check(response)
        return response.header("Content-Length")?.trim()?.toLongOrNull() ?: 0L
    }

    override suspend fun get(key: String): ByteArray? {
        val response = send("GET", key)
        if (response.statusCode == 404) {
            if ("NoSuchBucket" in response.text) throw CloudStorageError.BucketNotFound()
            return null
        }
        check(response)
        return response.body
    }

    override suspend fun put(key: String, data: ByteArray, contentType: String) {
        val response = send("PUT", key, data, listOf("Content-Type" to contentType))
        if (response.statusCode == 404) throw CloudStorageError.BucketNotFound()
        check(response)
    }

    override suspend fun delete(key: String) {
        val response = send("DELETE", key)
        if (response.statusCode == 404) {
            if ("NoSuchBucket" in response.text) throw CloudStorageError.BucketNotFound()
            return
        }
        check(response)
    }

    override suspend fun list(prefix: String, delimiter: String?): S3ListResult {
        val objects = mutableListOf<S3ListResult.S3Object>()
        val prefixes = mutableListOf<String>()
        var token: String? = null
        repeat(50) { // 50 pages × 1000 keys is far beyond what the app ever leaves in the bucket
            val query = S3ListResult.query(prefix, delimiter, token)
            val url = signer.presignedUrl("GET", "", query, REQUEST_EXPIRY_SECONDS, nowSeconds())
                ?: throw CloudStorageError.NotConfigured()
            val response = transport(CloudHttpRequest("GET", url, timeoutMs = 30_000))
            if (response.statusCode == 404) throw CloudStorageError.BucketNotFound()
            check(response)
            val page = S3ListResult.parse(response.text) ?: throw CloudStorageError.BadResponse("unreadable bucket listing")
            objects += page.objects
            prefixes += page.commonPrefixes
            val next = page.nextContinuationToken
            if (!page.isTruncated || next.isNullOrEmpty()) return S3ListResult(objects, prefixes, false, null)
            token = next
        }
        return S3ListResult(objects, prefixes, false, null)
    }

    private suspend fun send(
        method: String,
        key: String,
        body: ByteArray? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): CloudHttpResponse {
        val url = signer.presignedUrl(method, key, expiresSeconds = REQUEST_EXPIRY_SECONDS, nowSeconds = nowSeconds())
            ?: throw CloudStorageError.NotConfigured()
        return transport(CloudHttpRequest(method, url, headers, body, timeoutMs = 60_000))
    }

    private suspend fun transport(request: CloudHttpRequest): CloudHttpResponse = try {
        http.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Error text never carries a signature (HTTP stacks can quote the URL).
        throw CloudStorageError.Network(CloudRedaction.redact(error.message ?: error.javaClass.simpleName))
    }

    private fun check(response: CloudHttpResponse) {
        if (response.isSuccessful) return
        if (response.statusCode == 401 || response.statusCode == 403) {
            throw CloudStorageError.Unauthorized(response.statusCode)
        }
        throw CloudStorageError.Http(response.statusCode)
    }

    companion object {
        /** Presigned URLs for the phone's own small requests live this long. */
        const val REQUEST_EXPIRY_SECONDS = 900
    }
}
