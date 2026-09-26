package com.theveloper.pixelplay

import com.theveloper.pixelplay.presentation.navigation.navigateSafely

// import androidx.compose.ui.platform.LocalView // No longer needed for this
// import androidx.core.view.WindowInsetsCompat // No longer needed for this
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader as AndroidShader
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.view.Display
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.CallSuper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.theveloper.pixelplay.data.github.GitHubAnnouncementPropertiesService
import com.theveloper.pixelplay.data.github.PlayStoreAnnouncementRemoteConfig
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.data.preferences.NavBarStyle
import com.theveloper.pixelplay.data.preferences.sanitizeNavBarCornerRadius
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.data.worker.SyncProgress
import com.theveloper.pixelplay.presentation.components.AllFilesAccessDialog
import com.theveloper.pixelplay.presentation.components.AppSidebarDrawer
import com.theveloper.pixelplay.presentation.components.CrashReportDialog
import com.theveloper.pixelplay.presentation.components.DismissUndoBar
import com.theveloper.pixelplay.presentation.components.DrawerDestination
import com.theveloper.pixelplay.presentation.components.MiniPlayerBottomSpacer
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlayerInternalNavigationBar
import com.theveloper.pixelplay.presentation.components.PlayStoreAnnouncementDefaults
import com.theveloper.pixelplay.presentation.components.PlayStoreAnnouncementDialog
import com.theveloper.pixelplay.presentation.components.PlayStoreAnnouncementUiModel
import com.theveloper.pixelplay.presentation.components.UnifiedPlayerSheetV2
import com.theveloper.pixelplay.presentation.components.calculatePlayerSheetCollapsedTargetY
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.resolveNavBarSurfaceHeight
import com.theveloper.pixelplay.presentation.components.sanitizeNavigationBarBottomInset
import com.theveloper.pixelplay.presentation.navigation.AppNavigation
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.screens.SetupScreen
import com.theveloper.pixelplay.presentation.viewmodel.MainViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.theveloper.pixelplay.ui.theme.LocalHighContrastText
import com.theveloper.pixelplay.ui.theme.readHighContrastText
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.utils.CrashHandler
import com.theveloper.pixelplay.utils.AppLocaleManager
import com.theveloper.pixelplay.utils.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import com.theveloper.pixelplay.presentation.utils.AppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.NoOpHapticFeedback
import com.theveloper.pixelplay.utils.CrashLogData
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map


@Immutable
data class BottomNavItem(
    val label: String,
    @StringRes val labelResId: Int,
    @DrawableRes val iconResId: Int,
    @DrawableRes val selectedIconResId: Int? = null,
    val screen: Screen
)

