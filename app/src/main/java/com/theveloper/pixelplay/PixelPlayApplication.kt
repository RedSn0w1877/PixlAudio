package com.theveloper.pixelplay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnosticsController
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsTree
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsWatchers
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.presentation.viewmodel.LibraryStateHolder
import com.theveloper.pixelplay.presentation.viewmodel.ThemeStateHolder
import com.theveloper.pixelplay.utils.AlbumArtCacheManager
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.CrashHandler
import com.theveloper.pixelplay.utils.AppLocaleManager
import com.theveloper.pixelplay.utils.MediaMetadataRetrieverPool
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class PixelPlayApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: dagger.Lazy<ImageLoader>

    @Inject
    lateinit var localArtworkCoilFetcherFactory: dagger.Lazy<com.theveloper.pixelplay.data.image.LocalArtworkCoilFetcher.Factory>

    @Inject
    lateinit var themeStateHolder: dagger.Lazy<ThemeStateHolder>

    @Inject
    lateinit var artistImageRepository: dagger.Lazy<ArtistImageRepository>

    @Inject
    lateinit var libraryStateHolder: dagger.Lazy<LibraryStateHolder>

    @Inject
    lateinit var userPreferencesRepository: dagger.Lazy<UserPreferencesRepository>

    @Inject
    lateinit var advancedPerformanceDiagnosticsController: dagger.Lazy<AdvancedPerformanceDiagnosticsController>

    @Inject
    lateinit var spotifyRepository: dagger.Lazy<com.theveloper.pixelplay.data.spotify.SpotifyRepository>

    @Inject
    lateinit var automaticStudioManager: dagger.Lazy<com.theveloper.pixelplay.data.worker.AutomaticStudioManager>

    @Inject
    lateinit var cloudStudioEngine: dagger.Lazy<com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine>

    @Inject
    lateinit var cloudAutoPrepareScheduler: dagger.Lazy<com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepareScheduler>

    @Inject
    lateinit var appUpdateManager: dagger.Lazy<com.theveloper.pixelplay.data.update.AppUpdateManager>

    @Inject
    lateinit var aiPreferencesRepository: dagger.Lazy<com.theveloper.pixelplay.data.preferences.AiPreferencesRepository>

    @Inject
    lateinit var smartFeatureDefaults: dagger.Lazy<com.theveloper.pixelplay.data.preferences.SmartFeatureDefaults>

    @Inject
    lateinit var gemmaEngine: dagger.Lazy<com.theveloper.pixelplay.data.ai.local.GemmaLiteRtEngine>

    @Inject
    lateinit var youTubeAuthManager: dagger.Lazy<com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager>

    @Inject
    lateinit var wav2Vec2Aligner: dagger.Lazy<com.theveloper.pixelplay.data.tais.lyrics.TaisWav2Vec2Aligner>

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val appVisibilityDispatcher = Dispatchers.Default.limitedParallelism(1)

    // AÑADE EL COMPANION OBJECT
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pixelplay_music_channel"
        lateinit var instance: PixelPlayApplication
            private set
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            libraryStateHolder.get().restoreAfterTrimIfNeeded()
            // Off the main thread (its graph is built on first use), on one serial lane so
            // visible/hidden calls keep their order.
            startupScope.launch(appVisibilityDispatcher) {
                automaticStudioManager.get().setAppVisible(true)
                // Cloud Studio watches its jobs every 15 s while the app is on screen (nothing while it's off).
                cloudStudioEngine.get().setAppVisible(true)
                // "Prepare songs ahead in the cloud": the 6-hourly worker follows the two switches (a no-op while off).
                cloudAutoPrepareScheduler.get().sync()
                // Listening history from Spotify / YouTube Music: the worker syncs only when 6 h have passed.
                com.theveloper.pixelplay.data.history.ListeningHistorySyncWorker.enqueue(this@PixelPlayApplication)
            }
        }
        override fun onStop(owner: LifecycleOwner) {
            startupScope.launch(appVisibilityDispatcher) {
                automaticStudioManager.get().setAppVisible(false)
                cloudStudioEngine.get().setAppVisible(false)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        instance = this
        super.onCreate()

        // Benchmark variant intentionally restarts/kills app process during tests.
        // Avoid persisting those events as user-facing crash reports.
        if (BuildConfig.BUILD_TYPE != "benchmark") {
            CrashHandler.install(this)
        }

        // Shareable diagnostics log (Settings > About > Diagnostics): redacted WARN+ lines and key events in a
        // ~1 MB rotating file, in release too. Initialised after CrashHandler so its crash hook runs first
        // and hands on to the crash dialog's handler.
        DiagnosticsLog.init(this)
        Timber.plant(DiagnosticsTree())

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release tree: only WARN/ERROR/WTF - no DEBUG/VERBOSE/INFO
            Timber.plant(ReleaseTree())
        }
        DiagnosticsLog.event(
            "App",
            "start version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) commit=${BuildConfig.GIT_SHA} " +
                "built=${BuildConfig.BUILD_DATE} type=${BuildConfig.BUILD_TYPE} sdk=${Build.VERSION.SDK_INT} " +
                "device=${Build.MANUFACTURER} ${Build.MODEL}"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "PixlAudio Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        advancedPerformanceDiagnosticsController.get().start(startupScope)
        // Construir el repositorio ya arranca la colección de estados de emparejamiento
        // (ver SpotifyMatchStateCache) para que el indicador por canción esté listo desde
        // el principio, sin depender de que el usuario abra la pantalla de Spotify primero.
        // Built on a background thread: the session lives in EncryptedSharedPreferences, whose
        // Keystore/Tink setup is one of the costliest things at a cold start. Reading
        // isLoggedIn here warms it before any screen asks.
        startupScope.launch {
            DiagnosticsWatchers.watchSpotifySession(this, spotifyRepository.get().isLoggedIn)
        }
        DiagnosticsWatchers.watchWork(this, startupScope)
        // Same for the YouTube session (cookie + visitorData live in EncryptedSharedPreferences).
        startupScope.launch {
            runCatching { youTubeAuthManager.get().cookie }
        }
        // Sing preview clips are transient: none outlives the process that played it.
        startupScope.launch {
            runCatching {
                com.theveloper.pixelplay.data.tais.SingClipStore.clearAll(
                    com.theveloper.pixelplay.data.tais.SingClipStore.directory(this@PixelPlayApplication)
                )
            }
        }

        // On-device AI became the default: move setups that could never answer (a cloud
        // provider without its key, Ollama/Custom without a URL) to it, once.
        startupScope.launch {
            runCatching { aiPreferencesRepository.get().migrateProviderIfNeeded() }
                .onFailure { Timber.w(it, "AI provider migration failed") }
        }

        // Smart features (learning, Home mixes, history import, automatic lyrics) are on from the start:
        // once per install, switch on any that an old build or backup left off.
        startupScope.launch {
            runCatching { smartFeatureDefaults.get().applyOnce() }
                .onFailure { Timber.w(it, "Smart feature defaults failed") }
        }
        // And again when setup finishes, so the end of setup leaves them on (setup itself writes none of them).
        startupScope.launch {
            runCatching {
                val setupDone = userPreferencesRepository.get().initialSetupDoneFlow
                if (!setupDone.first()) {
                    setupDone.first { it }
                    smartFeatureDefaults.get().applyNow()
                }
            }.onFailure { Timber.w(it, "Smart feature defaults after setup failed") }
        }

        startupScope.launch {
            com.theveloper.pixelplay.data.worker.AiWorkerManager(this@PixelPlayApplication).ensurePeriodicDiscovery()
            appUpdateManager.get().schedulePeriodicChecks()
            AlbumArtUtils.migrateLegacyCacheLocation(this@PixelPlayApplication)
            val savedLimit = runCatching {
                userPreferencesRepository.get().albumArtCacheLimitMbFlow.first()
            }.getOrNull()
            if (savedLimit != null) {
                AlbumArtCacheManager.configuredCacheLimitMb = savedLimit.toLong()
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return imageLoader.get().newBuilder()
            .components {
                add(localArtworkCoilFetcherFactory.get())
                // Opt-in (SmartImage display requests only): thumbnail-sized CDN variants.
                add(
                    com.theveloper.pixelplay.data.image.RemoteThumbnailInterceptor(
                        diskCache = { imageLoader.get().diskCache }
                    )
                )
            }
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        imageLoader.get().memoryCache?.trimMemory(level)

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            themeStateHolder.get().trimMemory(level)
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            artistImageRepository.get().trimMemory()
            MediaMetadataRetrieverPool.clear()
        }

        libraryStateHolder.get().trimMemory(level)

        // The downloaded AI model holds ~1 GB while loaded; it reloads on the next request.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            gemmaEngine.get().release()
        }

        // The 378 MB lyric-alignment model reloads on the next job; never closed while one runs.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            startupScope.launch { wav2Vec2Aligner.get().releaseSession() }
        }

        if (
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            imageLoader.get().memoryCache?.clear()
        }
    }

    // 3. Sobrescribe el método para proveer la configuración de WorkManager
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
