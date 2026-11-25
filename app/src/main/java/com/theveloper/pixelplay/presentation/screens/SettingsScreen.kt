package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.components.CollapsingHeaderHeight
import com.theveloper.pixelplay.presentation.components.AdaptivePressSurface
import com.theveloper.pixelplay.presentation.components.AdaptiveClickableSurface
import com.theveloper.pixelplay.presentation.components.ScreenChrome
import com.theveloper.pixelplay.presentation.navigation.navigateSafely

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.presentation.components.redesign.BadgeShape
import com.theveloper.pixelplay.presentation.components.redesign.RedesignGroupedRow
import com.theveloper.pixelplay.presentation.components.redesign.RedesignScreenTitle
import com.theveloper.pixelplay.presentation.components.redesign.RedesignSectionLabel
import com.theveloper.pixelplay.presentation.components.redesign.RedesignType
import com.theveloper.pixelplay.presentation.components.redesign.ShapeBadge
import com.theveloper.pixelplay.presentation.components.redesign.groupedRowShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.rememberCollapseFraction
import com.theveloper.pixelplay.presentation.components.rememberCollapsingHeaderContentPadding
import com.theveloper.pixelplay.presentation.components.ExpressiveTopBarContent
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.model.SettingsCategory
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SettingsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.preferences.LaunchTab

