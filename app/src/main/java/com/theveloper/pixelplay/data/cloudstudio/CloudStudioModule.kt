package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.WorkManager
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.worker.AutomaticStudioManager
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Cloud Studio's own OkHttp client: no logging interceptor (the shared client prints request lines in debug builds,
 * and a presigned URL's query must never reach logcat), and no forced User-Agent. Only RunPod and the R2 bucket
 * use it, never YouTube.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudStudioHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CloudStudioModule {
    private const val BUILT_IN_BLOB_ASSET = "cloud_defaults.enc"

    @Provides
    @Singleton
    @CloudStudioHttpClient
    fun provideCloudOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // A 100 MB FLAC on a slow uplink: the write timeout is per write, not for the whole upload.
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // RunPod and R2 never redirect. Following one could carry a presigned query (signature, access key ID) to
        // another host, or over plain http (the app's network config allows cleartext for local servers).
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * PixlAudio's built-in cloud keys: the bundled blob opened with the key the build carries
     * (`BuildConfig.CLOUD_DEFAULTS_KEY`, empty in forks and pull-request builds = no built-in keys). The blob is read
     * and opened once, on an IO thread, when first asked for.
     */
    @Provides
    @Singleton
    fun provideCloudBuiltInConfig(@ApplicationContext context: Context): CloudBuiltInConfig =
        BuiltInCloudConfigProvider(BuildConfig.CLOUD_DEFAULTS_KEY, readBlob = {
            context.assets.open(BUILT_IN_BLOB_ASSET).use { it.readBytes() }
        })

    @Provides
    @Singleton
    fun provideCloudStudioSettings(
        @ApplicationContext context: Context,
        builtIn: CloudBuiltInConfig,
    ): CloudStudioSettings = CloudStudioSettings(context, builtIn)

    @Provides
    @Singleton
    fun provideCloudStudioEngine(
        @ApplicationContext context: Context,
        settings: CloudStudioSettings,
        @CloudStudioHttpClient client: OkHttpClient,
        musicRepository: MusicRepository,
        lyricsRepository: LyricsRepository,
        audioCacheManager: AudioCacheManager,
        spotifyStreamProxy: SpotifyStreamProxy,
        spotifyDao: SpotifyDao,
        instrumentalIndex: TaisInstrumentalIndex,
        automaticStudioManager: Lazy<AutomaticStudioManager>,
        workManager: WorkManager,
        builtIn: CloudBuiltInConfig,
    ): CloudStudioEngine {
        // The poll loop and the scheduler run here; an unexpected failure is logged (its class only) and never crashes.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error -> timber.log.Timber.w("Cloud Studio: %s", error.javaClass.simpleName) }
        )
        val http = OkHttpCloudHttp(client)
        val root = File(context.noBackupFilesDir, "cloud_studio")
        val host = LiveCloudStudioHost(
            context = context,
            musicRepository = musicRepository,
            lyricsRepository = lyricsRepository,
            audioCacheManager = audioCacheManager,
            spotifyStreamProxy = spotifyStreamProxy,
            spotifyDao = spotifyDao,
            instrumentalIndex = instrumentalIndex,
            onLyricsUpdated = { songId -> automaticStudioManager.get().noteLyricsUpdated(songId) },
            scope = scope,
        )
        return CloudStudioEngine(
            CloudStudioDependencies(
                settings = settings,
                store = CloudJobStore(root),
                host = host,
                preparer = LiveCloudAudioPreparer(context),
                transfers = http,
                makeRunPod = { config ->
                    if (config.hasRunPod) RunPodJobsClient(http, config.trimmedEndpointId, config.trimmedRunpodKey) else null
                },
                makeObjects = { config ->
                    val location = config.location
                    if (location != null && config.credentials.isComplete) {
                        CloudObjectClient(http, S3Signer(config.credentials, location))
                    } else null
                },
                scheduler = CloudStudioScheduler(workManager, scope),
                scope = scope,
                build = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                stagingDir = File(root, "staging"),
                builtIn = builtIn,
                isUnmetered = {
                    val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    connectivity != null && !connectivity.isActiveNetworkMetered
                },
            )
        )
    }
}
