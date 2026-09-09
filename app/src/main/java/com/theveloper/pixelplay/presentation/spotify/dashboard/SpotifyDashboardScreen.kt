package com.theveloper.pixelplay.presentation.spotify.dashboard

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theveloper.pixelplay.data.database.SpotifyPlaylistEntity
import com.theveloper.pixelplay.data.service.PlaybackErrorInfo
import com.theveloper.pixelplay.data.youtube.PlaybackDiagnosticsReport

private val SpotifyBrandGreen = Color(0xFF1DB954)

/**
 * Estado de la cuenta de Spotify: qué se ha importado y cuánto de eso ya tiene audio
 * emparejado en YouTube Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyDashboardScreen(
    onBackClick: () -> Unit,
    onBrowseClick: () -> Unit = {},
    onOpenYouTubeLogin: () -> Unit = {},
    viewModel: SpotifyDashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Los contadores de emparejamiento son una consulta puntual, no un Flow: se refrescan
    // al entrar y cada vez que termina una importación.
    LaunchedEffect(uiState.isSyncing, uiState.totalSongs) {
        viewModel.refreshMatchCounts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AccountCard(
                    uiState = uiState,
                    onSignIn = viewModel::signIn,
                    onSync = viewModel::syncNow,
                    onMatch = viewModel::matchNow,
                    onDiagnose = viewModel::runDiagnostics,
                    onDeepProbe = viewModel::runDeepProbe,
                    onLogout = viewModel::logout
                )
            }

            item {
                YouTubeAccountCard(
                    isSignedIn = uiState.isYouTubeSignedIn,
                    onConnect = onOpenYouTubeLogin,
                    onDisconnect = viewModel::signOutYouTube
                )
            }

            if (uiState.isLoggedIn) {
                item {
                    Button(
                        onClick = onBrowseClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyBrandGreen,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Browse artists, albums & top songs")
                    }
                }
            }

            if (uiState.playlistAccessDenied) {
                item { ReconnectRequiredCard(onReconnect = viewModel::reconnect) }
            }

            uiState.lastPlaybackError?.let { error ->
                item {
                    PlaybackErrorCard(error = error, onDismiss = viewModel::clearPlaybackError)
                }
            }

            if (uiState.isDiagnosing || uiState.diagnosticsReport != null) {
                item {
                    DiagnosticsCard(
                        isRunning = uiState.isDiagnosing,
                        report = uiState.diagnosticsReport,
                        onDismiss = viewModel::dismissDiagnostics
                    )
                }
            }

            if (uiState.isDeepProbing || uiState.deepProbeReport != null) {
                item {
                    DeepProbeCard(
                        isRunning = uiState.isDeepProbing,
                        report = uiState.deepProbeReport,
                        onDismiss = viewModel::dismissDeepProbe
                    )
                }
            }

            if (uiState.playlists.isNotEmpty()) {
                item {
                    Text(
                        text = "Imported playlists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                items(uiState.playlists, key = { it.id }) { playlist ->
                    PlaylistRow(playlist)
                }
            }
        }
    }

    // El código que el usuario teclea en google.com/device, mientras se sondea en segundo plano.
    uiState.youTubeSignInPrompt?.let { prompt ->
        YouTubeSignInDialog(prompt = prompt, onDismiss = viewModel::cancelYouTubeSignIn)
    }

    uiState.youTubeSignInError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissYouTubeError,
            confirmButton = {
                Button(onClick = viewModel::dismissYouTubeError) { Text("OK") }
            },
            title = { Text("YouTube sign-in") },
            text = { Text(error) }
        )
    }
}

/**
 * Tarjeta de la cuenta de YouTube. Iniciar sesión es lo que quita el muro de "confirma que
 * no eres un robot" que YouTube empezó a poner a las peticiones sin cuenta.
 */
@Composable
private fun YouTubeAccountCard(
    isSignedIn: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = Color(0xFFFF0000),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isSignedIn) "YouTube connected" else "YouTube account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (isSignedIn) {
                    "Signed in. Playback requests now go out as your account, which gets past YouTube's \"not a bot\" checks."
                } else {
                    "Sign in with a Google account to let songs play reliably. You'll enter a short code on Google's own page — the app never sees your password."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSignedIn) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect YouTube")
                }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0000),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect YouTube")
                }
            }
        }
    }
}

/**
 * Diálogo del flujo de dispositivo: enseña el código y abre google.com/device. Mientras
 * está abierto, el ViewModel sondea en segundo plano; se cierra solo al terminar.
 */
