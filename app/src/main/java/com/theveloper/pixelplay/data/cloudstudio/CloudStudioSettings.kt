package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Settings › Developer options › Experimental › Cloud processing (design §3.5 E, §7.2, §8).
 *
 * - The non-secret values (the consent switch, Endpoint ID, R2 endpoint, bucket, outputs, network, cost) live in the
 *   plain SharedPreferences file `cloud_studio_settings`. They are not in the settings DataStore, so `.pxpl` backups
 *   never carry them, and the Android backup rules exclude the file too.
 * - The three keys (the RunPod Restricted key, the R2 access key ID and secret) live ONLY in
 *   EncryptedSharedPreferences (`cloud_studio_secrets`, AES-256 under an Android Keystore master key). Unlike the
 *   Spotify session there is no plaintext fallback: if the Keystore can't open the file, the keys can't be saved or
 *   read and the screen says so. The keys never reach code, tests, logs or backups.
 * - If keys were saved once and the store later comes back empty (app data cleared, a restored device), the screen
 *   says "Cloud keys missing — paste them again" instead of failing silently (the iOS behaviour).
 * - Built-in keys (2026-10-08): a build that carries PixlAudio's own keys ([CloudBuiltInConfig]) uses them unless the
 *   person chose "Use my own keys" ([CloudKeyChoice]: own keys always win), and has the feature on until the person
 *   switches it off. A build without them, or whose blob doesn't open, behaves exactly as before. The built-in keys are
 *   opened once on a background thread when this object is created and are never read from or written to storage.
 */