private data class DismissUndoBarSlice(
    val isVisible: Boolean = false,
    val durationMillis: Long = 4000L
)

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    private var isUIVisiblyReady = false
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository // Inject here
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository
    @Inject
    lateinit var syncManager: SyncManager
    @Inject
    lateinit var appUpdateManager: com.theveloper.pixelplay.data.update.AppUpdateManager
    // For handling shortcut navigation - using StateFlow so composables can observe changes
    private val _pendingPlaylistNavigation = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val _pendingShuffleAll = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val requestAllFilesAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        // Handle the result in onResume
    }

    @CallSuper
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        LogUtils.d(this, "onCreate")
        val splashScreen = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()

        // Keep the splash only until the startup snapshot (theme, setup gate, start tab) is read,
        // so the first frame the user sees is the real one at full opacity, never a blank or a
        // wrong-theme frame. The deadline makes sure a slow disk can never hold the splash.
        val startupPrefs = mainViewModel.startupPrefs
        val splashDeadline = SystemClock.uptimeMillis() + SPLASH_MAX_HOLD_MS
        splashScreen.setKeepOnScreenCondition {
            startupPrefs.value == null && SystemClock.uptimeMillis() < splashDeadline
        }

        // LEER SEÑAL DE BENCHMARK
        val isBenchmarkMode = intent.getBooleanExtra("is_benchmark", false)
        val shouldBenchmarkRebuildDatabase =
            isBenchmarkMode && intent.getBooleanExtra("benchmark_rebuild_database", false)
        Log.i(
            "PixelPlayBenchmark",
            "onCreate benchmark=$isBenchmarkMode rebuildDatabase=$shouldBenchmarkRebuildDatabase"
        )
        if (shouldBenchmarkRebuildDatabase) {
            lifecycleScope.launch {
                userPreferencesRepository.setInitialSetupDone(true)
                Log.i("PixelPlayBenchmark", "Enqueueing benchmark database rebuild")
                syncManager.rebuildDatabase()
                delay(1_500L)
                playerViewModel.prepareBenchmarkPlayerFromLibrary()
            }
        }

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            // The splash is held until the startup snapshot is known, so these start from the
            // stored values instead of defaults that would flip a frame later.
            val startupSnapshot = startupPrefs.value
            val appThemeMode by themePreferencesRepository.appThemeModeFlow.collectAsStateWithLifecycle(
                initialValue = startupSnapshot?.themeMode ?: AppThemeMode.FOLLOW_SYSTEM
            )
            val showScrollbar by userPreferencesRepository.showScrollbarFlow.collectAsStateWithLifecycle(
                initialValue = startupSnapshot?.showScrollbar ?: true
            )
            val useDarkTheme = when (appThemeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                else -> systemDarkTheme
            }
            val isSetupCompleteFlag by mainViewModel.isSetupComplete.collectAsStateWithLifecycle()
            val startupState by startupPrefs.collectAsStateWithLifecycle()
            val isSetupComplete = isSetupCompleteFlag ?: startupState?.setupDone

            // Crash report dialog state
            var showCrashReportDialog by remember { mutableStateOf(false) }
            var crashLogData by remember { mutableStateOf<CrashLogData?>(null) }
            
            // Permissions Logic
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            @OptIn(ExperimentalPermissionsApi::class)
            val permissionState = rememberMultiplePermissionsState(permissions = permissions)
            // Determine if we need to show Setup based on completion OR missing permissions
            val permissionsValid = permissionState.allPermissionsGranted
            val showSetupScreen = remember(isSetupComplete, permissionsValid, isBenchmarkMode) {
                when {
                    isBenchmarkMode -> false
                    isSetupComplete == null -> null
                    else -> !isSetupComplete!! || !permissionsValid
                }
            }

            // Sync Trigger: When we are NOT showing setup (meaning permissions are good and setup is done)
            LaunchedEffect(showSetupScreen) {
                if (showSetupScreen == false) {
                     LogUtils.i(this, "Setup complete/skipped and permissions valid. Starting sync.")
                     mainViewModel.startSync()
                }
            }

            // Check for crash log when app starts
            LaunchedEffect(Unit) {
                if (isBenchmarkMode) return@LaunchedEffect
                // SharedPreferences first read: off the main thread, after the first frame.
                val savedCrash = withContext(Dispatchers.IO) {
                    if (CrashHandler.hasCrashLog()) CrashHandler.getCrashLog() else null
                }
                if (savedCrash != null) {
                    crashLogData = savedCrash
                    showCrashReportDialog = true
                }
            }

            CompositionLocalProvider(
                LocalShowScrollbar provides showScrollbar
            ) {
                PixelPlayTheme(
                    darkTheme = useDarkTheme
                ) {
                    // No launch fade: the splash stays up until the startup snapshot is ready,
                    // then the app draws at full opacity. (The old 100 ms blank + 600 ms fade
                    // also rendered the whole app into an offscreen layer during its busiest
                    // frames.)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (showSetupScreen == null) {
                            SetupGateLoadingScreen()
                        } else {
                            AnimatedContent(
                                targetState = showSetupScreen,
                                transitionSpec = {
                                    if (targetState) {
                                        // Transition to Setup
                                        fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                                    } else {
                                        // Transition from Setup to Main App
                                        scaleIn(initialScale = 0.95f, animationSpec = tween(450)) + fadeIn(animationSpec = tween(450)) togetherWith
                                                slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(450)) + fadeOut(animationSpec = tween(450))
                                    }
                                },
                                label = "SetupTransition"
                            ) { shouldShowSetup ->
                                if (shouldShowSetup) {
                                    SetupScreen(onSetupComplete = {
                                        // Repository-backed setup completion updates the gate automatically.
                                    })
                                } else {
                                    MainAppContent(playerViewModel, mainViewModel)
                                }
                            }
                        }

                        // Show crash report dialog if needed
                        if (showCrashReportDialog && crashLogData != null) {
                            CrashReportDialog(
                                crashLog = crashLogData!!,
                                onDismiss = {
                                    CrashHandler.clearCrashLog()
                                    crashLogData = null
                                    showCrashReportDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when {
            // Tapped an update notification. Handled here rather than in a receiver because only
            // a foreground app may open Android's install confirmation screen.
            intent.action == com.theveloper.pixelplay.data.update.AppUpdateNotifications.ACTION_OPEN_UPDATE -> {
                appUpdateManager.downloadAndInstall(announce = true)
                intent.action = null
            }

            // Handle shuffle all shortcut / tile
            intent.action == MainActivityIntentContract.ACTION_SHUFFLE_ALL -> {
                android.util.Log.d("TileDebug", "handleIntent: ACTION_SHUFFLE_ALL received")
                playerViewModel.triggerShuffleAllFromTile()
                intent.action = null // Clear action to prevent re-triggering
            }
            
            // Handle playlist shortcut
            intent.action == MainActivityIntentContract.ACTION_OPEN_PLAYLIST -> {
                intent.getStringExtra(MainActivityIntentContract.EXTRA_PLAYLIST_ID)?.let { playlistId ->
                    _pendingPlaylistNavigation.value = playlistId
                }
                intent.action = null
            }

            intent.getBooleanExtra("ACTION_SHOW_PLAYER", false) -> {
                playerViewModel.showPlayer()
            }

            intent.action == android.content.Intent.ACTION_VIEW && intent.data != null -> {
                intent.data?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }

            intent.action == android.content.Intent.ACTION_SEND && intent.type?.startsWith("audio/") == true -> {
                resolveStreamUri(intent)?.let { uri ->
                    persistUriPermissionIfNeeded(intent, uri)
                    playerViewModel.playExternalUri(uri)
                }
                clearExternalIntentPayload(intent)
            }
            
            intent.action == "com.theveloper.pixelplay.ACTION_PLAY_SONG" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                     intent.getParcelableExtra("song", com.theveloper.pixelplay.data.model.Song::class.java)?.let { song ->
                         playerViewModel.playSong(song)
                     }
                } else {
                     @Suppress("DEPRECATION")
                     intent.getParcelableExtra<com.theveloper.pixelplay.data.model.Song>("song")?.let { song ->
                         playerViewModel.playSong(song)
                     }
                }
                intent.action = null
            }
        }
    }
    
    private fun resolveStreamUri(intent: Intent): android.net.Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)?.let { return it }
        } else {
            @Suppress("DEPRECATION")
            val legacyUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
            if (legacyUri != null) return legacyUri
        }

        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                return clipData.getItemAt(0).uri
            }
        }

        return intent.data
    }

    private fun persistUriPermissionIfNeeded(intent: Intent, uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val hasPersistablePermission = intent.flags and android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            if (hasPersistablePermission) {
                val takeFlags = intent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags != 0) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (securityException: SecurityException) {
                        android.util.Log.w("MainActivity", "Unable to persist URI permission for $uri", securityException)
                    } catch (illegalArgumentException: IllegalArgumentException) {
                        android.util.Log.w("MainActivity", "Persistable URI permission not granted for $uri", illegalArgumentException)
                    }
                }
            }
        }
    }

    private fun clearExternalIntentPayload(intent: Intent) {
        intent.data = null
        intent.clipData = null
        intent.removeExtra(android.content.Intent.EXTRA_STREAM)
    }

    private fun openExternalUrl(url: String) {
        // Defense in depth: the announcement URL is fetched from a remote
        // properties file on GitHub. If that file is ever tampered with, we
        // must not let it launch arbitrary intents (`intent://...`,
        // `javascript:`, custom schemes, etc.). Allow only the Play Store host.
        val parsed = runCatching { url.toUri() }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host?.lowercase()
        val isPlayStore = scheme == "https" &&
            (host == "play.google.com" || host == "market.android.com")
        if (!isPlayStore) {
            LogUtils.w(this, "Refusing to open non-Play-Store announcement URL: $url")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, parsed)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            LogUtils.w(this, "No activity available to open URL: $url")
        }
    }

    private fun PlayStoreAnnouncementRemoteConfig.toUiModel(context: Context): PlayStoreAnnouncementUiModel {
        val fallback = PlayStoreAnnouncementDefaults.localizedTemplate(context)
        return fallback.copy(
            enabled = enabled,
            playStoreUrl = playStoreUrl ?: fallback.playStoreUrl,
            title = title ?: fallback.title,
            body = body ?: fallback.body,
            primaryActionLabel = primaryActionLabel ?: fallback.primaryActionLabel,
            dismissActionLabel = dismissActionLabel ?: fallback.dismissActionLabel,
            linkPendingMessage = linkPendingMessage ?: fallback.linkPendingMessage,
        )
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun SetupGateLoadingScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularWavyProgressIndicator()
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Preparing setup…",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Composable
    private fun MainAppContent(playerViewModel: PlayerViewModel, mainViewModel: MainViewModel) {
        Trace.beginSection("MainActivity.MainAppContent")
        val navController = rememberNavController()
        val isSyncing by mainViewModel.isSyncing.collectAsStateWithLifecycle()
        val isLibraryEmpty by mainViewModel.isLibraryEmpty.collectAsStateWithLifecycle()
        val hasCompletedInitialSync by mainViewModel.hasCompletedInitialSync.collectAsStateWithLifecycle()
        val syncProgress by mainViewModel.syncProgress.collectAsStateWithLifecycle()
        
        // isMediaControllerReady used below for playlist navigation gate
        val isMediaControllerReady by playerViewModel.isMediaControllerReady.collectAsStateWithLifecycle()
        
        // Observe pending playlist navigation
        val pendingPlaylistNav by _pendingPlaylistNavigation.collectAsStateWithLifecycle()
        var processedPlaylistId by remember { mutableStateOf<String?>(null) }
        
        LaunchedEffect(pendingPlaylistNav, isMediaControllerReady) {
            val playlistId = pendingPlaylistNav
            // Only process if we have a new playlist ID that hasn't been processed yet
            if (playlistId != null && playlistId != processedPlaylistId && isMediaControllerReady) {
                processedPlaylistId = playlistId
                // Wait for navigation graph to be ready (retry with delay)
                var success = false
                var attempts = 0
                while (!success && attempts < 50) { // 5 seconds max
                    try {
                        success = navController.navigateSafely(Screen.PlaylistDetail.createRoute(playlistId))
                        if (success) {
                            _pendingPlaylistNavigation.value = null
                        } else {
                            delay(100)
                            attempts++
                        }
                    } catch (e: IllegalArgumentException) {
                        delay(100)
                        attempts++
                    }
                }
            } else if (playlistId == null) {
                // Reset so the same playlist can be opened again
                processedPlaylistId = null
            }
        }

        // Estado para controlar si el indicador de carga puede mostrarse después de un delay
        var canShowLoadingIndicator by remember { mutableStateOf(false) }
        // Track when the loading indicator was first shown for minimum display time
        var loadingShownTimestamp by remember { mutableStateOf(0L) }
        val minimumDisplayDuration = 1500L // Show loading for at least 1.5 seconds

        val shouldPotentiallyShowLoading = isSyncing && isLibraryEmpty && !hasCompletedInitialSync

        LaunchedEffect(shouldPotentiallyShowLoading) {
            if (shouldPotentiallyShowLoading) {
                // Espera un breve período antes de permitir que se muestre el indicador de carga
                // Ajusta este valor según sea necesario (por ejemplo, 300-500 ms)
                delay(300L)
                // Vuelve a verificar la condición después del delay,
                // ya que el estado podría haber cambiado.
                if (mainViewModel.isSyncing.value && mainViewModel.isLibraryEmpty.value) {
                    canShowLoadingIndicator = true
                    loadingShownTimestamp = System.currentTimeMillis()
                }
            } else {
                // Ensure minimum display time before hiding
                if (canShowLoadingIndicator && loadingShownTimestamp > 0) {
                    val elapsed = System.currentTimeMillis() - loadingShownTimestamp
                    val remaining = minimumDisplayDuration - elapsed
                    if (remaining > 0) {
                        delay(remaining)
                    }
                }
                canShowLoadingIndicator = false
                loadingShownTimestamp = 0L
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            MainUI(playerViewModel, navController)

            // Muestra el LoadingOverlay solo si las condiciones se cumplen Y el delay ha pasado
            if (canShowLoadingIndicator) {
                LoadingOverlay(syncProgress)
            }
        }
        Trace.endSection() // End MainActivity.MainAppContent
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @Composable
    private fun MainUI(playerViewModel: PlayerViewModel, navController: NavHostController) {
        Trace.beginSection("MainActivity.MainUI")

        val commonNavItems = remember {
            persistentListOf(
                BottomNavItem("Home", R.string.nav_bar_home, R.drawable.rounded_home_24, R.drawable.home_24_rounded_filled, Screen.Home),
                BottomNavItem("Search", R.string.nav_bar_search, R.drawable.rounded_search_24, R.drawable.rounded_search_24, Screen.Search),
                BottomNavItem("Library", R.string.nav_bar_library, R.drawable.rounded_library_music_24, R.drawable.round_library_music_24, Screen.Library)
            )
        }
        // The route is read only where it is needed (derived states, the bar, the drawer), never
        // in MainUI's own body: reading it here recomposed this whole shell on every navigation.
        val currentBackStackEntryState = navController.currentBackStackEntryAsState()
        val currentRouteProvider: () -> String? = remember(currentBackStackEntryState) {
            { currentBackStackEntryState.value?.destination?.route }
        }
        var isSearchBarActive by remember { mutableStateOf(false) }
        // Read once: the start tab only matters for the NavHost's first composition.
        val startupPrefs = remember { mainViewModel.startupPrefs.value }

        val routesWithHiddenNavigationBar = remember {
            setOf(
                Screen.Settings.route,
                Screen.Plus.route,
                Screen.Accounts.route,
                Screen.PlaylistDetail.route,
                Screen.DailyMixScreen.route,
                Screen.RecentlyPlayed.route,
                Screen.GenreDetail.route,
                Screen.AlbumDetail.route,
                Screen.ArtistDetail.route,
                Screen.NavBarCrRad.route,
                Screen.About.route,
                Screen.OpenSourceLicenses.route,
                Screen.Stats.route,
                Screen.EditTransition.route,
                Screen.Experimental.route,
                Screen.ArtistSettings.route,
                Screen.Equalizer.route,
                Screen.SettingsCategory.route,
                Screen.DelimiterConfig.route,
                Screen.PaletteStyle.route,
                Screen.RecentlyPlayed.route,
                Screen.DeviceCapabilities.route,
                Screen.EasterEgg.route,
                Screen.WordDelimiterConfig.route,
                Screen.SpotifyDashboard.route,
                Screen.SpotifyBrowse.route
            )
        }
        val shouldHideNavigationBar by remember(currentRouteProvider) {
            derivedStateOf {
                val currentRoute = currentRouteProvider()
                if (currentRoute == Screen.Search.route && isSearchBarActive) {
                    true
                } else {
                    currentRoute?.let { route ->
                        routesWithHiddenNavigationBar.any { hiddenRoute ->
                            if (hiddenRoute.contains("{")) {
                                route.startsWith(hiddenRoute.substringBefore("{"))
                            } else {
                                route == hiddenRoute
                            }
                        }
                    } ?: false
                }
            }
        }

        val navBarStyle by playerViewModel.navBarStyle.collectAsStateWithLifecycle()
        val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
        val navBarCornerRadiusRaw by playerViewModel.navBarCornerRadius.collectAsStateWithLifecycle()
        val navBarCornerRadius = sanitizeNavBarCornerRadius(navBarCornerRadiusRaw)
        val useSmoothCorners by playerViewModel.useSmoothCorners.collectAsStateWithLifecycle()
        val isMiniPlayerDismissing by playerViewModel.isMiniPlayerDismissing.collectAsStateWithLifecycle()
        val hapticsEnabled by playerViewModel.hapticsEnabled.collectAsStateWithLifecycle()
        val disableBlurAllOver by playerViewModel.disableBlurAllOver.collectAsStateWithLifecycle()
        val predictiveBackCollapseFraction by playerViewModel.predictiveBackCollapseFraction.collectAsStateWithLifecycle()

        // System high-contrast text, re-read whenever the app comes back. The lyrics chrome uses it
        // for heavier fills.
        val a11yContext = LocalContext.current
        var highContrastText by remember { mutableStateOf(readHighContrastText(a11yContext)) }
        LifecycleResumeEffect(a11yContext) {
            highContrastText = readHighContrastText(a11yContext)
            onPauseOrDispose { }
        }

        val rootView = LocalView.current
        val platformHapticFeedback = LocalHapticFeedback.current
        val appHapticsConfig = remember(hapticsEnabled) {
            AppHapticsConfig(enabled = hapticsEnabled)
        }
        val scopedHapticFeedback = remember(platformHapticFeedback, appHapticsConfig.enabled) {
            if (appHapticsConfig.enabled) platformHapticFeedback else NoOpHapticFeedback
        }

        val systemNavBarInset = sanitizeNavigationBarBottomInset(
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        )

        LaunchedEffect(hapticsEnabled, rootView) {
            rootView.isHapticFeedbackEnabled = hapticsEnabled
            rootView.rootView?.isHapticFeedbackEnabled = hapticsEnabled
        }

        val horizontalPadding = if (navBarStyle == NavBarStyle.DEFAULT) {
            if (systemNavBarInset > 30.dp) 16.dp else 14.dp
        } else {
            0.dp
        }
        val animatedBottomBarPadding by animateDpAsState(
            targetValue = if (navBarStyle == NavBarStyle.FULL_WIDTH) 0.dp else systemNavBarInset,
            animationSpec = tween(400),
            label = "BottomBarPadding"
        )
        val bottomBarPadding = animatedBottomBarPadding
        val navBarHeight = resolveNavBarSurfaceHeight(navBarStyle, systemNavBarInset, navBarCompactMode)
        val navBarOccupiedHeight by remember(systemNavBarInset, navBarCompactMode) {
            derivedStateOf { resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode) }
        }
        val navBarVisibilityProgressState = animateFloatAsState(
            targetValue = if (shouldHideNavigationBar) 0f else 1f,
            animationSpec = tween(
                durationMillis = 220,
                easing = LinearOutSlowInEasing
            ),
            label = "NavBarVisibilityProgress"
        )
        // The visibility progress animates for 220 ms on every route that hides or shows the bar.
        // It is read only inside these derived states (and the bar's graphicsLayer), never in
        // composition: the flags below flip once per transition, and the mini player's bottom
        // margin reaches the player sheet as a state it reads outside composition.
        val miniPlayerBottomMarginState = remember(systemNavBarInset, navBarOccupiedHeight) {
            derivedStateOf {
                val visibleNavBarOccupiedHeight =
                    navBarOccupiedHeight * navBarVisibilityProgressState.value
                if (visibleNavBarOccupiedHeight > systemNavBarInset) {
                    visibleNavBarOccupiedHeight
                } else {
                    systemNavBarInset
                }
            }
        }
        val shouldRenderNavigationBar by remember(shouldHideNavigationBar) {
            derivedStateOf {
                !shouldHideNavigationBar || navBarVisibilityProgressState.value > 0.01f
            }
        }
        val isNavBarEffectivelyHidden by remember(shouldHideNavigationBar) {
            derivedStateOf {
                shouldHideNavigationBar && navBarVisibilityProgressState.value <= 0.01f
            }
        }

        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val announcementService = remember { GitHubAnnouncementPropertiesService() }
        val context = LocalContext.current
        var playStoreAnnouncement by remember {
            mutableStateOf(PlayStoreAnnouncementDefaults.localizedTemplate(context))
        }
        var showPlayStoreAnnouncement by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (PlayStoreAnnouncementDefaults.LOCAL_PREVIEW_ENABLED) {
                playStoreAnnouncement = PlayStoreAnnouncementDefaults.hardcodedPreview(this@MainActivity)
                showPlayStoreAnnouncement = true
                return@LaunchedEffect
            }

            // Network work stays out of the first seconds of animation.
            delay(ANNOUNCEMENT_FETCH_DELAY_MS)
            announcementService.fetchPlayStoreAnnouncement()
                .onSuccess { remoteConfig ->
                    val resolvedAnnouncement = remoteConfig.toUiModel(this@MainActivity)
                    playStoreAnnouncement = resolvedAnnouncement
                    showPlayStoreAnnouncement = resolvedAnnouncement.enabled
                }
                .onFailure { throwable ->
                    LogUtils.w(
                        this@MainActivity,
                        "Remote announcement unavailable. Keeping popup disabled. ${throwable.message ?: ""}",
                    )
                }
        }

        LaunchedEffect(userPreferencesRepository) {
            userPreferencesRepository.clearDeprecatedPlayerSheetPreference()
        }

        CompositionLocalProvider(
            LocalAppHapticsConfig provides appHapticsConfig,
            LocalHapticFeedback provides scopedHapticFeedback,
            LocalHighContrastText provides highContrastText
        ) {
            RouteAwareSidebarDrawer(
                drawerState = drawerState,
                currentRouteProvider = currentRouteProvider,
                onDestinationSelected = { destination ->
                    scope.launch { drawerState.close() }
                    when (destination) {
                        DrawerDestination.Home -> navController.navigateSafely(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                        DrawerDestination.Equalizer -> navController.navigateSafely(Screen.Equalizer.route)
                        DrawerDestination.Settings -> navController.navigateSafely(Screen.Settings.route)
                    }
                }
        ) {

                Scaffold(
                modifier = Modifier
                    .fillMaxSize(),
                // The root Surface below already paints the background; a second opaque
                // full-screen fill here was pure overdraw.
                containerColor = Color.Transparent,
                bottomBar = {
                    if (shouldRenderNavigationBar) {
                        val currentSongId by remember {
                            playerViewModel.stablePlayerState
                                .map { it.currentSong?.id }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = null)
                        val showPlayerContentArea = currentSongId != null
                        val navBarElevation = 3.dp

                        val animatedNavBarCornerRadius = animateDpAsState(
                            targetValue = navBarCornerRadius.dp,
                            animationSpec = tween(400),
                            label = "NavBarCornerRadius"
                        )

                        val animatedDefaultTopCornerRadius = animateDpAsState(
                            targetValue = if (showPlayerContentArea && !isMiniPlayerDismissing) 10.dp else navBarCornerRadius.dp,
                            animationSpec = tween(400),
                            label = "NavBarDefaultTopCornerRadius"
                        )

                        // Shape is now resolved per quantized radius in the draw phase
                        // (see the Surface graphicsLayer below) instead of being
                        // re-remembered every animation frame.

                        var componentHeightPx by remember { mutableStateOf(0) }
                        val density = LocalDensity.current
                        val shadowOverflowPx = remember(navBarElevation, density) {
                            with(density) { (navBarElevation * 8).toPx() }
                        }
                        val bottomBarPaddingPx = remember(bottomBarPadding, density) {
                            with(density) { bottomBarPadding.toPx() }
                        }
                        val navBarElevationPx = remember(navBarElevation, density) {
                            with(density) { navBarElevation.toPx() }
                        }
                        val navBarShapeCache = remember { NavBarShapeCache() }

                        // Top/bottom corner radii, read in the DRAW phase by the clip layer. Kept as
                        // a lambda (not a remembered value) on purpose: it reads animated state,
                        // and evaluating it during draw is what lets the radius animate without
                        // recomposing or re-laying-out the bar.
                        val navBarCorners: () -> Pair<Dp, Dp> = {
                            val fraction = playerViewModel.playerContentExpansionFraction.value
                            val safeFraction = fraction.coerceIn(0f, 1f)
                            val topDp = when {
                                navBarStyle == NavBarStyle.DEFAULT -> animatedDefaultTopCornerRadius.value
                                navBarStyle == NavBarStyle.FULL_WIDTH -> lerp(navBarCornerRadius.dp, 26.dp, safeFraction)
                                showPlayerContentArea -> if (fraction < 0.2f) {
                                    lerp(navBarCornerRadius.dp, 26.dp, (fraction / 0.2f).coerceIn(0f, 1f))
                                } else {
                                    26.dp
                                }
                                else -> navBarCornerRadius.dp
                            }
                            val bottomDp = when (navBarStyle) {
                                NavBarStyle.FULL_WIDTH -> 0.dp
                                else -> animatedNavBarCornerRadius.value
                            }
                            topDp to bottomDp
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(navBarOccupiedHeight)
                        ) {
                            val onSearchIconDoubleTap = remember(playerViewModel) {
                                { playerViewModel.onSearchNavIconDoubleTapped() }
                            }

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(bottom = bottomBarPadding)
                                    .onSizeChanged { componentHeightPx = it.height }
                                    .graphicsLayer {
                                        // Slide-down hide: covers both the player-expansion
                                        // hide and the route-based hide as a pure translation,
                                        // so child items never resize or get clipped/squished.
                                        val expansionHide = if (showPlayerContentArea) {
                                            playerViewModel.playerContentExpansionFraction.value.coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        val routeHide = (1f - navBarVisibilityProgressState.value).coerceIn(0f, 1f)
                                        val hideFraction = maxOf(expansionHide, routeHide)
                                        translationY = (componentHeightPx + shadowOverflowPx + bottomBarPaddingPx) * hideFraction
                                        alpha = 1f
                                    }
                                    .height(navBarHeight)
                                    .padding(horizontal = horizontalPadding)
                                    .graphicsLayer {
                                        // Animated corner shape resolved in the draw phase:
                                        // animating the radius re-clips this layer only — no
                                        // recomposition and no layout pass for the bar.
                                        val (topDp, bottomDp) = navBarCorners()
                                        shape = navBarShapeCache.get(this, topDp.toPx(), bottomDp.toPx(), useSmoothCorners)
                                        clip = true
                                        shadowElevation = navBarElevationPx
                                    },
                                color = NavigationBarDefaults.containerColor
                            ) {
                                PlayerInternalNavigationBar(
                                    navController = navController,
                                    navItems = commonNavItems,
                                    currentRoute = currentRouteProvider(),
                                    navBarStyle = navBarStyle,
                                    compactMode = navBarCompactMode,
                                    bottomBarPadding = bottomBarPadding,
                                    onSearchIconDoubleTap = onSearchIconDoubleTap,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                ) { innerPadding ->
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val containerHeight = this.maxHeight
                        val screenHeightPx = remember(containerHeight, density) {
                            with(density) { containerHeight.toPx() }
                        }

                        val showPlayerContentInitially by remember {
                            playerViewModel.stablePlayerState
                                .map { it.currentSong?.id != null }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = false)
                        val routesWithHiddenMiniPlayer = remember { setOf(Screen.NavBarCrRad.route) }
                        val shouldHideMiniPlayer by remember(currentRouteProvider) {
                            derivedStateOf { currentRouteProvider() in routesWithHiddenMiniPlayer }
                        }

                        val miniPlayerH = with(density) { MiniPlayerHeight.toPx() }
                        val totalSheetHeightWhenContentCollapsedPx = if (showPlayerContentInitially && !shouldHideMiniPlayer) miniPlayerH else 0f

                        val spacerPx = with(density) { MiniPlayerBottomSpacer.toPx() }
                        // Follows the nav bar's show/hide animation frame by frame, so it is
                        // handed to the sheet as a provider rather than recomposing it per frame.
                        val sheetCollapsedTargetYState = remember(
                            screenHeightPx,
                            totalSheetHeightWhenContentCollapsedPx,
                            spacerPx,
                            density
                        ) {
                            derivedStateOf {
                                calculatePlayerSheetCollapsedTargetY(
                                    containerHeightPx = screenHeightPx,
                                    collapsedContentHeightPx = totalSheetHeightWhenContentCollapsedPx,
                                    bottomMarginPx = with(density) {
                                        miniPlayerBottomMarginState.value.toPx()
                                    },
                                    bottomSpacerPx = spacerPx
                                )
                            }
                        }
                        val sheetCollapsedTargetYProvider = remember(sheetCollapsedTargetYState) {
                            { sheetCollapsedTargetYState.value }
                        }

                        val expansionFractionProvider = remember(playerViewModel.playerContentExpansionFraction) {
                            { playerViewModel.playerContentExpansionFraction.value }
                        }
                        val blurEffectCache = remember { BlurEffectCache() }

                        // Main-root screens (the only ones that read this padding) always show
                        // the nav bar, so their bottom padding is the bar's height whether or
                        // not the bar is rendered right now. Using the Scaffold's live padding
                        // made the exiting Home re-lay out its whole list ~220 ms into every
                        // push to a screen that hides the bar.
                        val layoutDirection = LocalLayoutDirection.current
                        val innerPaddingStart = innerPadding.calculateStartPadding(layoutDirection)
                        val innerPaddingTop = innerPadding.calculateTopPadding()
                        val innerPaddingEnd = innerPadding.calculateEndPadding(layoutDirection)
                        val rootScreenPadding = remember(
                            innerPaddingStart,
                            innerPaddingTop,
                            innerPaddingEnd,
                            navBarOccupiedHeight
                        ) {
                            PaddingValues(
                                start = innerPaddingStart,
                                top = innerPaddingTop,
                                end = innerPaddingEnd,
                                bottom = navBarOccupiedHeight
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val expansion = expansionFractionProvider()
                                    val fraction = (expansion * (1f - predictiveBackCollapseFraction)).coerceIn(0f, 1f)
                                    // Fully covered by the opaque expanded player: skip drawing
                                    // (and re-blurring) the page. It comes back the moment the
                                    // player moves or a back gesture starts.
                                    alpha = if (fraction >= 1f) 0f else 1f
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        if (disableBlurAllOver) {
                                            renderEffect = null
                                        } else {
                                            // Quantize to 2px steps: rebuild the RenderEffect only
                                            // when the blur crosses a step, reuse the cached object
                                            // every other frame.
                                            val quantizedBlurPx = (fraction * 120f / 2f).roundToInt() * 2f
                                            renderEffect = blurEffectCache.get(quantizedBlurPx)
                                        }
                                    }
                                }
                        ) {
                            AppNavigation(
                                playerViewModel = playerViewModel,
                                navController = navController,
                                paddingValues = rootScreenPadding,
                                initialLaunchTab = startupPrefs?.launchTab,
                                userPreferencesRepository = userPreferencesRepository,
                                onSearchBarActiveChange = { isSearchBarActive = it },
                                onOpenSidebar = { scope.launch { drawerState.open() } }
                            )
                        }

                        val isExpandedOrExpanding by remember {
                            derivedStateOf {
                                playerViewModel.playerContentExpansionFraction.value > 0.01f
                            }
                        }
                        AnimatedVisibility(
                            visible = isExpandedOrExpanding,
                            enter = fadeIn(animationSpec = tween(durationMillis = 350)),
                            exit = fadeOut(animationSpec = tween(durationMillis = 350)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Hidden under the opaque expanded player: skip the fill.
                                    // Same folded fraction as the page, so it returns with it
                                    // when a predictive back gesture starts.
                                    .graphicsLayer {
                                        val fraction = expansionFractionProvider() * (1f - predictiveBackCollapseFraction)
                                        alpha = if (fraction >= 1f) 0f else 1f
                                    }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(
                                            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.6f
                                        )
                                    )
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            playerViewModel.collapsePlayerSheet()
                                        }
                                    }
                            )
                        }

                        UnifiedPlayerSheetV2(
                            playerViewModel = playerViewModel,
                            sheetCollapsedTargetYProvider = sheetCollapsedTargetYProvider,
                            collapsedStateHorizontalPadding = horizontalPadding,
                            hideMiniPlayer = shouldHideMiniPlayer,
                            containerHeight = containerHeight,
                            navController = navController,
                            isNavBarHidden = isNavBarEffectivelyHidden
                        )

                        val dismissUndoBarSlice by remember {
                            playerViewModel.playerUiState
                                .map { state ->
                                    DismissUndoBarSlice(
                                        isVisible = state.showDismissUndoBar,
                                        durationMillis = state.undoBarVisibleDuration
                                    )
                                }
                                .distinctUntilChanged()
                        }.collectAsStateWithLifecycle(initialValue = DismissUndoBarSlice())
                        val onUndoDismissPlaylist = remember(playerViewModel) {
                            { playerViewModel.undoDismissPlaylist() }
                        }
                        val onCloseDismissUndoBar = remember(playerViewModel) {
                            { playerViewModel.hideDismissUndoBar() }
                        }

                        AnimatedVisibility(
                            visible = dismissUndoBarSlice.isVisible,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = innerPadding.calculateBottomPadding() + MiniPlayerBottomSpacer)
                                .padding(horizontal = horizontalPadding)
                        ) {
                            DismissUndoBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(MiniPlayerHeight)
                                    .padding(horizontal = 14.dp),
                                onUndo = onUndoDismissPlaylist,
                                onClose = onCloseDismissUndoBar,
                                durationMillis = dismissUndoBarSlice.durationMillis
                            )
                        }

                        if (showPlayStoreAnnouncement) {
                            PlayStoreAnnouncementDialog(
                                announcement = playStoreAnnouncement,
                                onDismiss = { showPlayStoreAnnouncement = false },
                                onOpenPlayStore = { url ->
                                    showPlayStoreAnnouncement = false
                                    openExternalUrl(url)
                                }
                            )
                        }
                    }
                }
            }
        }

        Trace.endSection()
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun LoadingOverlay(syncProgress: SyncProgress) {
        // Animate progress smoothly instead of jumping in steps
        val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
            targetValue = syncProgress.progress,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
            ),
            label = "SyncProgressAnimation"
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
                .clickable(enabled = false, onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                CircularWavyProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Preparing your library...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (syncProgress.hasProgress) {
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scanned ${syncProgress.currentCount} of ${syncProgress.totalCount} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }


    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        LogUtils.d(this, "onStart")
        playerViewModel.onMainActivityStart()

        // PlayerViewModel owns the one MediaController and keeps the service bound while the UI
        // exists. A second controller here was never read, and each connection makes the
        // session serialise the whole queue again.
    }

    override fun onStop() {
        super.onStop()
        LogUtils.d(this, "onStop")
    }

    override fun onResume() {
        super.onResume()
    }

    /**
     * Without an explicit request the system parks this app at 60 Hz (its default frame-rate
     * category), even on a 120 Hz panel. Pick the fastest mode at the current resolution.
     * (Ported from NexHome, where it was measured on the same phone.)
     */
    private fun requestHighestRefreshRate() {
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest.refreshRate
        }
    }

    private companion object {
        /** Upper bound on how long the splash may wait for the startup snapshot. */
        const val SPLASH_MAX_HOLD_MS = 1_000L

        /** The remote announcement check waits until the app has settled. */
        const val ANNOUNCEMENT_FETCH_DELAY_MS = 5_000L
    }
}

