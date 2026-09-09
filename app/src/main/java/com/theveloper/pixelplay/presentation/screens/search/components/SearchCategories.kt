package com.theveloper.pixelplay.presentation.screens.search.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.utils.GenreIconProvider
import com.theveloper.pixelplay.ui.theme.GenreThemeUtils
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Una categoría del explorador.
 *
 * Es deliberadamente una lista fija y no algo derivado de la biblioteca: las etiquetas de
 * género de los ficheros locales están medio vacías, y las pistas importadas de Spotify
 * llegan directamente sin género, así que "explorar por género" acababa mostrando cuatro
 * cajones sueltos. Estas llevan al catálogo en vivo, donde sí hay algo que descubrir.
 *
 * @param query lo que se busca en el catálogo al tocarla. Se separa del nombre porque el
 *   término que mejor busca no siempre es el que mejor se lee en una tarjeta.
 */
data class SearchCategory(
    val id: String,
    val name: String,
    val query: String = name
)

val DefaultSearchCategories: List<SearchCategory> = listOf(
    SearchCategory("lofi", "Lo-fi", "lofi beats"),
    SearchCategory("pop", "Pop"),
    SearchCategory("hiphop", "Hip-Hop"),
    SearchCategory("rock", "Rock"),
    SearchCategory("rnb", "R&B"),
    SearchCategory("electronic", "Electronic"),
    SearchCategory("indie", "Indie"),
    SearchCategory("jazz", "Jazz"),
    SearchCategory("metal", "Metal"),
    SearchCategory("classical", "Classical"),
    SearchCategory("kpop", "K-Pop"),
    SearchCategory("latin", "Latin"),
    SearchCategory("soul", "Soul"),
    SearchCategory("country", "Country"),
    SearchCategory("punk", "Punk"),
    SearchCategory("ambient", "Ambient")
)

/**
 * Tarjeta de categoría.
 *
 * Reutiliza la paleta y los iconos por género que ya existen ([GenreThemeUtils],
 * [GenreIconProvider]) para que estas tarjetas y las de la biblioteca se lean como la
 * misma cosa, en vez de como dos rejillas distintas apiladas.
 */
@Composable
fun SearchCategoryCard(
    category: SearchCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalPixelPlayDarkTheme.current
    val theme = remember(category.id, isDark) {
        GenreThemeUtils.getGenreThemeColor(category.id, isDark = isDark)
    }
    val container = theme.container
    val onContainer = theme.onContainer

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "categoryPressScale"
    )

    val shape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 24.dp, smoothnessAsPercentTL = 60,
            cornerRadiusTR = 24.dp, smoothnessAsPercentTR = 60,
            cornerRadiusBR = 24.dp, smoothnessAsPercentBR = 60,
            cornerRadiusBL = 24.dp, smoothnessAsPercentBL = 60
        )
    }

    // Degradado corto sobre el propio color del género: da profundidad sin introducir un
    // segundo tono que se pelee con la paleta del tema.
    val gradient = remember(container, isDark) {
        Brush.linearGradient(
            listOf(
                lerp(container, if (isDark) Color.White else Color.Black, 0.06f),
                container,
                lerp(container, if (isDark) Color.Black else Color.White, 0.10f)
            )
        )
    }

    Box(
        modifier = modifier
            .aspectRatio(1.45f)
            .scale(pressScale)
            .clip(shape)
            .background(gradient)
            .selectable(
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // El glifo sale por la esquina a propósito: recortado se lee como textura de la
        // tarjeta y no como un icono suelto pidiendo que lo pulsen.
        SmartImage(
            model = GenreIconProvider.getGenreImageResource(category.name, emptyMap()),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.BottomEnd)
                .padding(start = 8.dp, top = 8.dp)
                .rotate(-14f)
                .alpha(0.32f),
            colorFilter = ColorFilter.tint(onContainer),
            contentScale = ContentScale.Fit
        )

        Text(
            text = category.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = onContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.78f)
                .padding(start = 16.dp, top = 14.dp, end = 4.dp)
        )
    }
}

@Composable
fun SearchSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.fillMaxWidth()
    )
}
