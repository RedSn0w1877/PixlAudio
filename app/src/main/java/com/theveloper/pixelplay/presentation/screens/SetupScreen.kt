package com.theveloper.pixelplay.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.AppThemeMode
import com.theveloper.pixelplay.presentation.components.BackupModuleSelectionDialog
import com.theveloper.pixelplay.presentation.screens.setup.ConfettiBurst
import com.theveloper.pixelplay.presentation.screens.setup.BatteryHero
import com.theveloper.pixelplay.presentation.screens.setup.LibraryHeaderPreview
import com.theveloper.pixelplay.presentation.screens.setup.LocalSetupReduceMotion
import com.theveloper.pixelplay.presentation.screens.setup.MiniThemePreview
import com.theveloper.pixelplay.presentation.screens.setup.NavBarPreview
import com.theveloper.pixelplay.presentation.screens.setup.NotificationHero
import com.theveloper.pixelplay.presentation.screens.setup.SetupBackground
import com.theveloper.pixelplay.presentation.screens.setup.SetupBottomActions
import com.theveloper.pixelplay.presentation.screens.setup.SetupChoiceTile
import com.theveloper.pixelplay.presentation.screens.setup.SetupHeroCluster
import com.theveloper.pixelplay.presentation.screens.setup.SetupPageFrame
import com.theveloper.pixelplay.presentation.screens.setup.SetupStatusChip
import com.theveloper.pixelplay.presentation.screens.setup.SetupToggleCard
import com.theveloper.pixelplay.presentation.screens.setup.SetupTopBar
import com.theveloper.pixelplay.presentation.screens.setup.SetupTopBarHeight
import com.theveloper.pixelplay.presentation.screens.setup.WelcomeArt
import com.theveloper.pixelplay.presentation.screens.setup.rememberPageEntrance
import com.theveloper.pixelplay.presentation.screens.setup.setupEntrance
import com.theveloper.pixelplay.presentation.spotify.SpotifyAccountViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SetupEvent
import com.theveloper.pixelplay.presentation.viewmodel.SetupUiState
import com.theveloper.pixelplay.presentation.viewmodel.SetupViewModel
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.readReduceMotion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * First-run setup. One pager, one persistent frame:
 *
 *  - an animated tonal backdrop with a big shape that morphs from step to step,
 *  - a top row with back button and segmented progress,
 *  - the page itself (illustration, headline, short copy and the page's own controls),
 *  - a pinned bottom area with the single primary action and, when the step is optional, a skip.
 *
 * Nothing blocks the user: every permission step can be skipped, and the current step survives the app
 * being killed in the middle of setup (the finished flag is only written on the last page).
 */
@Composable
fun SetupScreen(
    setupViewModel: SetupViewModel = hiltViewModel(),
    spotifyViewModel: SpotifyAccountViewModel = hiltViewModel(),
    onSetupComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reduceMotion = remember(context) { readReduceMotion(context) }

    val uiState by setupViewModel.uiState.collectAsStateWithLifecycle()
    val latestUiState by rememberUpdatedState(uiState)
    val isSpotifyLoggedIn by spotifyViewModel.isLoggedIn.collectAsStateWithLifecycle()

    var selectedBackupUri by remember { mutableStateOf<Uri?>(null) }
    var showCornerRadiusOverlay by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizationsNow(context)) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }

    val backupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedBackupUri = uri
        if (uri != null) {
            setupViewModel.inspectBackupFile(uri)
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationsAsked = true
        setupViewModel.checkPermissions(context)
    }

    // Permissions and battery settings are changed outside the app, so re-read them on every resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                setupViewModel.checkPermissions(context)
                batteryUnrestricted = isIgnoringBatteryOptimizationsNow(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pages = remember { buildSetupPages(Build.VERSION.SDK_INT) }
    var savedPage by rememberSaveable { mutableStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = savedPage.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size }
    )
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.collect { savedPage = it }
    }
    val position = remember(pagerState) {
        { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    val goToPage: (Int) -> Unit = { target ->
        val bounded = target.coerceIn(0, pages.lastIndex)
        if (bounded != pagerState.currentPage) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch {
                pagerState.animateScrollToPage(
                    page = bounded,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)
                )
            }
        }
    }
    val goNext: () -> Unit = { goToPage(pagerState.currentPage + 1) }
    val goBack: () -> Unit = { goToPage(pagerState.currentPage - 1) }

    // Restoring a backup brings back the look and layout settings, so jump straight to the first step
    // that still needs something from the user (a permission), or to the end.
    LaunchedEffect(Unit) {
        setupViewModel.events.collectLatest { event ->
            when (event) {
                is SetupEvent.Message -> {
                    Toast.makeText(context, event.value, Toast.LENGTH_LONG).show()
                }
                is SetupEvent.RestoreCompleted -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                    val state = latestUiState
                    val batteryNow = isIgnoringBatteryOptimizationsNow(context)
                    val target = pages.indexOfFirst { page ->
                        when (page) {
                            SetupPage.NotificationsPermission -> !state.notificationsPermissionGranted
                            SetupPage.AlarmsPermission -> !state.alarmsPermissionGranted
                            SetupPage.BatteryOptimization -> !batteryNow
                            SetupPage.Finish -> true
                            else -> false
                        }
                    }
                    if (target >= 0) {
                        pagerState.animateScrollToPage(target)
                    } else {
                        onSetupComplete()
                    }
                }
            }
        }
    }

    // Predictive back: the whole frame shrinks a little under the gesture; committing goes one step back.
    val backProgress = remember { mutableStateOf(0f) }
    val backShape = remember { RoundedCornerShape(32.dp) }
    SetupBackHandler(
        enabled = pagerState.currentPage > 0 && !showCornerRadiusOverlay,
        onProgress = { backProgress.value = it },
        onBack = goBack
    )

    CompositionLocalProvider(LocalSetupReduceMotion provides reduceMotion) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val topInset = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
            val heroHeight = (maxHeight * 0.26f).coerceIn(150.dp, 260.dp)
            val blobRadius = minOf(heroHeight * 0.6f, maxWidth * 0.4f)
            val heroCenterY = topInset + SetupTopBarHeight + heroHeight / 2f

            SetupBackground(
                position = position,
                heroCenterY = heroCenterY,
                blobRadius = blobRadius
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = backProgress.value
                            val s = 1f - 0.07f * p
                            scaleX = s
                            scaleY = s
                            translationX = p * 20.dp.toPx()
                            shape = backShape
                            clip = p > 0.001f
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    SetupTopBar(
                        currentPage = pagerState.currentPage,
                        totalSteps = pages.lastIndex,
                        position = position,
                        onBack = goBack
                    )

                    HorizontalPager(
                        state = pagerState,
                        // Progression is driven by the buttons so a step can never be skipped by accident.
                        userScrollEnabled = false,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { pageIndex ->
                        val entrance = rememberPageEntrance()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Pages slide at half speed and fade, so the steps feel layered.
                                    val offset = (pageIndex - pagerState.currentPage) -
                                        pagerState.currentPageOffsetFraction
                                    val distance = kotlin.math.abs(offset).coerceIn(0f, 1f)
                                    alpha = 1f - distance
                                    translationX = -size.width * offset * 0.5f
                                    val s = 1f - 0.06f * distance
                                    scaleX = s
                                    scaleY = s
                                }
                        ) {
                            when (pages[pageIndex]) {
                                SetupPage.Welcome -> WelcomePage(entrance)
                                SetupPage.SpotifyLink -> SpotifyPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    isLoggedIn = isSpotifyLoggedIn,
                                    clientIdMissing = !spotifyViewModel.hasClientId()
                                )
                                SetupPage.BackupRestore -> BackupPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    uiState = uiState
                                )
                                SetupPage.ThemeSelection -> ThemePage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    selectedMode = uiState.appThemeMode,
                                    onModeSelected = { mode ->
                                        if (mode != uiState.appThemeMode) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        setupViewModel.setAppThemeMode(mode)
                                    }
                                )
                                SetupPage.LibraryLayout -> LibraryLayoutPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    isCompact = uiState.libraryNavigationMode == "compact_pill",
                                    onModeSelected = setupViewModel::setLibraryNavigationMode
                                )
                                SetupPage.NavBarLayout -> NavBarLayoutPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    isDefault = uiState.navBarStyle != "full_width",
                                    onModeSelected = setupViewModel::setNavBarStyle,
                                    onCustomizeRadius = { showCornerRadiusOverlay = true }
                                )
                                SetupPage.NotificationsPermission -> NotificationsPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    granted = uiState.notificationsPermissionGranted
                                )
                                SetupPage.AlarmsPermission -> AlarmsPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    granted = uiState.alarmsPermissionGranted
                                )
                                SetupPage.BatteryOptimization -> BatteryPage(
                                    entrance = entrance,
                                    heroHeight = heroHeight,
                                    unrestricted = batteryUnrestricted
                                )
                                SetupPage.Finish -> FinishPage(entrance, heroHeight)
                            }
                        }
                    }

                    val currentPage = pages[pagerState.currentPage]
                    val action = rememberSetupAction(
                        page = currentPage,
                        uiState = uiState,
                        isSpotifyLoggedIn = isSpotifyLoggedIn,
                        clientIdMissing = !spotifyViewModel.hasClientId(),
                        batteryUnrestricted = batteryUnrestricted,
                        notificationsAsked = notificationsAsked,
                        onNext = goNext,
                        onSignIn = { spotifyViewModel.signIn(context) },
                        onImportBackup = { backupPickerLauncher.launch("*/*") },
                        onSkipBackup = {
                            setupViewModel.clearRestorePlan()
                            selectedBackupUri = null
                            goNext()
                        },
                        onAskNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (notificationsAsked) {
                                    openNotificationSettings(context)
                                } else {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        },
                        onAskAlarms = { openExactAlarmSettings(context) },
                        onAskBattery = { requestBatteryUnrestricted(context) },
                        onFinish = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            setupViewModel.setSetupComplete()
                            onSetupComplete()
                        }
                    )
                    SetupBottomActions(
                        primaryLabel = action.label,
                        onPrimary = action.onClick,
                        primaryEnabled = action.enabled,
                        primaryContainer = action.container,
                        primaryContent = action.content,
                        primaryIcon = null,
                        secondaryLabel = action.secondaryLabel,
                        onSecondary = action.onSecondary
                    )
                }
            }
        }
    }

    val restorePlan = uiState.restorePlan
    if (restorePlan != null && selectedBackupUri != null) {
        BackupModuleSelectionDialog(
            plan = restorePlan,
            inProgress = uiState.isRestoringBackup,
            onDismiss = {
                setupViewModel.clearRestorePlan()
                selectedBackupUri = null
            },
            onBack = {
                setupViewModel.clearRestorePlan()
                selectedBackupUri = null
            },
            onSelectionChanged = setupViewModel::updateRestorePlanSelection,
            onConfirm = {
                val uri = selectedBackupUri ?: return@BackupModuleSelectionDialog
                selectedBackupUri = null
                setupViewModel.restoreFromPlan(uri)
            }
        )
    }

    // Overlay for corner radius customisation.
    AnimatedVisibility(
        visible = showCornerRadiusOverlay,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        BackHandler { showCornerRadiusOverlay = false }
        NavBarCornerRadiusContent(
            initialRadius = uiState.navBarCornerRadius.toFloat(),
            onRadiusChange = { setupViewModel.setNavBarCornerRadius(it) },
            onDone = { showCornerRadiusOverlay = false },
            onBack = { showCornerRadiusOverlay = false },
            isFullWidth = uiState.navBarStyle == "full_width"
        )
    }
}

