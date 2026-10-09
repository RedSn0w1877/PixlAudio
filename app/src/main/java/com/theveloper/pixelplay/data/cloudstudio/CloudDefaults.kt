package com.theveloper.pixelplay.data.cloudstudio

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// Built-in cloud keys (2026-10-08, owner request: cloud instrumentals work out of the box, nothing to fill in), ported
// from the iOS app (`CloudDefaults.swift` in PixlNet and the app). The app bundles `assets/cloud_defaults.enc`, an
// AES-256-GCM blob holding the owner's RunPod endpoint, a Restricted RunPod key and a bucket-scoped R2 key pair. The
// 32-byte key that opens it is NOT in the repository: the build reads `CLOUD_DEFAULTS_KEY` (base64) from the
// environment (CI secret) or local.properties into `BuildConfig.CLOUD_DEFAULTS_KEY`. Builds without it (forks, pull
// requests, local builds) simply have no built-in keys and behave as before.
//
// Honest limit: anyone with the APK can pull the key out of it, so the keys are scoped (the RunPod key reaches one
// endpoint, the R2 key one bucket) and the app caps built-in spending at $3 a month. See handoff/2026-10-08-android-
// builtin-keys.md. No value in this file is ever logged, shown or written anywhere; they live in memory only.

/** The blob's layout: ASCII `PXCD1`, a 12-byte nonce, then the AES-256-GCM ciphertext followed by its 16-byte tag. */
object CloudDefaultsBlob {
    val MAGIC: ByteArray = "PXCD1".toByteArray(Charsets.US_ASCII)
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16
    /** AES-256. */
    const val KEY_LENGTH = 32
    /** The blob is a few hundred bytes; anything this big isn't one. */
    const val MAX_BYTES = 16_384

    /** The layout's pieces, or null when [bytes] isn't a blob (wrong magic, too short, nothing encrypted, too big). */
    fun split(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        val header = MAGIC.size + NONCE_LENGTH
        if (bytes.size > MAX_BYTES || bytes.size <= header + TAG_LENGTH) return null
        for (index in MAGIC.indices) if (bytes[index] != MAGIC[index]) return null
        // (nonce, ciphertext || tag): what `Cipher.doFinal` takes in GCM mode.
        return bytes.copyOfRange(MAGIC.size, header) to bytes.copyOfRange(header, bytes.size)
    }

    fun join(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray = MAGIC + nonce + ciphertextAndTag
}

/** What the blob decrypts to (UTF-8 JSON, schema `v` 1). `toString` never shows a value. */
@Serializable
class CloudDefaultsPayload(
    val v: Int = VERSION,
    val runpodEndpointId: String,
    val runpodKey: String,
    /** `https://<account-id>.r2.cloudflarestorage.com` (or the bare account ID, like the settings field). */
    val r2Endpoint: String,
    val bucket: String,
    val r2AccessKeyId: String,
    val r2SecretAccessKey: String,
) {
    /** The fields as the settings screen would hold them. */
    val configInput: CloudConfigInput
        get() = CloudConfigInput(runpodEndpointId, runpodKey, r2Endpoint, bucket, r2AccessKeyId, r2SecretAccessKey)

    override fun toString(): String = "CloudDefaultsPayload(v$v, redacted)"

    companion object {
        const val VERSION = 1

        /**
         * The decrypted bytes as a payload, or null when they aren't a complete v1 one (any field missing, empty or
         * not usable by the clients). A blob that opens but is unusable counts as no built-in keys at all.
         */
        fun decode(bytes: ByteArray): CloudDefaultsPayload? {
            val payload = try {
                CloudJson.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
            } catch (error: Exception) {
                return null
            }
            return payload.takeIf { it.v == VERSION && it.configInput.isComplete }
        }

        fun encode(payload: CloudDefaultsPayload): ByteArray =
            CloudJson.encodeToString(serializer(), payload).toByteArray(Charsets.UTF_8)
    }
}

/** AES-256-GCM over the blob's layout, with the platform's own JCE (no dependency). */
object CloudDefaultsCrypto {
    /** The 32-byte key of a base64 string (surrounding whitespace ignored), or null when it isn't one. */
    fun parseKey(base64: String): ByteArray? {
        val text = base64.trim()
        if (text.isEmpty()) return null
        val key = try {
            Base64.getDecoder().decode(text)
        } catch (error: IllegalArgumentException) {
            return null
        }
        return key.takeIf { it.size == CloudDefaultsBlob.KEY_LENGTH }
    }

    /** The payload inside [blob], or null for a wrong key, a tampered or truncated blob, or an unusable payload. */
    fun open(blob: ByteArray, key: ByteArray): CloudDefaultsPayload? {
        if (key.size != CloudDefaultsBlob.KEY_LENGTH) return null
        val (nonce, sealed) = CloudDefaultsBlob.split(blob) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(CloudDefaultsBlob.TAG_LENGTH * 8, nonce))
            CloudDefaultsPayload.decode(cipher.doFinal(sealed))
        } catch (error: Exception) {
            // A wrong key or any change to the blob fails the tag check; nothing here can hold a key value.
            null
        }
    }

    /** A blob for [payload] (tests; the real ones come from the owner's bake script). */
    fun seal(payload: CloudDefaultsPayload, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(CloudDefaultsBlob.TAG_LENGTH * 8, nonce))
        return CloudDefaultsBlob.join(nonce, cipher.doFinal(CloudDefaultsPayload.encode(payload)))
    }
}

