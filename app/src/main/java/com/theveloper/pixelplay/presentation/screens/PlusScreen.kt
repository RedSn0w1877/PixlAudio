package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.OfflineBolt
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight

/**
 * A deliberately billing-agnostic supporter screen. The action is supplied by the caller so
 * checkout can be added without coupling the presentation layer to a payment provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlusScreen(
    onBackClick: () -> Unit,
    onSupportClick: (amountCents: Int) -> Boolean = { false },
    onInsightClick: () -> Unit = {},
    onSmartPlaylistClick: () -> Unit = {}
) {
    var showCheckoutPreview by remember { mutableStateOf(false) }
    var showAmountPicker by remember { mutableStateOf(false) }
    var amountDollars by remember { mutableStateOf("15") }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "PixelPlayer Plus",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + MiniPlayerHeight + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PlusHero(
                    onSupportClick = {
                        if (!onSupportClick(1_500)) showCheckoutPreview = true
                    },
                    onChooseAmount = { showAmountPicker = true }
                )
            }
            item {
                Text(
                    text = "A little support goes a long way",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
            item { SupportStory() }
            item {
                Text(
                    text = "The Plus roadmap",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
            item {
                LocalToolsPreview(
                    onInsightClick = onInsightClick,
                    onSmartPlaylistClick = onSmartPlaylistClick
                )
            }
            items(PLUS_FEATURES.chunked(2)) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { feature ->
                        FeatureCard(feature, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Text(
                    text = "One payment. No ads. No subscription.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
    if (showCheckoutPreview) {
        AlertDialog(
            onDismissRequest = { showCheckoutPreview = false },
            title = { Text("Plus checkout preview") },
            text = { Text("The secure supporter checkout is not connected in this build yet. It will open here after the hosted checkout URL and receipt verification are deployed. Nothing was charged.") },
            confirmButton = {
                TextButton(onClick = { showCheckoutPreview = false }) { Text("Got it") }
            }
        )
    }
    if (showAmountPicker) {
        AlertDialog(
            onDismissRequest = { showAmountPicker = false },
            title = { Text("Choose your support") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$15 is the minimum. A larger one-time amount grants the same permanent Plus tier.")
                    OutlinedTextField(
                        value = amountDollars,
                        onValueChange = { amountDollars = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        prefix = { Text("$") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dollars = amountDollars.toIntOrNull()
                    if (dollars != null && dollars >= 15) {
                        showAmountPicker = false
                        if (!onSupportClick(dollars * 100)) showCheckoutPreview = true
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showAmountPicker = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LocalToolsPreview(
    onInsightClick: () -> Unit,
    onSmartPlaylistClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Local tools preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Plus will add deeper local mixes and private listening insights. Existing stats stay free.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onInsightClick, modifier = Modifier.weight(1f)) { Text("Insight Lab") }
                OutlinedButton(onClick = onSmartPlaylistClick, modifier = Modifier.weight(1f)) { Text("Smart mixes") }
            }
        }
    }
}

private data class PlusFeature(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color
)

private val PLUS_FEATURES = listOf(
    PlusFeature("Cloud Studio", "Optional hosted acceleration with upload consent and local fallback", Icons.Rounded.Cloud, Color(0xFFB9E6FF)),
    PlusFeature("Deep Discovery", "A wider, artist-diverse recommendation candidate pool", Icons.Rounded.GraphicEq, Color(0xFFF1C5FF)),
    PlusFeature("Smart Playlist Tools", "Mood, era, listening-context, and artist-radio presets", Icons.Rounded.PlaylistPlay, Color(0xFFFFD59E)),
    PlusFeature("Pro Background Studio", "More quiet-processing capacity while idle and charging", Icons.Rounded.OfflineBolt, Color(0xFFB7F0D2)),
    PlusFeature("Advanced Audio Exports", "Portable instrumental/vocal stem bundles with metadata", Icons.Rounded.Cloud, Color(0xFFB9E6FF)),
    PlusFeature("Insight Lab", "Private local listening, discovery, and completion insights", Icons.Rounded.GraphicEq, Color(0xFFF1C5FF)),
    PlusFeature("Supporter Themes", "Animated supporter accents and visual theme experiments", Icons.Rounded.Palette, Color(0xFFC9C7FF))
)

@Composable
private fun PlusHero(onSupportClick: () -> Unit, onChooseAmount: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "plus-hero")
    val drift by transition.animateFloat(
        initialValue = -0.08f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "plus-drift"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "plus-pulse"
    )
    val purple = MaterialTheme.colorScheme.primary
    val heroBrush = remember(purple) {
        Brush.linearGradient(listOf(purple, Color(0xFF7D5CFF), Color(0xFFDE6FD6)))
    }
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().graphicsLayer { shadowElevation = 8.dp.toPx() }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(heroBrush).padding(24.dp)
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Color.White.copy(alpha = 0.13f), radius = size.minDimension * .28f, center = Offset(size.width * (.88f + drift), size.height * .10f))
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.minDimension * .42f, center = Offset(size.width * (.04f - drift), size.height * .94f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.size(56.dp).scale(pulse)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.VolunteerActivism, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Text("Support the next great listen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text(
                    "PixelPlayer Plus is a one-time thank-you that helps fund faster tools, richer music intelligence, and more time spent making the player better.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = .9f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onSupportClick,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = purple)
                    ) { Text("Support for $15", fontWeight = FontWeight.Bold) }
                    Text("one time", color = Color.White.copy(alpha = .86f), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onChooseAmount,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .65f))
                ) { Text("Choose another amount") }
            }
        }
    }
}

@Composable
private fun SupportStory() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Text(
            text = "Hey! My name is Hoa, and I'm the developer. I love building great things for people, but sometimes it gets kind of hard to keep up with updates and burnout. And I thought of subscriptions, but I immediately rejected that thought. I hate subscriptions. But I did want a way to reward people who support me. The app is staying completely ad free and all of the current features will be permanently free. But for a small $15 one time payment, you support me through my journey by funding features and motivating me to make even better things for everyone. And if you're even more willing to support me even more, you could set your own price to be higher than $15, but you really don't have to. By purchasing Plus, you empower me to push myself further, and you'll get access to a suite of new and exclusive features. Thanks! :)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Composable
private fun FeatureCard(feature: PlusFeature, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(156.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(feature.tint.copy(alpha = .28f)),
                contentAlignment = Alignment.Center
            ) { Icon(feature.icon, contentDescription = null, tint = feature.tint, modifier = Modifier.size(21.dp)) }
            Text(feature.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(feature.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
    }
}