private enum class SetupPage {
    Welcome,
    SpotifyLink,
    BackupRestore,
    ThemeSelection,
    LibraryLayout,
    NavBarLayout,
    NotificationsPermission,
    AlarmsPermission,
    BatteryOptimization,
    Finish
}

/**
 * Spotify is the only music source, so onboarding goes straight to connecting it (no media permission
 * and no folder picker: the app never reads on-device audio). Personal choices come first, then the
 * "keep it running" permissions, then the finish line.
 */
private fun buildSetupPages(sdkInt: Int): List<SetupPage> {
    val pages = mutableListOf(
        SetupPage.Welcome,
        SetupPage.SpotifyLink,
        SetupPage.BackupRestore,
        SetupPage.ThemeSelection,
        SetupPage.LibraryLayout,
        SetupPage.NavBarLayout
    )
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) pages += SetupPage.NotificationsPermission
    if (sdkInt >= Build.VERSION_CODES.S) pages += SetupPage.AlarmsPermission
    pages += SetupPage.BatteryOptimization
    pages += SetupPage.Finish
    return pages
}

private fun isIgnoringBatteryOptimizationsNow(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}

private fun openExactAlarmSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:${context.packageName}".toUri()
            }
        )
    }
}

private fun requestBatteryUnrestricted(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fall back to the general battery settings list if the direct prompt is unavailable.
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_toast_battery_settings_unavailable),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** Back button / gesture: scrubs a small shrink animation on Android 14+, plain back before that. */
@Composable
private fun SetupBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnBack by rememberUpdatedState(onBack)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        PredictiveBackHandler(enabled = enabled) { progressFlow ->
            var last = 0f
            try {
                progressFlow.collect { event ->
                    last = event.progress
                    currentOnProgress(event.progress)
                }
                currentOnBack()
            } catch (_: CancellationException) {
                // Gesture cancelled: just settle back below.
            }
            val from = last
            scope.launch {
                animate(initialValue = from, targetValue = 0f, animationSpec = tween(220)) { value, _ ->
                    currentOnProgress(value)
                }
            }
        }
    } else {
        BackHandler(enabled = enabled, onBack = onBack)
    }
}