/**
 * Reads the current route in its own recomposition scope, so a navigation re-runs only this
 * wrapper (and the drawer sheet), not the shell around it. The drawer's content lambda is the
 * same instance across these recompositions, so the app content itself is skipped.
 */
@Composable
private fun RouteAwareSidebarDrawer(
    drawerState: androidx.compose.material3.DrawerState,
    currentRouteProvider: () -> String?,
    onDestinationSelected: (DrawerDestination) -> Unit,
    content: @Composable () -> Unit
) {
    AppSidebarDrawer(
        drawerState = drawerState,
        selectedRoute = currentRouteProvider() ?: Screen.Home.route,
        onDestinationSelected = onDestinationSelected,
        content = content
    )
}

/**
 * Caches the (expensive) RenderEffect Java object so we don't allocate a new
 * blur every animation frame. The radius is quantized at the call site, so this
 * only rebuilds ~25 times across the whole expand animation instead of 60+/sec.
 */
private class BlurEffectCache {
    private var lastRadiusPx: Float = Float.NaN
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (radiusPx <= 0f) {
            lastRadiusPx = 0f
            cached = null
            return null
        }
        if (radiusPx != lastRadiusPx) {
            lastRadiusPx = radiusPx
            cached = AndroidRenderEffect
                .createBlurEffect(radiusPx, radiusPx, AndroidShader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
        return cached
    }
}

/**
 * Returns a cached Shape instance for a quantized (top, bottom) radius pair.
 * Because the instance identity is stable while the radii don't move past a
 * sub-pixel threshold, the graphics layer reuses its cached Outline between
 * frames and only re-clips when the radius actually changes.
 */
private class NavBarShapeCache {
    private var lastTopPx: Float = Float.NaN
    private var lastBottomPx: Float = Float.NaN
    private var lastSmooth: Boolean = true
    private var cached: androidx.compose.ui.graphics.Shape = RectangleShape

