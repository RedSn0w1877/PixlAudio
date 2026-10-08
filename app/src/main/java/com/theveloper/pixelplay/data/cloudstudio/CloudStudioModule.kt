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

    @Provides
    @Singleton
    @CloudStudioHttpClient
    fun provideCloudOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // A 100 MB FLAC on a slow uplink: the write timeout is per write, not for the whole upload.
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideCloudStudioSettings(@ApplicationContext context: Context): CloudStudioSettings = CloudStudioSettings(context)

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
    ): CloudStudioEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                isUnmetered = {
                    val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    connectivity != null && !connectivity.isActiveNetworkMetered
                },
            )
        )
    }
}