private class SetupAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val container: Color? = null,
    val content: Color? = null,
    val secondaryLabel: String? = null,
    val onSecondary: (() -> Unit)? = null
)

/** What the pinned bottom button (and the quiet skip under it) do on the current page. */
@Composable
private fun rememberSetupAction(
    page: SetupPage,
    uiState: SetupUiState,
    isSpotifyLoggedIn: Boolean,
    clientIdMissing: Boolean,
    batteryUnrestricted: Boolean,
    notificationsAsked: Boolean,
    onNext: () -> Unit,
    onSignIn: () -> Unit,
    onImportBackup: () -> Unit,
    onSkipBackup: () -> Unit,
    onAskNotifications: () -> Unit,
    onAskAlarms: () -> Unit,
    onAskBattery: () -> Unit,
    onFinish: () -> Unit
): SetupAction {
    val continueLabel = stringResource(R.string.setup_cta_continue)
    val skipLabel = stringResource(R.string.setup_skip_for_now)
    val notNowLabel = stringResource(R.string.setup_not_now)

    return when (page) {
        SetupPage.Welcome -> SetupAction(
            label = stringResource(R.string.setup_lets_go),
            onClick = onNext
        )

        SetupPage.SpotifyLink -> if (isSpotifyLoggedIn) {
            SetupAction(label = continueLabel, onClick = onNext)
        } else {
            SetupAction(
                label = stringResource(R.string.setup_spotify_button),
                onClick = onSignIn,
                enabled = !clientIdMissing,
                container = SpotifyBrandGreen,
                content = Color.Black,
                secondaryLabel = skipLabel,
                onSecondary = onNext
            )
        }

        SetupPage.BackupRestore -> {
            val inspecting = uiState.isInspectingBackup
            val restoring = uiState.isRestoringBackup
            SetupAction(
                label = when {
                    inspecting -> stringResource(R.string.setup_inspecting_backup)
                    restoring -> stringResource(R.string.setup_restoring_backup)
                    else -> stringResource(R.string.setup_import_backup)
                },
                onClick = onImportBackup,
                enabled = !inspecting && !restoring,
                secondaryLabel = if (restoring) null else skipLabel,
                onSecondary = onSkipBackup
            )
        }

        SetupPage.ThemeSelection,
        SetupPage.LibraryLayout,
        SetupPage.NavBarLayout -> SetupAction(label = continueLabel, onClick = onNext)

        SetupPage.NotificationsPermission -> if (uiState.notificationsPermissionGranted) {
            SetupAction(label = continueLabel, onClick = onNext)
        } else {
            SetupAction(
                label = stringResource(
                    if (notificationsAsked) R.string.setup_notifications_open_settings
                    else R.string.setup_enable_notifications
                ),
                onClick = onAskNotifications,
                secondaryLabel = notNowLabel,
                onSecondary = onNext
            )
        }

        SetupPage.AlarmsPermission -> if (uiState.alarmsPermissionGranted) {
            SetupAction(label = continueLabel, onClick = onNext)
        } else {
            SetupAction(
                label = stringResource(R.string.setup_alarms_allow),
                onClick = onAskAlarms,
                secondaryLabel = notNowLabel,
                onSecondary = onNext
            )
        }

        SetupPage.BatteryOptimization -> if (batteryUnrestricted) {
            SetupAction(label = continueLabel, onClick = onNext)
        } else {
            SetupAction(
                label = stringResource(R.string.setup_disable_battery_optimization),
                onClick = onAskBattery,
                secondaryLabel = notNowLabel,
                onSecondary = onNext
            )
        }

        SetupPage.Finish -> SetupAction(
            label = stringResource(R.string.setup_cta_start_listening),
            onClick = onFinish
        )
    }
}

