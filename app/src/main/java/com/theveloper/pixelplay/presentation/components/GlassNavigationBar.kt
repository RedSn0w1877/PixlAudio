package com.theveloper.pixelplay.presentation.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.BottomNavItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateToTopLevelSafely
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.components.LiquidBottomTab
import com.theveloper.pixelplay.ui.glass.components.LiquidBottomTabs
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** NexHome's tab bar height (`LiquidBottomTabs`: 64 dp capsule). */
internal val GlassNavBarHeight = 64.dp

/** Gap between the glass tab bar and the gesture handle (NexHome: nav bars + 8 dp). */
internal val GlassNavBarBottomGap = 8.dp

/** NexHome's tab bar side inset. */
internal val GlassNavBarSideInset = 20.dp

/**
 * The space the glass tab bar takes at the bottom of the window: the bar, its gap above the
 * gesture handle, and the system inset. Main-root screens pad by this and the mini player floats
 * [MiniPlayerBottomSpacer] above it.
 */
internal fun resolveGlassNavBarOccupiedHeight(systemNavBarInset: Dp): Dp =
    GlassNavBarHeight + GlassNavBarBottomGap + systemNavBarInset

/**
 * Glass mode's bottom navigation: NexHome's `LiquidBottomTabs` exactly (draggable accent blob,
 * 64 dp capsule, `vibrancy + blur 8 + lens 24/24`, blob `10·p/14·p` chromatic, 3 glass nodes + one
 * hidden accent layer), placed like NexHome's (20 dp sides, 8 dp above the gesture handle).
 * Each tab is a 24 dp icon over a Caption label.
 *
 * [hideFraction] (0 shown, 1 hidden; the player expansion and the route-based hide) is read only
 * in the layer: the bar slides down as a pure translation, like the Material 3 bar.
 */
@Composable
fun GlassNavigationBar(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRouteProvider: () -> String?,
    hideFraction: () -> Float,
    onSearchIconDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnSearchIconDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
    val latestRouteProvider by rememberUpdatedState(currentRouteProvider)
    val scope = rememberCoroutineScope()
    // Search double-tap detection, same rule as the Material 3 bar (350 ms).
    val lastSearchTap = remember { longArrayOf(0L) }
    // Last tab that matched the route, so a pushed non-tab route keeps the blob where it was.
    val lastSelected = remember { intArrayOf(0) }

    val selectedIndex: () -> Int = remember(navItems) {
        {
            val route = latestRouteProvider()
            val index = navItems.indexOfFirst { it.screen.route == route }
            if (index >= 0) lastSelected[0] = index
            lastSelected[0]
        }
    }

    val onTabClick: (Int) -> Unit = remember(navItems, navController, scope) {
        click@{ index ->
            val item = navItems.getOrNull(index) ?: return@click
            val route = latestRouteProvider()
            if (route == null) {
                lastSearchTap[0] = 0L
                return@click
            }
            val itemRoute = item.screen.route
            val isAlreadySelected = route == itemRoute
            if (itemRoute == Screen.Search.route) {
                val now = SystemClock.elapsedRealtime()
                val isDoubleTap = now - lastSearchTap[0] <= 350L
                lastSearchTap[0] = now
                if (!isAlreadySelected && !navController.navigateToTopLevelSafely(itemRoute)) {
                    lastSearchTap[0] = 0L
                    return@click
                }
                if (isDoubleTap) {
                    lastSearchTap[0] = 0L
                    if (isAlreadySelected) {
                        latestOnSearchIconDoubleTap()
                    } else {
                        scope.launch {
                            delay(160L)
                            latestOnSearchIconDoubleTap()
                        }
                    }
                }
            } else {
                lastSearchTap[0] = 0L
                if (!isAlreadySelected) navController.navigateToTopLevelSafely(itemRoute)
            }
        }
    }

    // The blob's own selection (a drag released over a tab). It also fires when the route changes
    // the selection from outside; navigating to the already-current tab is a no-op.
    val onTabSelected: (Int) -> Unit = remember(navItems, navController) {
        selected@{ index ->
            val item = navItems.getOrNull(index) ?: return@selected
            val route = latestRouteProvider() ?: return@selected
            if (route != item.screen.route) navController.navigateToTopLevelSafely(item.screen.route)
        }
    }

    Box(modifier) {
        LiquidBottomTabs(
            selectedTabIndex = selectedIndex,
            onTabSelected = onTabSelected,
            tabsCount = navItems.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    // Down past the bottom edge: this layer spans bar + gap + inset, plus a little
                    // for the blob's shadow.
                    translationY = size.height * 1.15f * hideFraction().coerceIn(0f, 1f)
                }
                .padding(horizontal = GlassNavBarSideInset)
                .navigationBarsPadding()
                .padding(bottom = GlassNavBarBottomGap),
        ) {
            val route = currentRouteProvider()
            navItems.forEachIndexed { index, item ->
                val selected = route == item.screen.route
                val iconRes = if (selected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                    item.selectedIconResId
                } else {
                    item.iconResId
                }
                val label = stringResource(item.labelResId)
                LiquidBottomTab(
                    onClick = { onTabClick(index) },
                    modifier = Modifier.semantics { contentDescription = label },
                ) {
                    GlassIcon(painterResource(iconRes), size = 24.dp)
                    GlassText(label, style = GlassType.Caption, maxLines = 1)
                }
            }
        }
    }
}
