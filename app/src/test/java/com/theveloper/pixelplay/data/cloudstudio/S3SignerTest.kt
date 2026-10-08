package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudFixtures.JOB_KEY
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class S3SignerTest {
    @Test fun `AWS's published query-presign example`() {
        // docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html — "An example".
        val signer = S3Signer(
            S3Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            S3Location("https://s3.amazonaws.com", "examplebucket", region = "us-east-1", virtualHosted = true),
        )
        val url = signer.presignedUrl("GET", "test.txt", expiresSeconds = 86_400,
            nowSeconds = Instant.parse("2013-05-24T00:00:00Z").epochSecond)
        assertEquals(
            "https://examplebucket.s3.amazonaws.com/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" +
                "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
                "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404",
            url
        )
    }

    @Test fun `R2 URLs have the exact shape of the worker's golden examples`() {
        val account = "0123456789abcdef0123456789abcdef"
        val signer = S3Signer(
            S3Credentials("EXAMPLEACCESSKEYID", "secret"),
            S3Location(CloudConfig.r2Endpoint(account), CloudConfig.DEFAULT_BUCKET),
        )
        val url = signer.presignedUrl("GET", CloudKeys.input(JOB_KEY, "m4a"), expiresSeconds = 273_600,
            nowSeconds = Instant.parse("2026-10-07T12:00:00Z").epochSecond)!!
        val golden = CloudJson.decodeFromString(CloudJobInput.serializer(), CloudFixtures.worker("job.input.process.json"))
            .audio!!.get!!
        assertEquals(golden.substringBefore("X-Amz-Signature="), url.substringBefore("X-Amz-Signature="))
        assertTrue(Regex("X-Amz-Signature=[0-9a-f]{64}$").containsMatchIn(url))
        // Signing is deterministic and the method is part of it.
        val again = signer.presignedUrl("GET", CloudKeys.input(JOB_KEY, "m4a"), expiresSeconds = 273_600,
            nowSeconds = Instant.parse("2026-10-07T12:00:00Z").epochSecond)
        val put = signer.presignedUrl("PUT", CloudKeys.input(JOB_KEY, "m4a"), expiresSeconds = 273_600,
            nowSeconds = Instant.parse("2026-10-07T12:00:00Z").epochSecond)!!
        assertEquals(url, again)
        assertFalse(put.substringAfter("X-Amz-Signature=") == url.substringAfter("X-Amz-Signature="))
    }

    @Test fun `expiry is clamped to R2's seven days`() {
        val signer = S3Signer(S3Credentials("a", "b"), S3Location("https://h.example.com", "bucket"))
        val url = signer.presignedUrl("GET", "k", expiresSeconds = 10_000_000, nowSeconds = 0)!!
        assertTrue("X-Amz-Expires=604800" in url)
        assertTrue(url.startsWith("https://h.example.com/bucket/k?"))
    }

    @Test fun `listing URL signs the bucket itself with the query`() {
        val signer = S3Signer(S3Credentials("a", "b"), S3Location("https://h.example.com", "bucket"))
        val url = signer.presignedUrl("GET", "", S3ListResult.query("out/", "/", null), 900, 0)!!
        assertTrue(url.startsWith("https://h.example.com/bucket?"))
        assertTrue("delimiter=%2F" in url && "list-type=2" in url && "prefix=out%2F" in url)
    }

    @Test fun `AWS URI encoding`() {
        assertEquals("a%20b/c~d", S3Signer.uriEncode("a b/c~d", encodeSlash = false))
        assertEquals("a%20b%2Fc~d", S3Signer.uriEncode("a b/c~d", encodeSlash = true))
        assertEquals("%C3%A9-_.", S3Signer.uriEncode("é-_.", encodeSlash = true))
        assertEquals("20261007T120000Z", S3Signer.amzDate(Instant.parse("2026-10-07T12:00:00Z").epochSecond))
    }

    @Test fun `locations and bucket names`() {
        assertNull(S3Location("http://h.example.com", "bucket").endpointHost)
        assertEquals("h.example.com:8443", S3Location("https://H.example.com:8443/path?q", "bucket").endpointHost)
        assertTrue(S3Location("https://h", "pixl-cloud-studio").hasValidBucket)
        assertFalse(S3Location("https://h", "Pixl").hasValidBucket)
        assertFalse(S3Location("https://h", "-ab").hasValidBucket)
        assertFalse(S3Location("https://h", "ab").hasValidBucket)
    }

    @Test fun `credentials never print the secret`() {
        val credentials = S3Credentials("AKID", "super-secret-value")
        assertFalse("super-secret-value" in credentials.toString())
        assertFalse("AKID" in credentials.toString())
    }

    @Test fun `ListObjectsV2 body parses`() {
        val list = S3ListResult.parse(CloudFixtures.phone("s3.list.xml"))!!
        assertEquals(1, list.objects.size)
        assertEquals("out/readme&notes.txt", list.objects[0].key)
        assertEquals(12L, list.objects[0].size)
        assertEquals(
            listOf(JOB_KEY, "0a1b2c3d-0000-4000-8000-000000000001"),
            list.commonPrefixes.mapNotNull(CloudKeys::jobKeyFromOutputKey)
        )
        assertFalse(list.isTruncated)
        assertNull(S3ListResult.parse("<Error><Code>AccessDenied</Code></Error>"))
        val truncated = S3ListResult.parse(
            "<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>t&amp;1</NextContinuationToken>" +
                "<Contents><Key>a</Key><Size/></Contents></ListBucketResult>"
        )!!
        assertTrue(truncated.isTruncated)
        assertEquals("t&1", truncated.nextContinuationToken)
        assertEquals(0L, truncated.objects.single().size)
    }
}