private val SpotifyBrandGreen = Color(0xFF1DB954)

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(entrance: State<Float>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier
                    .setupEntrance(entrance, index = 1)
                    .semantics(mergeDescendants = true) { heading() }
            ) {
                Text(
                    text = stringResource(R.string.setup_welcome_prefix).trim(),
                    style = ExpTitleTypography.displayLarge.copy(
                        fontSize = 42.sp,
                        lineHeight = 1.1.em
                    )
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = GoogleSansRounded,
                        fontSize = 46.sp,
                        color = MaterialTheme.colorScheme.primary,
                        lineHeight = 1.1.em
                    )
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            WelcomeArt(entrance = entrance)
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.setup_intro_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.setupEntrance(entrance, index = 2)
            )
        }
    }
}

@Composable
private fun SpotifyPage(
    entrance: State<Float>,
    heroHeight: Dp,
    isLoggedIn: Boolean,
    clientIdMissing: Boolean
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_spotify_title),
        body = stringResource(R.string.setup_spotify_description),
        hero = {
            SetupHeroCluster(
                centerIcon = painterResource(R.drawable.rounded_music_note_24),
                satelliteIcons = listOf(
                    painterResource(R.drawable.round_favorite_24),
                    painterResource(R.drawable.rounded_queue_music_24),
                    painterResource(R.drawable.rounded_album_24),
                    painterResource(R.drawable.rounded_library_music_24)
                ),
                entrance = entrance,
                height = heroHeight,
                centerContainer = SpotifyBrandGreen,
                centerContent = Color.Black
            )
        }
    ) {
        if (isLoggedIn) {
            SetupStatusChip(text = stringResource(R.string.setup_spotify_connected))
        } else if (clientIdMissing) {
            Text(
                text = stringResource(R.string.setup_spotify_not_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupPage(
    entrance: State<Float>,
    heroHeight: Dp,
    uiState: SetupUiState
) {
    val progress = uiState.backupTransferProgress
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_backup_have_title),
        body = stringResource(R.string.setup_backup_have_description),
        hero = {
            SetupHeroCluster(
                centerIcon = painterResource(R.drawable.rounded_upload_file_24),
                satelliteIcons = listOf(
                    painterResource(R.drawable.rounded_playlist_play_24),
                    painterResource(R.drawable.rounded_settings_24),
                    painterResource(R.drawable.rounded_lyrics_24),
                    painterResource(R.drawable.rounded_monitoring_24)
                ),
                entrance = entrance,
                height = heroHeight,
                centerShape = 1
            )
        }
    ) {
        AnimatedVisibility(visible = uiState.isInspectingBackup || progress != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.isInspectingBackup && progress == null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                text = stringResource(R.string.setup_checking_backup),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (progress != null) {
                        Text(
                            text = progress.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = progress.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePage(
    entrance: State<Float>,
    heroHeight: Dp,
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_theme_title),
        body = stringResource(R.string.setup_theme_subtitle),
        hero = {
            SetupHeroCluster(
                centerIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.DarkMode),
                satelliteIcons = listOf(
                    androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.LightMode),
                    androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.PhoneAndroid)
                ),
                entrance = entrance,
                height = heroHeight,
                centerShape = 5
            )
        }
    ) {
        val darkTitle = stringResource(R.string.setup_theme_dark_title)
        val lightTitle = stringResource(R.string.setup_theme_light_title)
        val followTitle = stringResource(R.string.setup_theme_follow_title)
        val darkBackground = Color(0xFF14151A)
        val darkCard = Color(0xFF262831)
        val darkLine = Color(0xFF3C3F4B)
        val lightBackground = Color(0xFFF6F6FA)
        val lightCard = Color(0xFFE4E5EC)
        val lightLine = Color(0xFFC9CBD6)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SetupChoiceTile(
                title = darkTitle,
                selected = selectedMode == AppThemeMode.DARK,
                onClick = { onModeSelected(AppThemeMode.DARK) },
                modifier = Modifier.weight(1f)
            ) {
                MiniThemePreview(
                    background = darkBackground,
                    card = darkCard,
                    line = darkLine,
                    modifier = Modifier.fillMaxSize()
                )
            }
            SetupChoiceTile(
                title = lightTitle,
                selected = selectedMode == AppThemeMode.LIGHT,
                onClick = { onModeSelected(AppThemeMode.LIGHT) },
                modifier = Modifier.weight(1f)
            ) {
                MiniThemePreview(
                    background = lightBackground,
                    card = lightCard,
                    line = lightLine,
                    modifier = Modifier.fillMaxSize()
                )
            }
            SetupChoiceTile(
                title = followTitle,
                selected = selectedMode == AppThemeMode.FOLLOW_SYSTEM,
                onClick = { onModeSelected(AppThemeMode.FOLLOW_SYSTEM) },
                modifier = Modifier.weight(1f)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    MiniThemePreview(
                        background = darkBackground,
                        card = darkCard,
                        line = darkLine,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                    MiniThemePreview(
                        background = lightBackground,
                        card = lightCard,
                        line = lightLine,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                }
            }
        }
        val description = when (selectedMode) {
            AppThemeMode.LIGHT -> stringResource(R.string.setup_theme_light_description)
            AppThemeMode.FOLLOW_SYSTEM -> stringResource(R.string.setup_theme_follow_description)
            else -> stringResource(R.string.setup_theme_dark_description)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HintText(stringResource(R.string.setup_theme_footer))
    }
}

@Composable
private fun LibraryLayoutPage(
    entrance: State<Float>,
    heroHeight: Dp,
    isCompact: Boolean,
    onModeSelected: (String) -> Unit
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_library_layout_title),
        body = stringResource(R.string.setup_library_layout_subtitle),
        hero = { LibraryHeaderPreview(isCompact = isCompact) }
    ) {
        SetupToggleCard(
            title = stringResource(R.string.setup_compact_mode),
            subtitle = if (isCompact) {
                stringResource(R.string.setup_compact_mode_pill_hint)
            } else {
                stringResource(R.string.setup_compact_mode_tab_hint)
            },
            checked = isCompact,
            onCheckedChange = { checked -> onModeSelected(if (checked) "compact_pill" else "tab_row") }
        )
        HintText(stringResource(R.string.setup_library_layout_footer))
    }
}

@Composable
private fun NavBarLayoutPage(
    entrance: State<Float>,
    heroHeight: Dp,
    isDefault: Boolean,
    onModeSelected: (String) -> Unit,
    onCustomizeRadius: () -> Unit
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_app_navigation_title),
        body = stringResource(R.string.setup_app_navigation_subtitle),
        hero = { NavBarPreview(isDefault = isDefault) }
    ) {
        SetupToggleCard(
            title = stringResource(R.string.setup_navbar_default_style),
            subtitle = if (isDefault) {
                stringResource(R.string.setup_nav_floating_pill_description)
            } else {
                stringResource(R.string.setup_nav_full_width_description)
            },
            checked = isDefault,
            onCheckedChange = { checked -> onModeSelected(if (checked) "default" else "full_width") }
        )
        FilledTonalButton(
            onClick = onCustomizeRadius,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.RoundedCorner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.setup_customize_corner_radius),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HintText(stringResource(R.string.setup_navbar_footer))
    }
}

