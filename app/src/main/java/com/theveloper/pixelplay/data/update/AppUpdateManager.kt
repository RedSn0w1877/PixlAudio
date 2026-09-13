package com.theveloper.pixelplay.data.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.ConnectivityManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.worker.AppUpdateCheckWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val update: AvailableUpdate) : AppUpdateState
    /** [percent] is null when the server does not say how big the file is. */
    data class Downloading(val update: AvailableUpdate, val percent: Int?) : AppUpdateState
    data class ReadyToInstall(val update: AvailableUpdate) : AppUpdateState
    data class Installing(val update: AvailableUpdate) : AppUpdateState
    data class Failed(val message: String, val update: AvailableUpdate?) : AppUpdateState
}

val AppUpdateState.pendingUpdate: AvailableUpdate?
    get() = when (this) {
        is AppUpdateState.Available -> update
        is AppUpdateState.Downloading -> update
        is AppUpdateState.ReadyToInstall -> update
        is AppUpdateState.Installing -> update
        is AppUpdateState.Failed -> update
        AppUpdateState.Idle, AppUpdateState.Checking, AppUpdateState.UpToDate -> null
    }

private class UpdateCheckException(val httpCode: Int) : IOException("GitHub answered HTTP $httpCode")
private class UpdateVerificationException(@StringRes val messageRes: Int) : Exception()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppUpdateEntryPoint {
    fun appUpdateManager(): AppUpdateManager
}

