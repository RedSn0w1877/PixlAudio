package com.theveloper.pixelplay.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.PixelPlayApplication
import com.theveloper.pixelplay.data.database.AlbumArtThemeDao
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.database.FavoritesDao
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.database.AiCacheDao
import com.theveloper.pixelplay.data.database.AiUsageDao
import com.theveloper.pixelplay.data.database.LocalPlaylistDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.PixelPlayDatabase
import com.theveloper.pixelplay.data.database.SearchHistoryDao
import com.theveloper.pixelplay.data.database.TransitionDao
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.preferences.dataStore
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.network.deezer.DeezerApiService
import com.theveloper.pixelplay.data.network.lyrics.LrcLibApiService
import com.theveloper.pixelplay.data.repository.ArtistImageRepository
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.repository.LyricsRepositoryImpl
import com.theveloper.pixelplay.data.repository.MediaStoreSongRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.repository.MusicRepositoryImpl
import com.theveloper.pixelplay.data.repository.SongRepository
import com.theveloper.pixelplay.data.repository.TransitionRepository
import com.theveloper.pixelplay.data.repository.TransitionRepositoryImpl
import com.theveloper.pixelplay.data.repository.FolderTreeBuilder
import dagger.Module
import dagger.Provides
import dagger.Lazy
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


/**
 * Concurrent image fetch+decode slots. Enough to keep a fling scroll fed without letting a
 * few hundred queued requests each grab an IO thread and thrash. Scales with the device but
 * stays bounded on both ends.
 */