class CloudStudioSettings(
    context: Context,
    private val builtIn: CloudBuiltInConfig = NoBuiltInCloudConfig,
) : CloudStudioSettingsSource {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretsLock = Mutex()
    @Volatile private var secretPrefs: SharedPreferences? = null
    @Volatile private var secretStoreFailed = false
    /** Built-in keys exist in this build (a key is there) and have not turned out unusable (counts as available while opening). */
    @Volatile private var builtInAvailable = builtIn.isBundled

    private val _settings = MutableStateFlow(readSnapshot())
    /** The non-secret values, for the screens. */
    val settings: StateFlow<CloudSettingsSnapshot> = _settings.asStateFlow()

    init {
        // Open the blob once, off the main thread; if it doesn't open (another key, damaged) the build is treated as
        // having no built-in keys, like a fork's.
        if (builtIn.isBundled) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val config = try {
                    builtIn.load()
                } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    null
                }
                if (config == null) markBuiltInUnavailable()
            }
        }
    }

    @Synchronized
    private fun markBuiltInUnavailable() {
        builtInAvailable = false
        _settings.value = _settings.value.let {
            it.copy(
                builtInAvailable = false,
                // The untouched default of the consent switch follows availability.
                enabled = if (prefs.contains(Keys.ENABLED)) it.enabled else CloudKeyChoice.defaultEnabled(false),
            )
        }
    }

    override fun snapshot(): CloudSettingsSnapshot = _settings.value

    private fun readSnapshot(): CloudSettingsSnapshot {
        val caps = prefs.getString(Keys.WORKER_CAPS, null)?.let {
            runCatching { CloudJson.decodeFromString(CloudWorkerCaps.serializer(), it) }.getOrNull()
        }
        val endpointId = prefs.getString(Keys.ENDPOINT_ID, "").orEmpty()
        return CloudSettingsSnapshot(
            enabled = if (prefs.contains(Keys.ENABLED)) prefs.getBoolean(Keys.ENABLED, false)
            else CloudKeyChoice.defaultEnabled(builtInAvailable),
            useOwnKeys = if (prefs.contains(Keys.USE_OWN_KEYS)) prefs.getBoolean(Keys.USE_OWN_KEYS, false)
            else CloudKeyChoice.defaultUseOwnKeys(prefs.getBoolean(Keys.KEYS_SAVED, false), endpointId),
            builtInAvailable = builtInAvailable,
            endpointId = endpointId,
            r2Endpoint = prefs.getString(Keys.R2_ENDPOINT, "").orEmpty(),
            bucket = prefs.getString(Keys.BUCKET, CloudConfig.DEFAULT_BUCKET) ?: CloudConfig.DEFAULT_BUCKET,
            wantsInstrumental = prefs.getBoolean(Keys.INSTRUMENTAL, true),
            wantsLyrics = prefs.getBoolean(Keys.LYRICS, true),
            transcribeWhenMissing = prefs.getBoolean(Keys.TRANSCRIBE, true),
            quality = CloudSeparationQuality.fromWire(prefs.getString(Keys.QUALITY, null)) ?: CloudSeparationQuality.STANDARD,
            useCellular = prefs.getBoolean(Keys.CELLULAR, false),
            pricePerSecondMicroUsd = prefs.getLong(Keys.PRICE, CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD).coerceIn(1, 10_000),
            monthlyCapMicroUsd = prefs.getLong(Keys.MONTHLY_CAP, CloudCost.DEFAULT_MONTHLY_CAP_MICRO_USD).coerceIn(0, 1_000_000_000),
            workerCaps = caps,
        )
    }

    /** Changes the non-secret values. Changing the Endpoint ID forgets the old endpoint's limits. */
    @Synchronized
    fun update(change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot) {
        val before = _settings.value
        // Availability is the build's, not a setting.
        var after = change(before).copy(builtInAvailable = before.builtInAvailable)
        // Another endpoint (typed in, or the other kind of keys): the old one's limits are unknown until a selftest.
        if ((after.endpointId.trim() != before.endpointId.trim() || after.useOwnKeys != before.useOwnKeys) &&
            after.workerCaps != null && after.workerCaps == before.workerCaps
        ) after = after.copy(workerCaps = null)
        if (after == before) return
        prefs.edit().apply {
            // The consent switch and the key choice stay "untouched" (their defaults follow the build) until changed.
            if (after.enabled != before.enabled || prefs.contains(Keys.ENABLED)) putBoolean(Keys.ENABLED, after.enabled)
            if (after.useOwnKeys != before.useOwnKeys || prefs.contains(Keys.USE_OWN_KEYS)) putBoolean(Keys.USE_OWN_KEYS, after.useOwnKeys)
            putString(Keys.ENDPOINT_ID, after.endpointId)
            putString(Keys.R2_ENDPOINT, after.r2Endpoint)
            putString(Keys.BUCKET, after.bucket)
            putBoolean(Keys.INSTRUMENTAL, after.wantsInstrumental)
            putBoolean(Keys.LYRICS, after.wantsLyrics)
            putBoolean(Keys.TRANSCRIBE, after.transcribeWhenMissing)
            putString(Keys.QUALITY, after.quality.wire)
            putBoolean(Keys.CELLULAR, after.useCellular)
            putLong(Keys.PRICE, after.pricePerSecondMicroUsd)
            putLong(Keys.MONTHLY_CAP, after.monthlyCapMicroUsd)
            val caps = after.workerCaps
            if (caps == null) remove(Keys.WORKER_CAPS)
            else putString(Keys.WORKER_CAPS, CloudJson.encodeToString(CloudWorkerCaps.serializer(), caps))
        }.apply()
        _settings.value = after
    }

    override fun saveWorkerCaps(caps: CloudWorkerCaps?) = update { it.copy(workerCaps = caps) }

    // ─── Spend of removed jobs ──────────────────────────────────────────────────────────────

    override fun removedSpendMicroUsd(monthStartMs: Long): Long =
        if (prefs.getLong(Keys.REMOVED_SPEND_MONTH, 0L) == monthStartMs) max(prefs.getLong(Keys.REMOVED_SPEND_TOTAL, 0L), 0L) else 0L

    /** Remembers what removed jobs spent this month: the monthly cap counts the month, not what the list still shows. */
    @Synchronized
    override fun addRemovedSpend(microUsd: Long, monthStartMs: Long) {
        if (microUsd <= 0) return
        val total = min(removedSpendMicroUsd(monthStartMs) + microUsd, 1_000_000_000_000L)
        prefs.edit().putLong(Keys.REMOVED_SPEND_MONTH, monthStartMs).putLong(Keys.REMOVED_SPEND_TOTAL, total).apply()
    }

    // ─── Keys ───────────────────────────────────────────────────────────────────────────────

    override suspend fun secrets(): CloudSecretsState = withContext(Dispatchers.IO) {
        secretsLock.withLock {
            val store = openSecrets() ?: return@withLock CloudSecretsState(CloudSecrets.EMPTY, storageUnavailable = true)
            val secrets = try {
                CloudSecrets(
                    runpodKey = store.getString(Keys.RUNPOD_KEY, "").orEmpty(),
                    accessKeyId = store.getString(Keys.ACCESS_KEY_ID, "").orEmpty(),
                    secretAccessKey = store.getString(Keys.SECRET_ACCESS_KEY, "").orEmpty(),
                )
            } catch (error: Exception) {
                // A value the Keystore can no longer decrypt (the key was invalidated): treat it as gone.
                CloudSecrets.EMPTY
            }
            CloudSecretsState(secrets, keysMissing = prefs.getBoolean(Keys.KEYS_SAVED, false) && secrets.isEmpty)
        }
    }

    /** Saves the keys. False when secure storage is unavailable (nothing is written anywhere else). */
    suspend fun saveSecrets(secrets: CloudSecrets): Boolean = withContext(Dispatchers.IO) {
        secretsLock.withLock {
            val store = openSecrets() ?: return@withLock false
            val saved = store.edit()
                .putString(Keys.RUNPOD_KEY, secrets.runpodKey.trim())
                .putString(Keys.ACCESS_KEY_ID, secrets.accessKeyId.trim())
                .putString(Keys.SECRET_ACCESS_KEY, secrets.secretAccessKey.trim())
                .commit()
            if (saved) prefs.edit().putBoolean(Keys.KEYS_SAVED, !secrets.isEmpty).apply()
            saved
        }
    }

    /** "Forget keys": the encrypted values and the saved flag. */
    suspend fun forgetSecrets() = withContext(Dispatchers.IO) {
        secretsLock.withLock {
            openSecrets()?.edit()?.clear()?.commit()
            prefs.edit().putBoolean(Keys.KEYS_SAVED, false).apply()
        }
    }

    private fun openSecrets(): SharedPreferences? {
        secretPrefs?.let { return it }
        if (secretStoreFailed) return null
        return try {
            val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                appContext,
                SECRETS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { secretPrefs = it }
        } catch (error: Exception) {
            // Never a plaintext fallback for these keys (design §5). The exception text can't hold a key.
            secretStoreFailed = true
            null
        }
    }

    private object Keys {
        const val ENABLED = "enabled"
        const val ENDPOINT_ID = "endpoint_id"
        const val R2_ENDPOINT = "r2_endpoint"
        const val BUCKET = "bucket"
        const val INSTRUMENTAL = "out_instrumental"
        const val LYRICS = "out_lyrics"
        const val TRANSCRIBE = "out_transcribe"
        const val QUALITY = "quality"
        const val CELLULAR = "cellular"
        const val PRICE = "price_micro_usd"
        const val MONTHLY_CAP = "monthly_cap_micro_usd"
        const val WORKER_CAPS = "worker_caps"
        /** Set once keys were saved, so an empty store later reads as "keys missing". */
        const val KEYS_SAVED = "keys_saved"
        /** "Use my own keys"; unset until the person first chooses. */
        const val USE_OWN_KEYS = "use_own_keys"
        /** What jobs taken off the list spent this month (the month's start and the total), so clearing the list frees no cap room. */
        const val REMOVED_SPEND_MONTH = "removed_spend_month_ms"
        const val REMOVED_SPEND_TOTAL = "removed_spend_micro_usd"

        const val RUNPOD_KEY = "runpod_restricted"
        const val ACCESS_KEY_ID = "r2_access_key_id"
        const val SECRET_ACCESS_KEY = "r2_secret_access_key"
    }

    companion object {
        /** Excluded from Android backups in `backup_rules.xml` / `data_extraction_rules.xml`. */
        const val PREFS_NAME = "cloud_studio_settings"
        const val SECRETS_NAME = "cloud_studio_secrets"
    }
}
