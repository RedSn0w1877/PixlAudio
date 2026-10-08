package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.BuildConfig
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The built-in keys: the blob's format (also made by the owner's Node bake script and the iOS app), the provider that
 * opens it, and the rules between built-in and own keys. Every key and value here is a throwaway dummy; the real blob
 * is never asserted on (except that it opens, in [the bundled blob fits this build]).
 */
class CloudDefaultsTest {
    /** The throwaway AES key of the Node-made fixture and of the tests ((i * 7 + 3) mod 256). */
    private val key = ByteArray(32) { ((it * 7 + 3) % 256).toByte() }
    private val keyBase64 = Base64.getEncoder().encodeToString(key)
    private val nonce = ByteArray(12) { (it + 1).toByte() }

    private val payload = CloudDefaultsPayload(
        runpodEndpointId = "dummyendpoint1",
        runpodKey = "rpa_DUMMY_RUNPOD_KEY",
        r2Endpoint = "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com",
        bucket = "pixl-cloud-studio",
        r2AccessKeyId = "DUMMYACCESSKEYID",
        r2SecretAccessKey = "dummy/secret+access/key",
    )

    private fun fixture(): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream("cloudstudio/phone/cloud_defaults.test.enc")) {
        "missing fixture"
    }.use { it.readBytes() }

    // ─── The blob ───────────────────────────────────────────────────────────────────────────

    @Test fun `a blob made by the Node bake script's format opens with its key`() {
        val opened = CloudDefaultsCrypto.open(fixture(), key)
        assertNotNull(opened)
        val config = opened!!.configInput
        assertTrue(config.isComplete)
        assertEquals("testendpoint123", config.trimmedEndpointId)
        assertEquals("pixl-cloud-studio", config.trimmedBucket)
        assertEquals("rpa_TEST_DUMMY_KEY_NOT_REAL", config.trimmedRunpodKey)
        assertEquals("TESTACCESSKEYIDNOTREAL", config.credentials.accessKeyId)
        assertEquals("https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com", config.location!!.endpoint)
    }

    @Test fun `the layout is magic, a 12-byte nonce, then ciphertext and tag`() {
        val blob = CloudDefaultsCrypto.seal(payload, key, nonce)
        assertEquals("PXCD1", String(blob.copyOfRange(0, 5), Charsets.US_ASCII))
        assertArrayEquals(nonce, blob.copyOfRange(5, 17))
        val plaintextLength = CloudDefaultsPayload.encode(payload).size
        assertEquals(5 + 12 + plaintextLength + 16, blob.size)
        val (parsedNonce, sealed) = CloudDefaultsBlob.split(blob)!!
        assertArrayEquals(nonce, parsedNonce)
        assertEquals(plaintextLength + 16, sealed.size)
    }

    @Test fun `seal and open round trip`() {
        val opened = CloudDefaultsCrypto.open(CloudDefaultsCrypto.seal(payload, key, nonce), key)!!
        assertEquals(payload.configInput, opened.configInput)
        assertEquals(1, opened.v)
    }

    @Test fun `a wrong key opens nothing`() {
        val blob = CloudDefaultsCrypto.seal(payload, key, nonce)
        val other = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(CloudDefaultsCrypto.open(blob, other))
        assertNull(CloudDefaultsCrypto.open(blob, ByteArray(16)))
        assertNull(CloudDefaultsCrypto.open(blob, ByteArray(0)))
    }

    @Test fun `any changed byte, a truncated or a foreign blob opens nothing`() {
        val blob = CloudDefaultsCrypto.seal(payload, key, nonce)
        // Every region: magic, nonce, ciphertext, tag.
        for (index in listOf(0, 4, 5, 16, 17, blob.size / 2, blob.size - 17, blob.size - 16, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull(CloudDefaultsCrypto.open(tampered, key), "byte $index")
        }
        assertNull(CloudDefaultsCrypto.open(blob.copyOf(blob.size - 1), key))
        assertNull(CloudDefaultsCrypto.open(blob.copyOf(5 + 12 + 16), key))
        assertNull(CloudDefaultsCrypto.open(blob.copyOf(10), key))
        assertNull(CloudDefaultsCrypto.open(ByteArray(0), key))
        assertNull(CloudDefaultsCrypto.open("not a blob at all, but long enough to look like one maybe".toByteArray(), key))
        assertNull(CloudDefaultsCrypto.open(ByteArray(CloudDefaultsBlob.MAX_BYTES + 1), key))
    }

    @Test fun `a blob that opens but holds an unusable payload counts as no keys`() {
        fun sealed(json: String): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            return CloudDefaultsBlob.join(nonce, cipher.doFinal(json.toByteArray()))
        }
        // The placeholder shape (empty values), a wrong schema version, a missing field, and not JSON at all.
        assertNull(CloudDefaultsCrypto.open(sealed("""{"v":1,"runpodEndpointId":"","runpodKey":"","r2Endpoint":"","bucket":"","r2AccessKeyId":"","r2SecretAccessKey":""}"""), key))
        assertNull(CloudDefaultsCrypto.open(sealed(String(CloudDefaultsPayload.encode(payload)).replace("\"v\":1", "\"v\":2")), key))
        assertNull(CloudDefaultsCrypto.open(sealed("""{"v":1,"runpodEndpointId":"abc"}"""), key))
        assertNull(CloudDefaultsCrypto.open(sealed("nope"), key))
        // A payload with an endpoint ID the client can't use (a URL, not an ID).
        val bad = CloudDefaultsPayload(runpodEndpointId = "https://x/y", runpodKey = "k", r2Endpoint = payload.r2Endpoint,
            bucket = payload.bucket, r2AccessKeyId = "a", r2SecretAccessKey = "b")
        assertNull(CloudDefaultsCrypto.open(CloudDefaultsCrypto.seal(bad, key, nonce), key))
    }

    @Test fun `keys are parsed from base64 or refused`() {
        assertArrayEquals(key, CloudDefaultsCrypto.parseKey(keyBase64))
        assertArrayEquals(key, CloudDefaultsCrypto.parseKey("  $keyBase64\n"))
        assertNull(CloudDefaultsCrypto.parseKey(""))
        assertNull(CloudDefaultsCrypto.parseKey("   "))
        assertNull(CloudDefaultsCrypto.parseKey("not base64 !!"))
        assertNull(CloudDefaultsCrypto.parseKey(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertNull(CloudDefaultsCrypto.parseKey(Base64.getEncoder().encodeToString(ByteArray(33))))
    }

    @Test fun `nothing prints a key value`() {
        val everything = listOf(
            payload.toString(), payload.configInput.toString(), CloudSecrets("rpa_X", "AK", "SK").toString(),
            BuiltInCloudConfigProvider(keyBase64, { null }).toString(),
        ).joinToString("\n")
        for (secret in listOf(payload.runpodKey, payload.r2AccessKeyId, payload.r2SecretAccessKey, keyBase64)) {
            assertFalse(secret in everything, "leaked a value")
        }
    }

    // ─── The provider ───────────────────────────────────────────────────────────────────────

    @Test fun `the provider opens the blob once, off the caller's thread, and keeps it`() = runBlocking {
        var reads = 0
        var readThread: Thread? = null
        val blob = CloudDefaultsCrypto.seal(payload, key, nonce)
        val provider = BuiltInCloudConfigProvider(keyBase64, readBlob = {
            reads++
            readThread = Thread.currentThread()
            blob
        }, dispatcher = Dispatchers.Default)
        assertTrue(provider.isBundled)
        // Two callers at once and one later: one read, one decryption.
        val results = listOf(async { provider.load() }, async { provider.load() }).awaitAll() + provider.load()
        assertTrue(results.all { it == payload.configInput })
        assertEquals(1, reads)
        assertNotEquals(Thread.currentThread(), readThread)
    }

    @Test fun `no key means no built-in keys and the blob is never read`() = runBlocking {
        var reads = 0
        for (empty in listOf("", "  ", "garbage", Base64.getEncoder().encodeToString(ByteArray(8)))) {
            val provider = BuiltInCloudConfigProvider(empty, readBlob = { reads++; fixture() })
            assertFalse(provider.isBundled)
            assertNull(provider.load())
        }
        assertEquals(0, reads)
    }

    @Test fun `a wrong key, a tampered blob and an unreadable asset give null, not a crash`() = runBlocking {
        val blob = CloudDefaultsCrypto.seal(payload, key, nonce)
        val otherKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        assertNull(BuiltInCloudConfigProvider(otherKey, { blob }).load())
        assertNull(BuiltInCloudConfigProvider(keyBase64, { blob.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() } }).load())
        assertNull(BuiltInCloudConfigProvider(keyBase64, { throw java.io.FileNotFoundException("no asset") }).load())
        assertNull(BuiltInCloudConfigProvider(keyBase64, { null }).load())
        assertNull(BuiltInCloudConfigProvider(keyBase64, { ByteArray(0) }).load())
    }

    /**
     * When this build carries the real key (CI with the secret, or a local.properties key), the blob bundled in
     * `assets/` must open with it: a key that doesn't match can't ship silently. Skipped without the key (forks, pull
     * requests). Only "it opens" is asserted, never what is inside.
     */
    @Test fun `the bundled blob fits this build`() {
        val buildKey = CloudDefaultsCrypto.parseKey(BuildConfig.CLOUD_DEFAULTS_KEY)
        assumeTrue(buildKey != null, "this build carries no CLOUD_DEFAULTS_KEY")
        val asset = File("src/main/assets/cloud_defaults.enc")
        assumeTrue(asset.isFile, "the unit tests did not run from the app module directory")
        assertNotNull(CloudDefaultsCrypto.open(asset.readBytes(), buildKey!!), "the CLOUD_DEFAULTS_KEY secret does not open cloud_defaults.enc")
    }

    // ─── Which keys win ─────────────────────────────────────────────────────────────────────

    @Test fun `own keys win whenever chosen, built-in is the default when the build has it`() {
        assertEquals(CloudKeySource.BUILT_IN, CloudKeyChoice.source(useOwnKeys = false, builtInAvailable = true))
        assertEquals(CloudKeySource.OWN, CloudKeyChoice.source(useOwnKeys = true, builtInAvailable = true))
        // No built-in keys in this build (a fork, or a blob that doesn't open): the fields, as before.
        assertEquals(CloudKeySource.OWN, CloudKeyChoice.source(useOwnKeys = false, builtInAvailable = false))
        assertEquals(CloudKeySource.OWN, CloudKeyChoice.source(useOwnKeys = true, builtInAvailable = false))
    }

    @Test fun `someone who set up their own keys keeps them, a new install starts on built-in`() {
        assertTrue(CloudKeyChoice.defaultUseOwnKeys(keysSaved = true, endpointId = ""))
        assertTrue(CloudKeyChoice.defaultUseOwnKeys(keysSaved = false, endpointId = "abc123"))
        assertFalse(CloudKeyChoice.defaultUseOwnKeys(keysSaved = false, endpointId = ""))
        assertFalse(CloudKeyChoice.defaultUseOwnKeys(keysSaved = false, endpointId = "   "))
        assertTrue(CloudKeyChoice.defaultEnabled(builtInAvailable = true))
        assertFalse(CloudKeyChoice.defaultEnabled(builtInAvailable = false))
    }

    @Test fun `the monthly cap is clamped to 3 dollars with built-in keys only`() {
        val builtIn = CloudKeySource.BUILT_IN
        assertEquals(3_000_000, CloudKeyChoice.effectiveMonthlyCap(100_000_000, builtIn))
        assertEquals(3_000_000, CloudKeyChoice.effectiveMonthlyCap(3_000_000, builtIn))
        assertEquals(1_000_000, CloudKeyChoice.effectiveMonthlyCap(1_000_000, builtIn))
        assertEquals(0, CloudKeyChoice.effectiveMonthlyCap(-5, builtIn))
        assertEquals(100_000_000, CloudKeyChoice.effectiveMonthlyCap(100_000_000, CloudKeySource.OWN))
    }

    @Test fun `the GPU price never falls below the endpoint's own with built-in keys`() {
        val floor = CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD
        assertEquals(floor, CloudKeyChoice.effectivePricePerSecond(1, CloudKeySource.BUILT_IN))
        assertEquals(floor, CloudKeyChoice.effectivePricePerSecond(floor, CloudKeySource.BUILT_IN))
        assertEquals(500, CloudKeyChoice.effectivePricePerSecond(500, CloudKeySource.BUILT_IN))
        assertEquals(1, CloudKeyChoice.effectivePricePerSecond(1, CloudKeySource.OWN))
    }

    @Test fun `the settings snapshot applies the rules`() {
        val snapshot = CloudSettingsSnapshot(builtInAvailable = true, useOwnKeys = false, monthlyCapMicroUsd = 50_000_000, pricePerSecondMicroUsd = 10)
        assertTrue(snapshot.usesBuiltInKeys)
        assertEquals(3_000_000, snapshot.effectiveMonthlyCapMicroUsd)
        assertEquals(192, snapshot.effectivePricePerSecondMicroUsd)
        val own = snapshot.copy(useOwnKeys = true)
        assertFalse(own.usesBuiltInKeys)
        assertEquals(50_000_000, own.effectiveMonthlyCapMicroUsd)
        assertEquals(10, own.effectivePricePerSecondMicroUsd)
        // A fork's build: no built-in keys, the person's own numbers whatever the switch says.
        val fork = snapshot.copy(builtInAvailable = false)
        assertEquals(CloudKeySource.OWN, fork.keySource)
        assertEquals(50_000_000, fork.effectiveMonthlyCapMicroUsd)
    }
}