private val IMAGE_PIPELINE_PARALLELISM =
    Runtime.getRuntime().availableProcessors().coerceIn(4, 8)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplication(@ApplicationContext app: Context): PixelPlayApplication {
        return app as PixelPlayApplication
    }

    @Singleton
    @Provides
    fun provideGson(): com.google.gson.Gson {
        return com.google.gson.Gson()
    }

    @OptIn(UnstableApi::class)
    @Singleton
    @Provides
    fun provideSessionToken(@ApplicationContext context: Context): androidx.media3.session.SessionToken {
        return androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.theveloper.pixelplay.data.service.MusicService::class.java)
        )
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Singleton
    @Provides
    fun provideJson(): Json { // Proveer Json
        return Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    @Singleton
    @Provides
    @AppScope
    fun provideAppCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Singleton
    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Singleton
    @Provides
    fun providePixelPlayDatabase(@ApplicationContext context: Context): PixelPlayDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            PixelPlayDatabase::class.java,
            "pixelplay_database"
        )
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .addMigrations(
                PixelPlayDatabase.MIGRATION_1_2,
                PixelPlayDatabase.MIGRATION_2_3,
                PixelPlayDatabase.MIGRATION_3_4,
                PixelPlayDatabase.MIGRATION_4_5
            )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

        // P2-4: Only allow destructive migration in debug builds.
        // In release, a migration bug will crash the app (revealing the problem)
        // rather than silently wiping user data (playlists, favorites, statistics).
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }

        return builder.build()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: PixelPlayDatabase): AlbumArtThemeDao {
        return database.albumArtThemeDao()
    }

    @Singleton
    @Provides
    fun provideSongCacheDao(database: PixelPlayDatabase): com.theveloper.pixelplay.data.database.SongCacheDao {
        return database.songCacheDao()
    }

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: PixelPlayDatabase): SearchHistoryDao { // NUEVO MÉTODO
        return database.searchHistoryDao()
    }

    @Singleton
    @Provides
    fun provideMusicDao(database: PixelPlayDatabase): MusicDao { // Proveer MusicDao
        return database.musicDao()
    }

    @Singleton
    @Provides
    fun provideTransitionDao(database: PixelPlayDatabase): TransitionDao {
        return database.transitionDao()
    }

    @Singleton
    @Provides
    fun provideEngagementDao(database: PixelPlayDatabase): EngagementDao {
        return database.engagementDao()
    }

    @Singleton
    @Provides
    fun provideFavoritesDao(database: PixelPlayDatabase): FavoritesDao {
        return database.favoritesDao()
    }

    @Singleton
    @Provides
    fun provideLyricsDao(database: PixelPlayDatabase): LyricsDao {
        return database.lyricsDao()
    }

    @Singleton
    @Provides
    fun provideSpotifyDao(database: PixelPlayDatabase): SpotifyDao {
        return database.spotifyDao()
    }

    @Singleton
    @Provides
    fun provideLocalPlaylistDao(database: PixelPlayDatabase): LocalPlaylistDao {
        return database.localPlaylistDao()
    }

    @Singleton
    @Provides
    fun provideAiCacheDao(database: PixelPlayDatabase): AiCacheDao {
        return database.aiCacheDao()
    }

    @Provides
    fun provideAiUsageDao(database: PixelPlayDatabase): AiUsageDao {
        return database.aiUsageDao()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        return ImageLoader.Builder(context)
            // This dispatcher runs fetch (disk/network I/O) *and* decode, not just decode.
            // On Dispatchers.Default that parks CPU-pool threads — of which there are only
            // `availableProcessors()` — on blocking reads, starving everything else scheduled
            // there and effectively serialising decodes behind disk latency. Dispatchers.IO
            // is the right pool for that, and Coil's own default; the cap keeps a burst of
            // fling-scroll requests from spawning an unbounded number of decodes at once.
            .dispatcher(Dispatchers.IO.limitedParallelism(IMAGE_PIPELINE_PARALLELISM))
            .allowHardware(true) // Re-enable hardware bitmaps for better performance
            .memoryCache {
                MemoryCache.Builder(context)
                    // Hard 40 MB cap instead of 20%-of-heap. Rationale:
                    //  - On large-heap devices (Pixel 8 etc.) the percentage
                    //    expanded to ~80–100 MB, far beyond what an album-art
                    //    workload needs.
                    //  - allowHardware(true) keeps most decoded pixels in GPU
                    //    memory, so the MemoryCache mostly tracks Bitmap
                    //    references — 40 MB still buffers ~100+ album arts.
                    //  - Tighter cap = less GC pressure and less thermal
                    //    headroom spent on memory pressure during long sessions.
                    .maxSizeBytes(40 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache
                    .build()
            }
            .respectCacheHeaders(false) // Ignore server cache headers, always cache
            .build()
    }

    @Provides
    @Singleton
    fun provideLyricsRepository(
        @ApplicationContext context: Context,
        lrcLibApiService: LrcLibApiService,
        lyricsDao: LyricsDao,
        okHttpClient: OkHttpClient
    ): LyricsRepository {
        return LyricsRepositoryImpl(
            context = context,
            lrcLibApiService = lrcLibApiService,
            lyricsDao = lyricsDao,
            okHttpClient = okHttpClient
        )
    }

    @Provides
    @Singleton
    fun provideSongRepository(
        @ApplicationContext context: Context,
        mediaStoreObserver: com.theveloper.pixelplay.data.observer.MediaStoreObserver,
        favoritesDao: FavoritesDao,
        userPreferencesRepository: UserPreferencesRepository,
        musicDao: MusicDao
    ): SongRepository {
        return MediaStoreSongRepository(
            context = context,
            mediaStoreObserver = mediaStoreObserver,
            favoritesDao = favoritesDao,
            userPreferencesRepository = userPreferencesRepository,
            musicDao = musicDao
        )
    }

    @Provides
    @Singleton
    fun provideFolderTreeBuilder(): FolderTreeBuilder {
        return FolderTreeBuilder()
    }

    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        userPreferencesRepository: UserPreferencesRepository,
        playlistPreferencesRepository: PlaylistPreferencesRepository,
        searchHistoryDao: SearchHistoryDao,
        musicDao: MusicDao,
        lyricsRepository: LyricsRepository,
        songRepository: SongRepository,
        favoritesDao: FavoritesDao,
        artistImageRepository: ArtistImageRepository,
        folderTreeBuilder: FolderTreeBuilder,
        spotifyDao: com.theveloper.pixelplay.data.database.SpotifyDao
    ): MusicRepository {
        return MusicRepositoryImpl(
            context = context,
            userPreferencesRepository = userPreferencesRepository,
            playlistPreferencesRepository = playlistPreferencesRepository,
            searchHistoryDao = searchHistoryDao,
            musicDao = musicDao,
            lyricsRepository = lyricsRepository,
            songRepository = songRepository,
            favoritesDao = favoritesDao,
            artistImageRepository = artistImageRepository,
            folderTreeBuilder = folderTreeBuilder,
            spotifyDao = spotifyDao
        )

    }

    @Provides
    @Singleton
    fun provideTransitionRepository(
        transitionRepositoryImpl: TransitionRepositoryImpl
    ): TransitionRepository {
        return transitionRepositoryImpl
    }

    @Singleton
    @Provides
    fun provideSongMetadataEditor(
        @ApplicationContext context: Context,
        musicDao: MusicDao,
        userPreferencesRepository: UserPreferencesRepository
    ): SongMetadataEditor {
        return SongMetadataEditor(context, musicDao, userPreferencesRepository)
    }

    /**
     * Provee una instancia singleton de OkHttpClient con logging e interceptor de User-Agent.
     * Retry logic with backoff is handled in coroutine-based callers.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // HEADERS (not BODY) so we never print response bodies that may contain
            // cookies, tokens, or third-party API payloads. Headers are still useful
            // for debugging request paths and status codes.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // Redact every header that can carry a credential or session token.
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            redactHeader("x-goog-api-key")
            redactHeader("X-Emby-Token")
            redactHeader("X-Emby-Authorization")
            redactHeader("X-MediaBrowser-Token")
        }
        
        // Connection pool with optimized connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        return OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Add User-Agent header (required by some APIs)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithUserAgent = originalRequest.newBuilder()
                    .header("User-Agent", "PixelPlayer/1.0 (Android; Music Player)")
                    .build()
                chain.proceed(requestWithUserAgent)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia de OkHttpClient con timeouts para búsquedas de lyrics.
     * Includes DNS resolver, modern TLS, connection pool, and connection retry.
     */
    @Provides
    @Singleton
    @FastOkHttpClient
    fun provideFastOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS)
        
        // Connection pool to reuse connections for better performance
        val connectionPool = okhttp3.ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 30,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS
        )
        
        // Use Cloudflare and Google DNS to avoid potential DNS issues
        val dns = okhttp3.Dns { hostname ->
            try {
                // First try system DNS
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                // Fallback to manual resolution if system DNS fails
                java.net.InetAddress.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .dns(dns)
            .connectionPool(connectionPool)
            // Use HTTP/1.1 to avoid HTTP/2 stream issues with some servers
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            // Use modern TLS connection spec
            .connectionSpecs(listOf(
                okhttp3.ConnectionSpec.MODERN_TLS,
                okhttp3.ConnectionSpec.COMPATIBLE_TLS
            ))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            // Enable built-in retry on connection failure
            .retryOnConnectionFailure(true)
            // Add headers
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithHeaders = originalRequest.newBuilder()
                    .header("User-Agent", "PixelPlayer/1.0 (Android; Music Player)")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(requestWithHeaders)
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Provee una instancia singleton de Retrofit para la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideRetrofit(@FastOkHttpClient okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee una instancia singleton del servicio de la API de LRCLIB.
     */
    @Provides
    @Singleton
    fun provideLrcLibApiService(retrofit: Retrofit): LrcLibApiService {
        return retrofit.create(LrcLibApiService::class.java)
    }

    /**
     * Provee una instancia de Retrofit para la API de Deezer.
     */
    @Provides
    @Singleton
    @DeezerRetrofit
    fun provideDeezerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.deezer.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Provee el servicio de la API de Deezer.
     */
    @Provides
    @Singleton
    fun provideDeezerApiService(@DeezerRetrofit retrofit: Retrofit): DeezerApiService {
        return retrofit.create(DeezerApiService::class.java)
    }

    /**
     * Cliente HTTP para YouTube (InnerTube, base.js y los streams de googlevideo).
     *
     * Es distinto del cliente compartido por una razón concreta: aquel añade un
     * interceptor que **sobrescribe** `User-Agent` con "PixelPlayer/1.0". InnerTube
     * compara ese encabezado con el cliente que se declara en el cuerpo de la petición
     * (iPhone, Android VR…), y si no cuadran rechaza la llamada. Aquí no hay interceptor
     * de User-Agent para que cada petición pueda declarar el suyo.
     *
     * Los timeouts son más largos porque `base.js` ronda los dos megas y los streams de
     * audio son descargas continuas.
     */
    @Provides
    @Singleton
    @YouTubeOkHttpClient
    fun provideYouTubeOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 60,
                    timeUnit = java.util.concurrent.TimeUnit.SECONDS
                )
            )
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Retrofit para el servidor de cuentas de Spotify (solo OAuth).
     */
    @Provides
    @Singleton
    @SpotifyAccountsRetrofit
    fun provideSpotifyAccountsRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(com.theveloper.pixelplay.data.spotify.SpotifyAuthManager.ACCOUNTS_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideSpotifyAuthApiService(
        @SpotifyAccountsRetrofit retrofit: Retrofit
    ): com.theveloper.pixelplay.data.network.spotify.SpotifyAuthApiService {
        return retrofit.create(com.theveloper.pixelplay.data.network.spotify.SpotifyAuthApiService::class.java)
    }

    /**
     * Retrofit para la Web API de Spotify. Usa timeouts más holgados que el cliente por
     * defecto: importar una biblioteca grande encadena decenas de páginas.
     */
    @Provides
    @Singleton
    @SpotifyRetrofit
    fun provideSpotifyRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val client = okHttpClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(com.theveloper.pixelplay.data.spotify.SpotifyAuthManager.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideSpotifyApiService(
        @SpotifyRetrofit retrofit: Retrofit
    ): com.theveloper.pixelplay.data.network.spotify.SpotifyApiService {
        return retrofit.create(com.theveloper.pixelplay.data.network.spotify.SpotifyApiService::class.java)
    }

    /**
     * Retrofit para el OAuth de Google (flujo de dispositivo de YouTube). Usa el cliente de
     * YouTube (sin el interceptor que reescribe el User-Agent) para no chocar con nada.
     */
    @Provides
    @Singleton
    @com.theveloper.pixelplay.di.GoogleOAuthRetrofit
    fun provideGoogleOAuthRetrofit(
        @com.theveloper.pixelplay.di.YouTubeOkHttpClient okHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager.OAUTH_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGoogleOAuthService(
        @com.theveloper.pixelplay.di.GoogleOAuthRetrofit retrofit: Retrofit
    ): com.theveloper.pixelplay.data.network.youtube.GoogleOAuthService {
        return retrofit.create(com.theveloper.pixelplay.data.network.youtube.GoogleOAuthService::class.java)
    }

    /**
     * Provee el repositorio de imágenes de artistas.
     */
    @Provides
    @Singleton
    fun provideArtistImageRepository(
        deezerApiService: DeezerApiService,
        musicDao: MusicDao
    ): ArtistImageRepository {
        return ArtistImageRepository(deezerApiService, musicDao)
    }
}