// SettingsTopBar removed, replaced by CollapsibleCommonTopBar

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
        navController: NavController,
        playerViewModel: PlayerViewModel,
        onNavigationIconClick: () -> Unit,
        settingsViewModel: SettingsViewModel = hiltViewModel()
) {

    // Animation effects
    val transitionState = remember { MutableTransitionState(false) }
    LaunchedEffect(true) { transitionState.targetState = true }

    val transition = rememberTransition(transitionState, label = "SettingsAppearTransition")

    val contentAlpha by
            transition.animateFloat(
                    label = "ContentAlpha",
                    transitionSpec = { tween(durationMillis = 500) }
            ) { if (it) 1f else 0f }

    val contentOffset by
            transition.animateDp(
                    label = "ContentOffset",
                    transitionSpec = { tween(durationMillis = 400, easing = FastOutSlowInEasing) }
            ) { if (it) 0.dp else 40.dp }

    val lazyListState = rememberLazyListState()
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val developerMode by settingsViewModel.developerMode.collectAsStateWithLifecycle()

    // Redesign 2 (project/AndroidSettings.dc.html): the header scrolls with the list. 20 dp under
    // the status bar, a 48 dp row (Back as a 48 dp surfaceContainerHigh circle, 12 dp, "Settings"
    // 36/44 sp bold), then 14 dp between blocks: the Accounts card (72 dp, primaryContainer, 28 dp
    // corners, a 52 dp cookie badge, title 18/24 medium, line 13/18), "Listening" and "App" groups
    // (label 14/20 medium primary, 16 dp in, 6 dp under; rows 52 dp min, surfaceContainer, 20/4 dp
    // corners, 2 dp apart, 40 dp shape badges with 20 dp icons, 14 dp gap, title 16/22 medium).
    // Developer mode closes the App group with a switch; Developer and Device capabilities follow
    // it while it is on.
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val listening = remember { SettingsListeningCategories.filter { it in SettingsCategory.entries } }
    val app = remember {
        SettingsCategory.entries.filter {
            it !in SettingsListeningCategories &&
                it != SettingsCategory.ABOUT &&
                it != SettingsCategory.DEVELOPER &&
                it != SettingsCategory.DEVICE_CAPABILITIES
        } + SettingsCategory.ABOUT
    }
    val advanced = remember { listOf(SettingsCategory.DEVELOPER, SettingsCategory.DEVICE_CAPABILITIES) }

    Box(
            modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .graphicsLayer {
                        alpha = contentAlpha
                        translationY = contentOffset.toPx()
                    }
    ) {
        LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    top = statusBarHeight + 20.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onNavigationIconClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    RedesignScreenTitle(text = stringResource(R.string.common_settings), modifier = Modifier.weight(1f))
                }
            }
            item(key = "accounts") {
                SettingsAccountsCard(onClick = { navController.navigateSafely(Screen.Accounts.route) })
            }
            // Plus only shows once checkout is wired into the build (or in Developer mode); otherwise every
            // button on it ends in a 'checkout is not connected' dialog.
            if (BuildConfig.PLUS_CHECKOUT_URL.isNotBlank() || developerMode) {
                item(key = "plus") {
                    PlusSettingsItem(onClick = { navController.navigateSafely(Screen.Plus.route) })
                }
            }
            item(key = "listening") {
                Column {
                    RedesignSectionLabel(text = stringResource(R.string.settings_group_listening))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        listening.forEachIndexed { index, category ->
                            SettingsCategoryRow(
                                category = category,
                                colors = getCategoryColors(category, isDark),
                                badge = SettingsBadgeShapes[index % SettingsBadgeShapes.size],
                                shape = groupedRowShape(index, listening.size),
                                onClick = { openSettingsCategory(navController, category) }
                            )
                        }
                    }
                }
            }
            item(key = "app") {
                Column {
                    RedesignSectionLabel(text = stringResource(R.string.settings_group_app))
                    val total = app.size + 1 + if (developerMode) advanced.size else 0
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        app.forEachIndexed { index, category ->
                            SettingsCategoryRow(
                                category = category,
                                colors = getCategoryColors(category, isDark),
                                badge = SettingsBadgeShapes[(index + 4) % SettingsBadgeShapes.size],
                                shape = groupedRowShape(index, total),
                                onClick = { openSettingsCategory(navController, category) }
                            )
                        }
                        // The switch is always there; Developer and Device capabilities appear right
                        // under it while it's on. Turning it off hides them (and every "Advanced"
                        // subsection) without touching any stored value.
                        val view = LocalView.current
                        val appHapticsConfig = LocalAppHapticsConfig.current
                        val toggleDeveloperMode = { on: Boolean ->
                            performAppCompatHapticFeedback(view, appHapticsConfig, HapticFeedbackConstantsCompat.GESTURE_START)
                            settingsViewModel.setDeveloperMode(on)
                        }
                        val devColors = getCategoryColors(SettingsCategory.DEVELOPER, isDark)
                        RedesignGroupedRow(
                            title = stringResource(R.string.settings_developer_mode_title),
                            shape = groupedRowShape(app.size, total),
                            onClick = { toggleDeveloperMode(!developerMode) },
                            leading = {
                                ShapeBadge(
                                    icon = rememberVectorPainter(Icons.Rounded.Code),
                                    shape = BadgeShape.Clover4,
                                    containerColor = devColors.first,
                                    iconTint = devColors.second
                                )
                            },
                            trailing = { Switch(checked = developerMode, onCheckedChange = toggleDeveloperMode) }
                        )
                        if (developerMode) {
                            advanced.forEachIndexed { i, category ->
                                SettingsCategoryRow(
                                    category = category,
                                    colors = getCategoryColors(category, isDark),
                                    badge = SettingsBadgeShapes[(i + 2) % SettingsBadgeShapes.size],
                                    shape = groupedRowShape(app.size + 1 + i, total),
                                    onClick = { openSettingsCategory(navController, category) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Listening group of the main list, in this order; every other category goes under App. */
private val SettingsListeningCategories = listOf(
    SettingsCategory.PLAYBACK,
    SettingsCategory.EQUALIZER,
    SettingsCategory.LYRICS,
    SettingsCategory.SING,
    SettingsCategory.ENDLESS,
    SettingsCategory.SONG_TAP,
)

/** The badge shapes cycle in the mockup's order: cookie, sunny, flower, clover, soft burst. */
private val SettingsBadgeShapes = listOf(
    BadgeShape.Cookie9, BadgeShape.Sunny, BadgeShape.Flower, BadgeShape.Clover4, BadgeShape.SoftBurst, BadgeShape.Cookie6
)

/** One category row of the main list (see the header comment of [SettingsScreen] for sizes). */
@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    colors: Pair<Color, Color>,
    badge: BadgeShape,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    val painter = when {
        category.icon != null -> rememberVectorPainter(category.icon)
        category.iconRes != null -> painterResource(category.iconRes)
        else -> rememberVectorPainter(Icons.Rounded.ChevronRight)
    }
    RedesignGroupedRow(
        title = stringResource(category.titleRes),
        shape = shape,
        onClick = onClick,
        leading = {
            ShapeBadge(icon = painter, shape = badge, containerColor = colors.first, iconTint = colors.second)
        },
    )
}

/** The Accounts card at the top (the mockup's "Spotify connected" card). */
@Composable
private fun SettingsAccountsCard(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ShapeBadge(
            icon = rememberVectorPainter(Icons.Rounded.AccountCircle),
            shape = BadgeShape.Cookie9,
            containerColor = scheme.onPrimaryContainer,
            iconTint = scheme.primaryContainer,
            size = 52.dp,
            iconSize = 26.dp
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_category_accounts_title),
                style = RedesignType.RowTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.settings_category_accounts_subtitle),
                style = RedesignType.RowSubtitle,
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onPrimaryContainer)
    }
}

@Composable
private fun PlusSettingsItem(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AdaptivePressSurface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        modifier = Modifier.fillMaxWidth().height(92.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(58.dp).clip(CircleShape).background(colors.primary)
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("PixlAudio Plus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                Text("One-time support for future music tools", style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer.copy(alpha = .78f))
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onPrimaryContainer)
        }
    }
}

@Composable
fun ExpressiveNavigationItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: Pair<Color, Color>,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)
) {
    AdaptiveClickableSurface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(88.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp).fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.first)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.second,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2
                )
            }
        }
    }
}

