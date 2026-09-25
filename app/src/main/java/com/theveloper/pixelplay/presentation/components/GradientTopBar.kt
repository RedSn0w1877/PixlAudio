package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.PixelPlayStatusBarStyle
import com.theveloper.pixelplay.ui.glass.GlassButton
import com.theveloper.pixelplay.ui.glass.GlassGroup
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreGradientTopBar(
    title: String,
    startColor: Color,
    endColor: Color,
    contentColor: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationIconClick: () -> Unit,
) {
    val gradientBrush = remember(startColor, endColor) {
        Brush.verticalGradient(colors = listOf(startColor, endColor))
    }

    PixelPlayStatusBarStyle(color = startColor)

    LargeTopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                modifier = Modifier.padding(start = 6.dp),
                text = title,
                color = contentColor,
                fontFamily = GoogleSansRounded
            )
        },
        expandedHeight = 160.dp,
        modifier = Modifier.background(brush = gradientBrush),
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(start = 10.dp),
                onClick = onNavigationIconClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = contentColor
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = startColor
                )
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent, // Background is handled by the gradient brush
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer, // Or a color that contrasts well with your typical gradient
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer // Same as title
        )
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeGradientTopBar(
    onNavigationIconClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onBetaClick: () -> Unit,
    onMenuClick: () -> Unit = {},
    isScrolled: Boolean = false,
    activeJobCount: Int = 0,
    onJobsClick: () -> Unit = {},
) {
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHighest

    PixelPlayStatusBarStyle(color = surfaceContainerHigh)

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "topbar_alpha_transition"
    )

    val glassEnabled = com.theveloper.pixelplay.ui.glass.isGlassEnabled
    // The scrim used to be a flat rect painted *inside* the status bar padding, so it stopped
    // short of the top of the screen and ended in a hard horizontal edge — a grey slab floating
    // in the middle of the page with content still visible above and below it. Drawing behind the
    // inset instead makes it reach the very top, and fading the bottom third out means content
    // slides under it with no seam.
    val scrimBrush = remember(surfaceContainerHigh) {
        Brush.verticalGradient(
            0.0f to surfaceContainerHigh,
            0.55f to surfaceContainerHigh,
            0.80f to surfaceContainerHigh.copy(alpha = 0.72f),
            1.0f to Color.Transparent
        )
    }

    TopAppBar(
        modifier = Modifier
            .drawBehind {
                // Glass mode: the screen's ScrollEdgeEffect (GlassScaffold) does this job.
                if (!glassEnabled && animatedAlpha > 0.01f) {
                    drawRect(brush = scrimBrush, alpha = animatedAlpha)
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars),
        title = { /* nada, usamos solo acciones */ },
        navigationIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                // Glass mode puts Beta in the single trailing action group instead.
                if (!glassEnabled) {
                    FilledTonalButton(
                        modifier = Modifier.padding(start = 4.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onBetaClick
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.topbar_beta_letter),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = stringResource(R.string.topbar_beta_label),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                }
                }
            }
        },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 14.dp)
            ) {
                if (glassEnabled) {
                    HomeGlassActionGroup(
                        activeJobCount = activeJobCount,
                        onBetaClick = onBetaClick,
                        onJobsClick = onJobsClick,
                        onMoreOptionsClick = onMoreOptionsClick,
                        onSettingsClick = onNavigationIconClick
                    )
                } else {
                    if (activeJobCount > 0) {
                        FilledIconButton(
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = onJobsClick
                        ) {
                            BadgedBox(badge = { Badge { Text(activeJobCount.toString()) } }) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_hourglass_24),
                                    contentDescription = stringResource(R.string.topbar_cd_active_jobs)
                                )
                            }
                        }
                    }
                    FilledIconButton(
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onMoreOptionsClick
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.round_newspaper_24),
                            contentDescription = stringResource(R.string.topbar_cd_changelog)
                        )
                    }
                    FilledIconButton(
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onNavigationIconClick
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_settings_24),
                            contentDescription = stringResource(R.string.common_settings)
                        )
                    }
                }
            }
        },
        colors = topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

/**
 * Home's top-bar actions in Liquid Glass mode: Beta, active jobs, changelog and settings on ONE
 * piece of glass ([GlassGroup]) refracting the feed, instead of four separate glass buttons.
 * Beta is a fill pill; the icons are plain glyphs on the group.
 */
@Composable
private fun HomeGlassActionGroup(
    activeJobCount: Int,
    onBetaClick: () -> Unit,
    onJobsClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val glyphColors = IconButtonDefaults.iconButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface
    )
    GlassGroup(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        GlassButton(
            onClick = onBetaClick,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.topbar_beta_letter),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.topbar_beta_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (activeJobCount > 0) {
            IconButton(onClick = onJobsClick, colors = glyphColors) {
                BadgedBox(badge = { Badge { Text(activeJobCount.toString()) } }) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_hourglass_24),
                        contentDescription = stringResource(R.string.topbar_cd_active_jobs)
                    )
                }
            }
        }
        IconButton(onClick = onMoreOptionsClick, colors = glyphColors) {
            Icon(
                painter = painterResource(R.drawable.round_newspaper_24),
                contentDescription = stringResource(R.string.topbar_cd_changelog)
            )
        }
        IconButton(onClick = onSettingsClick, colors = glyphColors) {
            Icon(
                painter = painterResource(R.drawable.rounded_settings_24),
                contentDescription = stringResource(R.string.common_settings)
            )
        }
    }
}