/**
 * Over-the-air updates from the app's public GitHub releases.
 *
 * Flow: check the releases listing → download the APK for this phone's ABI → verify it (size,
 * GitHub's SHA-256, same package, newer version code, same signing key) → hand it to Android's
 * [PackageInstaller]. Android itself refuses an APK signed with a different key; we check first
 * only so the user gets an explanation instead of a bare "App not installed".
 *
 * Only the release build updates itself. The debug build is a different app (`.debug` suffix),
 * so a release APK could never install over it.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    // El cliente compartido corta a los 8 s; un APK de ~170 MB necesita bastante más margen.
    private val client = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializa comprobación y descarga: el worker periódico y la pantalla pueden coincidir.
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedVersionName: String = packageInfo.versionName ?: "?"
    val installedVersionCode: Long = packageInfo.longVersionCode

    val canSelfUpdate: Boolean = BuildConfig.BUILD_TYPE == "release"

    fun checkNow() {
        scope.launch { check() }
    }

    /** One tap: download if needed, then install. [announce] shows a toast when a download starts. */
    fun downloadAndInstall(announce: Boolean = false) {
        scope.launch {
            val update = _state.value.pendingUpdate ?: check()
            if (update != null && canSelfUpdate) {
                if (announce && !verifiedApkFile(update).exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.app_update_toast_downloading, update.versionName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                if (download(update)) installDownloaded()
            }
        }
    }

    fun schedulePeriodicChecks() {
        if (!canSelfUpdate) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w(it, "Unable to schedule app update checks") }
    }

    /**
     * Background check. Downloads only on an unmetered network (the APK is large); on mobile
     * data it just says an update exists. Each version is announced at most once per stage.
     */
    suspend fun runBackgroundCheck() {
        if (!canSelfUpdate) return
        val update = check() ?: return
        if (isOnUnmeteredNetwork() && download(update)) {
            notifyOnce(update, downloaded = true)
        } else {
            notifyOnce(update, downloaded = false)
        }
    }

    fun onInstallStatus(status: Int, systemMessage: String?) {
        val update = _state.value.pendingUpdate
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Cancelled on Android's confirmation screen: not an error, just offer Install again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (update != null) _state.value = AppUpdateState.ReadyToInstall(update)
            }
            else -> {
                Timber.w("App update install failed: status=$status message=$systemMessage")
                _state.value = AppUpdateState.Failed(context.getString(installFailureMessage(status)), update)
            }
        }
    }

    /** Runs in the freshly installed version (`MY_PACKAGE_REPLACED`). */
    fun onAppReplaced() {
        val dir = File(context.noBackupFilesDir, UPDATES_DIR)
        val installedByUs = dir.listFiles()?.any { it.name.endsWith(".apk") } == true
        dir.deleteRecursively()
        if (installedByUs) AppUpdateNotifications.showUpdated(context, installedVersionName)
    }

    private suspend fun check(): AvailableUpdate? = mutex.withLock { checkLocked() }

    private fun checkLocked(): AvailableUpdate? {
        _state.value = AppUpdateState.Checking
        try {
            val update = fetchLatestUpdate()
            deleteDownloadsExcept(update)
            _state.value = when {
                update == null -> AppUpdateState.UpToDate
                verifiedApkFile(update).exists() -> AppUpdateState.ReadyToInstall(update)
                else -> AppUpdateState.Available(update)
            }
            return update
        } catch (e: Exception) {
            Timber.w(e, "App update check failed")
            _state.value = AppUpdateState.Failed(messageFor(e, R.string.app_update_error_offline), null)
            return null
        }
    }

    private fun fetchLatestUpdate(): AvailableUpdate? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateCheckException(response.code)
            val releases = json.decodeFromString<List<GitHubReleaseDto>>(checkNotNull(response.body).string())
            GitHubReleaseParser.pickUpdate(releases, installedVersionCode, Build.SUPPORTED_ABIS.toList())
        }
    }

    private suspend fun download(update: AvailableUpdate): Boolean = mutex.withLock { downloadLocked(update) }

    private fun downloadLocked(update: AvailableUpdate): Boolean {
        val target = verifiedApkFile(update)
        if (target.exists()) {
            _state.value = AppUpdateState.ReadyToInstall(update)
            return true
        }
        deleteDownloadsExcept(null)
        val partial = File(updatesDir(), "${target.name}.part")
        _state.value = AppUpdateState.Downloading(update, null)
        try {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(update.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = checkNotNull(response.body)
                val total = body.contentLength().takeIf { it > 0L } ?: update.sizeBytes.takeIf { it > 0L }
                var written = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sha256.update(buffer, 0, read)
                            written += read
                            if (total != null) {
                                val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.value = AppUpdateState.Downloading(update, percent)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
            if (update.sizeBytes > 0L && partial.length() != update.sizeBytes) {
                throw UpdateVerificationException(R.string.app_update_error_incomplete)
            }
            val actualSha = sha256.digest().toHex()
            if (update.sha256 != null && update.sha256 != actualSha) {
                throw UpdateVerificationException(R.string.app_update_error_corrupt)
            }
            verifyApk(partial)
            if (!partial.renameTo(target)) throw IOException("Could not move the verified update into place")
            _state.value = AppUpdateState.ReadyToInstall(update)
            return true
        } catch (e: Exception) {
            partial.delete()
            Timber.w(e, "App update download failed")
            _state.value = AppUpdateState.Failed(messageFor(e, R.string.app_update_error_incomplete), update)
            return false
        }
    }

    private fun verifyApk(file: File) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateVerificationException(R.string.app_update_error_corrupt)
        if (archive.packageName != context.packageName) {
            throw UpdateVerificationException(R.string.app_update_error_wrong_app)
        }
        if (archive.longVersionCode <= installedVersionCode) {
            throw UpdateVerificationException(R.string.app_update_error_not_newer)
        }
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archiveCerts = certificateDigests(archive.signingInfo)
        val installedCerts = certificateDigests(installed.signingInfo)
        // Si Android no pudo leer alguna de las firmas, el instalador del sistema lo comprobará igual.
        if (archiveCerts.isNotEmpty() && installedCerts.isNotEmpty() && archiveCerts.none { it in installedCerts }) {
            throw UpdateVerificationException(R.string.app_update_error_different_key)
        }
    }

    /** With key rotation the new APK's history still contains the old certificate, so compare histories. */
    private fun certificateDigests(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signatures.orEmpty()
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }
            .toSet()
    }

    private suspend fun installDownloaded() {
        val update = _state.value.pendingUpdate ?: return
        val file = verifiedApkFile(update)
        if (!file.exists()) {
            _state.value = AppUpdateState.Available(update)
            return
        }
        _state.value = AppUpdateState.Installing(update)
        try {
            withContext(Dispatchers.IO) { AppUpdateInstaller.commit(context, file) }
        } catch (e: Exception) {
            Timber.w(e, "Could not start the app update install")
            _state.value = AppUpdateState.Failed(context.getString(R.string.app_update_error_install_generic), update)
        }
    }

    private fun notifyOnce(update: AvailableUpdate, downloaded: Boolean) {
        val key = "notified_${update.versionCode}_${if (downloaded) "ready" else "available"}"
        if (prefs.getBoolean(key, false)) return
        AppUpdateNotifications.showUpdate(context, update, downloaded)
        prefs.edit().putBoolean(key, true).apply()
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !connectivity.isActiveNetworkMetered
    }

    private fun updatesDir(): File = File(context.noBackupFilesDir, UPDATES_DIR).apply { mkdirs() }

    private fun verifiedApkFile(update: AvailableUpdate): File =
        File(updatesDir(), "update-${update.versionCode}.apk")

    private fun deleteDownloadsExcept(keep: AvailableUpdate?) {
        val keepName = keep?.let { verifiedApkFile(it).name }
        updatesDir().listFiles()?.forEach { file ->
            if (file.name != keepName) file.delete()
        }
    }

    private fun messageFor(error: Throwable, @StringRes networkFallback: Int): String {
        val res = when (error) {
            is UpdateVerificationException -> error.messageRes
            is UpdateCheckException -> when (error.httpCode) {
                403, 429 -> R.string.app_update_error_rate_limited
                404 -> R.string.app_update_error_not_found
                else -> R.string.app_update_error_check_generic
            }
            is IOException -> networkFallback
            else -> R.string.app_update_error_check_generic
        }
        return context.getString(res)
    }

    @StringRes
    private fun installFailureMessage(status: Int): Int = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.app_update_error_different_key
        PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.app_update_error_storage
        PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.app_update_error_blocked
        PackageInstaller.STATUS_FAILURE_INVALID -> R.string.app_update_error_corrupt
        else -> R.string.app_update_error_install_generic
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val REPO_OWNER = "RedSn0w1877"
        const val REPO_NAME = "PixlAudio"
        private const val PREFS_NAME = "app_updates"
        private const val UPDATES_DIR = "app-updates"
        private const val PERIODIC_WORK_NAME = "app_update_check"
    }
}