/** The corner shape of row [index] in a settings group of [count] rows: round only at the group's ends. */
private fun settingsGroupShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape =
    when {
        count == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        index == count - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(4.dp)
    }

private fun openSettingsCategory(navController: NavController, category: SettingsCategory) {
    when (category) {
        // Equalizer, Device capabilities and About are screens of their own, not category pages.
        SettingsCategory.EQUALIZER -> navController.navigateSafely(Screen.Equalizer.route)
        SettingsCategory.DEVICE_CAPABILITIES -> navController.navigateSafely(Screen.DeviceCapabilities.route)
        SettingsCategory.ABOUT -> navController.navigateSafely("about")
        else -> navController.navigateSafely(Screen.SettingsCategory.createRoute(category.id))
    }
}

/** A row styled like [ExpressiveNavigationItem], with a switch in place of the arrow. */
@Composable
fun ExpressiveSwitchItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: Pair<Color, Color>,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp)
) {
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    val toggle = { newValue: Boolean ->
        performAppCompatHapticFeedback(view, appHapticsConfig, HapticFeedbackConstantsCompat.GESTURE_START)
        onCheckedChange(newValue)
    }

    AdaptiveClickableSurface(
        onClick = { toggle(!checked) },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(88.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp).fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.first)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.second,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(checked = checked, onCheckedChange = toggle)
        }
    }
}

@Composable
fun ExpressiveCategoryItem(
    category: SettingsCategory,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    customColors: Pair<Color, Color>? = null
) {
    AdaptivePressSurface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().height(88.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp).fillMaxSize()
        ) {
            // Icon Container
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(customColors?.first ?: MaterialTheme.colorScheme.primaryContainer)
            ) {
                if (category.icon != null) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        tint = customColors?.second ?: MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (category.iconRes != null) {
                    Icon(
                        painter = painterResource(id = category.iconRes),
                        contentDescription = null,
                        tint = customColors?.second ?: MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.titleRes),
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = stringResource(category.subtitleRes),
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
//            // Chevron or indicator
//             Box(
//                contentAlignment = Alignment.Center,
//                modifier = Modifier
//                    .size(36.dp)
//                    .clip(CircleShape)
//                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
//            ) {
//                 Icon(
//                    imageVector = Icons.Rounded.ChevronRight,
//                    contentDescription = null,
//                    tint = MaterialTheme.colorScheme.onSurface,
//                    modifier = Modifier.size(20.dp)
//                )
//            }
        }
    }
}