@Composable
private fun NotificationsPage(
    entrance: State<Float>,
    heroHeight: Dp,
    granted: Boolean
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_permission_notifications_title),
        body = stringResource(R.string.setup_permission_notifications_description),
        hero = { NotificationHero(entrance = entrance, height = heroHeight) }
    ) {
        if (granted) SetupStatusChip(text = stringResource(R.string.setup_notifications_on))
    }
}

@Composable
private fun AlarmsPage(
    entrance: State<Float>,
    heroHeight: Dp,
    granted: Boolean
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_permission_alarms_title),
        body = stringResource(R.string.setup_permission_alarms_description),
        hero = {
            SetupHeroCluster(
                centerIcon = painterResource(R.drawable.rounded_alarm_24),
                satelliteIcons = listOf(
                    painterResource(R.drawable.rounded_timer_24),
                    painterResource(R.drawable.rounded_schedule_24),
                    painterResource(R.drawable.rounded_hourglass_empty_24),
                    painterResource(R.drawable.rounded_notifications_active_24)
                ),
                entrance = entrance,
                height = heroHeight,
                centerShape = 2
            )
        }
    ) {
        if (granted) SetupStatusChip(text = stringResource(R.string.setup_alarms_on))
    }
}

@Composable
private fun BatteryPage(
    entrance: State<Float>,
    heroHeight: Dp,
    unrestricted: Boolean
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_battery_optimization_title),
        body = stringResource(R.string.setup_battery_optimization_description),
        hero = { BatteryHero(entrance = entrance, height = heroHeight) }
    ) {
        if (unrestricted) SetupStatusChip(text = stringResource(R.string.setup_battery_on))
    }
}

@Composable
private fun FinishPage(
    entrance: State<Float>,
    heroHeight: Dp
) {
    SetupPageFrame(
        entrance = entrance,
        heroHeight = heroHeight,
        title = stringResource(R.string.setup_all_set_title),
        body = stringResource(R.string.setup_all_set_body),
        hero = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
                contentAlignment = Alignment.Center
            ) {
                ConfettiBurst(entrance = entrance, height = heroHeight)
                SetupHeroCluster(
                    centerIcon = painterResource(R.drawable.rounded_check_circle_24),
                    satelliteIcons = listOf(
                        painterResource(R.drawable.round_favorite_24),
                        painterResource(R.drawable.rounded_celebration_24)
                    ),
                    entrance = entrance,
                    height = heroHeight,
                    centerShape = 0
                )
            }
        }
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    )
}
