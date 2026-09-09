package com.theveloper.pixelplay.presentation.spotify.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theveloper.pixelplay.data.network.spotify.SpotifyAlbumFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyArtistFull
import com.theveloper.pixelplay.data.network.spotify.SpotifyTrack

private val SpotifyBrandGreen = Color(0xFF1DB954)

/**
 * Explorar el catálogo de Spotify: buscar, ver un artista con sus canciones más escuchadas
 * y sus discos, y añadir a la biblioteca lo que interese.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyBrowseScreen(
    onBackClick: () -> Unit,
    initialQuery: String = "",
    viewModel: SpotifyBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Entrada desde una categoría de la pantalla de búsqueda: se lanza la búsqueda una
    // sola vez. Sin la guarda, volver de un artista al listado la relanzaría y se perdería
    // el sitio donde estaba el usuario.
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && uiState.query.isBlank()) {
            viewModel.onQueryChange(initialQuery)
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Atrás retrocede primero dentro de la pantalla (álbum → artista → búsqueda).
    BackHandler(enabled = uiState.level != BrowseLevel.Home) {
        if (!viewModel.goBack()) onBackClick()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(uiState.level)) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.goBack()) onBackClick() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!isLoggedIn) {
                EmptyMessage("Connect Spotify first to browse the catalog.")
                return@Column
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search songs, artists, albums") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (val level = uiState.level) {
                    BrowseLevel.Home -> homeContent(uiState, viewModel)
                    BrowseLevel.Results -> resultsContent(uiState, viewModel)
                    is BrowseLevel.Artist -> artistContent(level.artist, uiState, viewModel)
                    is BrowseLevel.Album -> albumContent(level.album, uiState, viewModel)
                }
            }
        }
    }
}

private fun titleFor(level: BrowseLevel): String = when (level) {
    BrowseLevel.Home -> "Browse Spotify"
    BrowseLevel.Results -> "Search results"
    is BrowseLevel.Artist -> level.artist.name ?: "Artist"
    is BrowseLevel.Album -> level.album.name ?: "Album"
}

// ─── Secciones ─────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.homeContent(
    uiState: SpotifyBrowseUiState,
    viewModel: SpotifyBrowseViewModel
) {
    if (uiState.topReadDenied) {
        item {
            InfoCard(
                "Your most-played data isn't available yet. Disconnect and reconnect " +
                    "Spotify once — the permission for it was added after you signed in."
            )
        }
    }

    if (uiState.topArtists.isNotEmpty()) {
        item { SectionHeader("Your top artists") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(uiState.topArtists, key = { it.id.orEmpty() }) { artist ->
                    ArtistBubble(artist) { viewModel.openArtist(artist) }
                }
            }
        }
    }

    if (uiState.tracks.isNotEmpty()) {
        item {
            SectionHeaderWithAction("Your top songs", "Add all") {
                viewModel.addToLibrary(uiState.tracks, "your top songs")
            }
        }
        items(uiState.tracks, key = { "top_${it.id}" }) { track ->
            TrackRow(track) { viewModel.addToLibrary(listOf(track), track.name.orEmpty()) }
        }
    }

    if (uiState.topArtists.isEmpty() && uiState.tracks.isEmpty() && !uiState.isLoading) {
        item { EmptyMessage("Search above to find artists, albums and songs.") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultsContent(
    uiState: SpotifyBrowseUiState,
    viewModel: SpotifyBrowseViewModel
) {
    if (uiState.artists.isNotEmpty()) {
        item { SectionHeader("Artists") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(uiState.artists, key = { it.id.orEmpty() }) { artist ->
                    ArtistBubble(artist) { viewModel.openArtist(artist) }
                }
            }
        }
    }

    if (uiState.albums.isNotEmpty()) {
        item { SectionHeader("Albums") }
        items(uiState.albums, key = { "album_${it.id}" }) { album ->
            AlbumRow(album) { viewModel.openAlbum(album) }
        }
    }

    if (uiState.tracks.isNotEmpty()) {
        item { SectionHeader("Songs") }
        items(uiState.tracks, key = { "track_${it.id}" }) { track ->
            TrackRow(track) { viewModel.addToLibrary(listOf(track), track.name.orEmpty()) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistContent(
    artist: SpotifyArtistFull,
    uiState: SpotifyBrowseUiState,
    viewModel: SpotifyBrowseViewModel
) {
    item { ArtistHeader(artist) }

    if (uiState.tracks.isNotEmpty()) {
        item {
            SectionHeaderWithAction("Popular", "Add all") {
                viewModel.addToLibrary(uiState.tracks, "${artist.name}'s top songs")
            }
        }
        items(uiState.tracks, key = { "artist_track_${it.id}" }) { track ->
            TrackRow(track) { viewModel.addToLibrary(listOf(track), track.name.orEmpty()) }
        }
    }

    if (uiState.artistAlbums.isNotEmpty()) {
        item { SectionHeader("Albums") }
        items(uiState.artistAlbums, key = { "artist_album_${it.id}" }) { album ->
            AlbumRow(album) { viewModel.openAlbum(album) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumContent(
    album: SpotifyAlbumFull,
    uiState: SpotifyBrowseUiState,
    viewModel: SpotifyBrowseViewModel
) {
    item { AlbumHeader(album) }
    item {
        Button(
            onClick = { viewModel.addToLibrary(uiState.tracks, album.name.orEmpty()) },
            enabled = uiState.tracks.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SpotifyBrandGreen,
                contentColor = Color.Black
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.LibraryAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add whole album to library")
        }
    }
    items(uiState.tracks, key = { "album_track_${it.id}" }) { track ->
        TrackRow(track) { viewModel.addToLibrary(listOf(track), track.name.orEmpty()) }
    }
}

// ─── Piezas ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun SectionHeaderWithAction(title: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = action,
            style = MaterialTheme.typography.labelLarge,
            color = SpotifyBrandGreen,
            modifier = Modifier.clickable(onClick = onAction).padding(6.dp)
        )
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ArtistBubble(artist: SpotifyArtistFull, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(96.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val image = artist.images?.firstOrNull()?.url
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist.name.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtistHeader(artist: SpotifyArtistFull) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(72.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            val image = artist.images?.firstOrNull()?.url
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = artist.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            artist.genres?.take(2)?.takeIf { it.isNotEmpty() }?.let { genres ->
                Text(
                    text = genres.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: SpotifyAlbumFull) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val image = album.images?.firstOrNull()?.url
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = album.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = listOfNotNull(
                    album.releaseDate?.take(4),
                    album.totalTracks?.let { "$it tracks" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AlbumRow(album: SpotifyAlbumFull, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                val image = album.images?.firstOrNull()?.url
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        album.releaseDate?.take(4),
                        album.albumType?.replaceFirstChar { it.uppercase() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: SpotifyTrack, onAdd: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                val image = track.album?.images?.firstOrNull()?.url
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artists.orEmpty().mapNotNull { it.name }.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Add to library",
                    tint = SpotifyBrandGreen
                )
            }
        }
    }
}