private fun getAccountsColors(isDark: Boolean): Pair<Color, Color> {
    return if (isDark) {
        Color(0xFF37474F) to Color(0xFFBBD9E8)
    } else {
        Color(0xFFD6EAF5) to Color(0xFF103548)
    }
}

@Composable
fun ExpressiveSettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent)
    ) {
        content()
    }
}

private fun getCategoryColors(category: SettingsCategory, isDark: Boolean): Pair<Color, Color> {
    return if (isDark) {
        when (category) {
            SettingsCategory.LIBRARY -> Color(0xFF004A77) to Color(0xFFC2E7FF) 
            SettingsCategory.APPEARANCE -> Color(0xFF7D5260) to Color(0xFFFFD8E4) 
            SettingsCategory.PLAYBACK -> Color(0xFF633B48) to Color(0xFFFFD8EC) 
            SettingsCategory.BEHAVIOR -> Color(0xFF3E4C63) to Color(0xFFD7E3FF)
            SettingsCategory.AI_INTEGRATION -> Color(0xFF004F58) to Color(0xFF88FAFF) 
            SettingsCategory.BACKUP_RESTORE -> Color(0xFF3B4869) to Color(0xFFD9E2FF)
            SettingsCategory.DEVELOPER -> Color(0xFF324F34) to Color(0xFFCBEFD0) 
            SettingsCategory.EQUALIZER -> Color(0xFF6E4E13) to Color(0xFFFFDEAC)
            SettingsCategory.LYRICS -> Color(0xFF4A4458) to Color(0xFFE8DEF8)
            SettingsCategory.SING -> Color(0xFF5C3B1E) to Color(0xFFFFDCC2)
            SettingsCategory.ENDLESS -> Color(0xFF1F4D45) to Color(0xFFBFF2E6)
            SettingsCategory.SONG_TAP -> Color(0xFF00504B) to Color(0xFFA8F0E8)
            SettingsCategory.DEVICE_CAPABILITIES -> Color(0xFF004D61) to Color(0xFFACEFEE) // Custom teal/cyan mix
            SettingsCategory.ABOUT -> Color(0xFF3F474D) to Color(0xFFDEE3EB) 
        }
    } else {
        when (category) {
            SettingsCategory.LIBRARY -> Color(0xFFD7E3FF) to Color(0xFF005AC1)
            SettingsCategory.APPEARANCE -> Color(0xFFFFD8E4) to Color(0xFF631835)
            SettingsCategory.PLAYBACK -> Color(0xFFFFD8EC) to Color(0xFF631B4B)
            SettingsCategory.BEHAVIOR -> Color(0xFFD7E3FF) to Color(0xFF253347)
            SettingsCategory.AI_INTEGRATION -> Color(0xFFCCE8EA) to Color(0xFF004F58)
            SettingsCategory.BACKUP_RESTORE -> Color(0xFFD9E2FF) to Color(0xFF27304E)
            SettingsCategory.DEVELOPER -> Color(0xFFCBEFD0) to Color(0xFF042106)
            SettingsCategory.EQUALIZER -> Color(0xFFFFDEAC) to Color(0xFF281900)
            SettingsCategory.LYRICS -> Color(0xFFE8DEF8) to Color(0xFF1D192B)
            SettingsCategory.SING -> Color(0xFFFFDCC2) to Color(0xFF2D1600)
            SettingsCategory.ENDLESS -> Color(0xFFBFF2E6) to Color(0xFF00201B)
            SettingsCategory.SONG_TAP -> Color(0xFFA8F0E8) to Color(0xFF00201D)
            SettingsCategory.DEVICE_CAPABILITIES -> Color(0xFFACEFEE) to Color(0xFF002022)
            SettingsCategory.ABOUT -> Color(0xFFEFF1F7) to Color(0xFF44474F)
        }
    }
}