@Composable
private fun YouTubeSignInDialog(
    prompt: YouTubeSignInPrompt,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open Google")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Connect YouTube") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. Tap \"Open Google\" below (or go to ${prompt.verificationUrl}).\n" +
                        "2. Sign in and enter this code:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = prompt.userCode,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(prompt.userCode)) }
                ) {
                    Text("Copy code")
                }
                Text(
                    text = "This window closes itself once you finish signing in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    )
}

@Composable
private fun AccountCard(
    uiState: SpotifyDashboardUiState,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onMatch: () -> Unit,
    onDiagnose: () -> Unit,
    onDeepProbe: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!uiState.isLoggedIn) {
                Text(
                    text = "Not connected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sign in to bring your liked songs and playlists into PixelPlayer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyBrandGreen,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in with Spotify")
                }
                return@Column
            }

            Text(
                text = uiState.accountName ?: "Spotify account",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            uiState.accountEmail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "${uiState.totalSongs} tracks across ${uiState.playlists.size} playlists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val totalTracked = uiState.matchedCount + uiState.pendingMatchCount + uiState.unmatchedCount
            if (totalTracked > 0) {
                Text(
                    text = "${uiState.matchedCount} ready to play · " +
                        "${uiState.pendingMatchCount} still looking · " +
                        "${uiState.unmatchedCount} no match found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { uiState.matchedCount.toFloat() / totalTracked.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (uiState.isSyncing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = uiState.syncStatus?.let { "Importing $it…" } ?: "Importing…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = !uiState.isSyncing) {
                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync")
                }
                OutlinedButton(onClick = onMatch, enabled = uiState.totalSongs > 0) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Find audio")
                }
            }

            OutlinedButton(
                onClick = onDiagnose,
                enabled = !uiState.isDiagnosing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Test playback")
            }

            OutlinedButton(
                onClick = onDeepProbe,
                enabled = !uiState.isDeepProbing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Deep probe (debug)")
            }

            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Disconnect Spotify")
            }
        }
    }
}

/**
 * Aviso de que hay que volver a enlazar la cuenta.
 *
 * Spotify da 403 al leer el contenido de las playlists cuando el token se emitió antes de
 * que la app pidiera ese permiso: la lista de playlists se lee, sus canciones no. Visto
 * desde fuera son "playlists vacías" — de ahí que merezca un aviso explícito y no una
 * línea en el log.
 */
@Composable
private fun ReconnectRequiredCard(onReconnect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Reconnect Spotify to import playlists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Spotify is refusing to hand over the songs inside your playlists " +
                    "(HTTP 403). Your login is from before the app asked for that " +
                    "permission, so the saved token never got it — which is why every " +
                    "playlist imports empty while Liked Songs works fine.\n\n" +
                    "Signing in again fixes it. This keeps your library and the audio " +
                    "already matched — it only replaces the login.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onReconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("Sign in to Spotify again")
            }
        }
    }
}

/**
 * El último fallo real del reproductor, con la causa encadenada.
 *
 * El aviso que sale al reproducir dice solo "Source error", que es la categoría de
 * ExoPlayer y no la causa. Aquí se ve la excepción concreta y la dirección que intentó
 * abrir, que es lo que distingue un rechazo del servidor de un formato irreconocible o de
 * una dirección que se quedó sin traducir.
 */
@Composable
private fun PlaybackErrorCard(
    error: PlaybackErrorInfo,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Last playback error",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = error.trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = error.errorCodeName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            error.failingUri?.let {
                Text(
                    text = "Tried to open:\n$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = error.causeChain,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onDismiss) { Text("Clear") }
        }
    }
}

/**
 * Volcado de depuración: el resultado crudo de disparar todas las variantes de petición
 * contra googlevideo. Texto monoespaciado, con scroll y un botón para copiarlo entero.
 * Es temporal, para cazar el 403.
 */
@Composable
private fun DeepProbeCard(
    isRunning: Boolean,
    report: String?,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Deep probe (debug)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Firing every request shape at YouTube… this takes a bit.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Column
            }

            val text = report ?: "No output."
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Text("Copy")
                }
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

/**
 * Muestra en qué punto se rompe la cadena Spotify → YouTube. Sin esto, cualquier fallo se
 * ve igual desde fuera: la canción no suena y no hay forma de saber por qué.
 */
@Composable
private fun DiagnosticsCard(
    isRunning: Boolean,
    report: PlaybackDiagnosticsReport?,
    onDismiss: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Playback test",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Trying one song end to end…", style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            if (report == null) {
                Text("The test could not run.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            report.steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = if (step.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                        contentDescription = if (step.ok) "Passed" else "Failed",
                        tint = if (step.ok) SpotifyBrandGreen else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = if (report.succeeded) {
                    "Everything works — this song is playable."
                } else {
                    "The first red line above is where it breaks."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: SpotifyPlaylistEntity) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (playlist.coverUrl != null) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${playlist.songCount} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
