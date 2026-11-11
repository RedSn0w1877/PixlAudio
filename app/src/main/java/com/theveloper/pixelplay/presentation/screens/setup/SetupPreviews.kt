package com.theveloper.pixelplay.presentation.screens.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@Composable
fun LibraryHeaderPreview(isCompact: Boolean, modifier: Modifier = Modifier) {
    val gradientColors = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        Color.Transparent
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .background(Brush.verticalGradient(gradientColors))
        ) {
            AnimatedContent(
                targetState = isCompact,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.95f))
                        .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.95f))
                },
                label = "HeaderPreviewAnim"
            ) { compact ->
                if (compact) {
                    // Compact Mode Preview
                    Box(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .padding(top = 24.dp, start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LibraryNavigationPillSetupShow(
                                title = stringResource(R.string.setup_preview_songs_label),
                                isExpanded = false,
                                iconRes = R.drawable.rounded_music_note_24,
                                pageIndex = 0,
                                onClick = {},
                                onArrowClick = {}
                            )
                        }
                    }
                } else {
                    // Standard Mode Preview
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 24.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_default_tab_library),
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 40.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.background(
                                        MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = CircleShape
                                    )
                                ) {
                                    Text(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                                        text = stringResource(R.string.setup_tab_songs),
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(3.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(100)
                                        )
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.background(
                                        MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = CircleShape
                                    )
                                ) {
                                    Text(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                                        text = stringResource(R.string.setup_tab_albums),
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(3.dp)
                                        .background(
                                            Color.Transparent,
                                            RoundedCornerShape(100)
                                        )
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier.background(
                                        MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = CircleShape
                                    )
                                ) {
                                    Text(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp),
                                        text = stringResource(R.string.setup_tab_artists),
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(3.dp)
                                        .background(
                                            Color.Transparent,
                                            RoundedCornerShape(100)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun LibraryNavigationPillSetupShow(
    title: String,
    isExpanded: Boolean,
    iconRes: Int,
    pageIndex: Int,
    onClick: () -> Unit,
    onArrowClick: () -> Unit
) {
    data class PillState(val pageIndex: Int, val iconRes: Int, val title: String)

    val pillRadius = 26.dp
    val innerRadius = 4.dp
    // Radio para cuando está expandido/seleccionado (totalmente redondo)
    val expandedRadius = 60.dp

    // Animación Esquina Flecha (Interna):
    // Depende de 'isExpanded':
    // - true: Se vuelve redonda (expandedRadius/pillRadius) separándose visualmente.
    // - false: Se mantiene recta (innerRadius) pareciendo unida al título.
    val animatedArrowCorner by animateFloatAsState(
        targetValue = if (isExpanded) pillRadius.value else innerRadius.value,
        label = "ArrowCornerAnimation"
    )

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "ArrowRotation"
    )

    // IntrinsicSize.Min en el Row + fillMaxHeight en los hijos asegura misma altura
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = pillRadius,
                bottomStart = pillRadius,
                topEnd = innerRadius,
                bottomEnd = innerRadius
            ),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = pillRadius,
                        bottomStart = pillRadius,
                        topEnd = innerRadius,
                        bottomEnd = innerRadius
                    )
                )
                .clickable(onClick = onClick)
        ) {
            Box(
                modifier = Modifier.padding(start = 18.dp, end = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                AnimatedContent(
                    targetState = PillState(pageIndex = pageIndex, iconRes = iconRes, title = title),
                    transitionSpec = {
                        val direction = targetState.pageIndex.compareTo(initialState.pageIndex).coerceIn(-1, 1)
                        val slideIn = slideInHorizontally { fullWidth -> if (direction >= 0) fullWidth else -fullWidth } + fadeIn()
                        val slideOut = slideOutHorizontally { fullWidth -> if (direction >= 0) -fullWidth else fullWidth } + fadeOut()
                        slideIn.togetherWith(slideOut)
                    },
                    label = "LibraryPillTitle"
                ) { targetState ->
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = targetState.iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = targetState.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // --- PARTE 2: FLECHA (Cambia de forma según estado) ---
        Surface(
            shape = RoundedCornerShape(
                topStart = animatedArrowCorner.dp, // Anima entre 4.dp y 26.dp
                bottomStart = animatedArrowCorner.dp, // Anima entre 4.dp y 26.dp
                topEnd = pillRadius,
                bottomEnd = pillRadius
            ),
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = animatedArrowCorner.dp, // Anima entre 4.dp y 26.dp
                        bottomStart = animatedArrowCorner.dp, // Anima entre 4.dp y 26.dp
                        topEnd = pillRadius,
                        bottomEnd = pillRadius
                    )
                )
                .clickable(
                    indication = ripple(),
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onArrowClick
                )
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    modifier = Modifier.rotate(arrowRotation),
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.common_expand_menu),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun NavBarPreview(isDefault: Boolean, modifier: Modifier = Modifier) {
    val gradientColors = listOf(
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f), // Lighter top
        MaterialTheme.colorScheme.surfaceContainer, // Darker bottom
    )
    
    // Simulate the bottom of a screen
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Content placeholder
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                 // Fake content lines
                 repeat(3) {
                     Box(
                         modifier = Modifier
                             .fillMaxWidth(if(it==1) 0.7f else 1f)
                             .height(12.dp)
                             .clip(RoundedCornerShape(6.dp))
                             .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                     )
                 }
            }
            
            // Navbar
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                AnimatedContent(
                    targetState = isDefault,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(400)) + slideInVertically { it })
                            .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutVertically { it })
                    },
                    label = "NavbarPreviewAnim"
                ) { default ->
                    if (default) {
                        // Default Pill Style
                         Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .height(80.dp),
                            shape = AbsoluteSmoothCornerShape(
                                cornerRadiusTL = 28.dp,
                                cornerRadiusTR = 28.dp,
                                cornerRadiusBL = 28.dp,
                                cornerRadiusBR = 28.dp,
                                smoothnessAsPercentTL = 60,
                                smoothnessAsPercentTR = 60,
                                smoothnessAsPercentBL = 60,
                                smoothnessAsPercentBR = 60
                            ),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(R.drawable.rounded_home_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(painterResource(R.drawable.rounded_search_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(painterResource(R.drawable.rounded_library_music_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        // Full Width Style
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 6.dp,
                            // Simulated rounded top corners for preview if desired, or simplified
                            shape = AbsoluteSmoothCornerShape(
                                cornerRadiusTL = 28.dp, // Default preview radius
                                smoothnessAsPercentTL = 60,
                                cornerRadiusTR = 28.dp,
                                smoothnessAsPercentTR = 60,
                                cornerRadiusBL = 0.dp,
                                smoothnessAsPercentBL = 60,
                                cornerRadiusBR = 0.dp,
                                smoothnessAsPercentBR = 60
                            )
                        ) {
                             Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(painterResource(R.drawable.rounded_home_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(painterResource(R.drawable.rounded_search_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Icon(painterResource(R.drawable.rounded_library_music_24), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