    fun get(
        density: androidx.compose.ui.unit.Density,
        topPx: Float,
        bottomPx: Float,
        smooth: Boolean
    ): androidx.compose.ui.graphics.Shape {
        if (smooth == lastSmooth &&
            !lastTopPx.isNaN() &&
            kotlin.math.abs(topPx - lastTopPx) < 0.5f &&
            kotlin.math.abs(bottomPx - lastBottomPx) < 0.5f
        ) {
            return cached
        }
        lastTopPx = topPx
        lastBottomPx = bottomPx
        lastSmooth = smooth
        cached = with(density) {
            DynamicSmoothCornerShape(
                useSmoothCorners = smooth,
                topRadius = topPx.toDp(),
                bottomRadius = bottomPx.toDp()
            )
        }
        return cached
    }
}

/**
 * Fixed-radius corner shape. Swaps AbsoluteSmoothCornerShape for a plain
 * RoundedCornerShape when smooth corners are disabled in settings. The radius
 * values are identical in both branches, so the animated radius behavior is
 * unchanged regardless of which delegate is active. The resulting Outline is
 * cached per (size, layoutDirection) so repeated draws are cheap.
 */
private class DynamicSmoothCornerShape(
    private val useSmoothCorners: Boolean,
    private val topRadius: androidx.compose.ui.unit.Dp,
    private val bottomRadius: androidx.compose.ui.unit.Dp
) : androidx.compose.ui.graphics.Shape {

    private var cachedSize: androidx.compose.ui.geometry.Size =
        androidx.compose.ui.geometry.Size.Unspecified
    private var cachedLayoutDirection: androidx.compose.ui.unit.LayoutDirection? = null
    private var cachedOutline: androidx.compose.ui.graphics.Outline? = null

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        cachedOutline?.let {
            if (cachedSize == size && cachedLayoutDirection == layoutDirection) return it
        }

        val delegate: androidx.compose.ui.graphics.Shape = if (useSmoothCorners) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTL = topRadius,
                smoothnessAsPercentTL = 60,
                cornerRadiusTR = topRadius,
                smoothnessAsPercentTR = 60,
                cornerRadiusBL = bottomRadius,
                smoothnessAsPercentBL = 60,
                cornerRadiusBR = bottomRadius,
                smoothnessAsPercentBR = 60
            )
        } else {
            RoundedCornerShape(
                topStart = topRadius,
                topEnd = topRadius,
                bottomEnd = bottomRadius,
                bottomStart = bottomRadius
            )
        }

        return delegate.createOutline(size, layoutDirection, density).also {
            cachedSize = size
            cachedLayoutDirection = layoutDirection
            cachedOutline = it
        }
    }
}