/** Whose keys a job goes out with. */
enum class CloudKeySource {
    /** PixlAudio's built-in keys (the owner's endpoint and bucket). */
    BUILT_IN,
    /** The person's own, typed in Cloud processing (the only kind before 2026-10-08). */
    OWN,
}

/** The rules between built-in and own keys (Settings › Experimental › Cloud processing). */
object CloudKeyChoice {
    /**
     * Built-in keys may spend at most this much a month on one phone, whatever the cap field says (the app's $3
     * monthly cap stays; own keys keep the person's own cap).
     */
    const val BUILT_IN_MONTHLY_CAP_MICRO_USD: Long = 3_000_000

    /**
     * Own keys win whenever the person chose them; otherwise the built-in ones when this build has them; otherwise
     * own (the old behaviour: the fields, empty until filled in).
     */
    fun source(useOwnKeys: Boolean, builtInAvailable: Boolean): CloudKeySource =
        if (!useOwnKeys && builtInAvailable) CloudKeySource.BUILT_IN else CloudKeySource.OWN

    /**
     * "Use my own keys" before the person ever touched it: on for someone who set the feature up with their own keys
     * before built-in ones existed (saved keys, or an Endpoint ID typed in), so their setup keeps working.
     */
    fun defaultUseOwnKeys(keysSaved: Boolean, endpointId: String): Boolean = keysSaved || endpointId.isNotBlank()

    /**
     * The consent switch before the person ever touched it: on when the build has built-in keys (jobs still only go
     * when the person sends songs and confirms), off otherwise (nothing configured, as before).
     */
    fun defaultEnabled(builtInAvailable: Boolean): Boolean = builtInAvailable

    /** The cap the money guards use. */
    fun effectiveMonthlyCap(capMicroUsd: Long, source: CloudKeySource): Long =
        if (source == CloudKeySource.BUILT_IN) min(max(capMicroUsd, 0), BUILT_IN_MONTHLY_CAP_MICRO_USD) else capMicroUsd

    /**
     * The GPU price the estimates and the cap use: the field's, but never below PixlAudio's own endpoint price with
     * the built-in keys (a lower price typed in would otherwise let the $3 cap pass more songs than it pays for).
     */
    fun effectivePricePerSecond(priceMicroUsd: Long, source: CloudKeySource): Long =
        if (source == CloudKeySource.BUILT_IN) max(priceMicroUsd, CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD) else priceMicroUsd
}

/**
 * The seam to the app's own configuration (its endpoint, bucket and keys), used when the person hasn't chosen their
 * own keys. [NoBuiltInCloudConfig] when the build has none; [BuiltInCloudConfigProvider] in the app.
 */
interface CloudBuiltInConfig {
    /** Cheap and synchronous (no I/O, no decryption): this build carries a usable key. Whether it opens the blob is only known after [load]. */
    val isBundled: Boolean

    /** The built-in keys, decrypted once off the main thread and kept in memory; null when there are none or the blob doesn't open. */
    suspend fun load(): CloudConfigInput?
}

/** No built-in configuration: every field comes from the person. */
object NoBuiltInCloudConfig : CloudBuiltInConfig {
    override val isBundled: Boolean = false
    override suspend fun load(): CloudConfigInput? = null
}

/**
 * The app's built-in keys: the bundled blob opened with the key this build carries ([keyBase64], the BuildConfig
 * field; empty = none). Decrypts once, on [dispatcher] (never the caller's thread), and keeps the result in memory;
 * a wrong key, a tampered, truncated or placeholder blob and an unreadable asset all give null, never a crash.
 * Nothing it holds is ever logged: [toString] is fixed text.
 */
class BuiltInCloudConfigProvider(
    keyBase64: String,
    private val readBlob: () -> ByteArray?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudBuiltInConfig {
    private val key: ByteArray? = CloudDefaultsCrypto.parseKey(keyBase64)
    private val lock = Mutex()
    @Volatile private var finished = false
    @Volatile private var cached: CloudConfigInput? = null

    override val isBundled: Boolean get() = key != null

    override suspend fun load(): CloudConfigInput? {
        if (finished) return cached
        return lock.withLock {
            if (!finished) {
                cached = withContext(dispatcher) { decrypt() }
                finished = true
            }
            cached
        }
    }

    private fun decrypt(): CloudConfigInput? {
        val key = key ?: return null
        val blob: ByteArray? = try {
            readBlob()
        } catch (error: Exception) {
            null
        }
        if (blob == null) return null
        return CloudDefaultsCrypto.open(blob, key)?.configInput
    }

    override fun toString(): String = "BuiltInCloudConfigProvider(bundled=$isBundled)"
}
