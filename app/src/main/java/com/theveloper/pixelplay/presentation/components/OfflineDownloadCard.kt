package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.cache.DownloadProgressCache
import com.theveloper.pixelplay.data.cache.SongCacheStateCache
import com.theveloper.pixelplay.data.database.SongCacheState
import com.theveloper.pixelplay.data.model.Song

/**
 * Estado de disponibilidad sin conexión de una canción, con su acción correspondiente.
 *
 * Existe porque hasta ahora no había NINGÚN control de descarga por canción: `requestDownload`
 * solo se llamaba desde la descarga masiva de una playlist, y `removeDownload` no se llamaba
 * desde ningún sitio. Sin esto, el indicador de "falló" de la fila no tendría forma de
 * reintentarse.
 *
 * Solo aparece en canciones de streaming: un archivo local ya suena sin conexión, así que
 * ofrecerle "Descargar" no significaría nada.
 */
@Composable
fun OfflineDownloadCard(
    song: Song,
    onDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyId = song.spotifyId ?: return

    val states by SongCacheStateCache.states.collectAsState()
    val reasons by SongCacheStateCache.failureReasons.collectAsState()
    val progressBySong by DownloadProgressCache.progress.collectAsState()
    val state = states[spotifyId]
    val failureReason = reasons[spotifyId]
    val percent = progressBySong[spotifyId]

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = when (state) {
                        SongCacheState.DOWNLOADED -> Icons.Rounded.DownloadDone
                        SongCacheState.DOWNLOADING -> Icons.Rounded.Downloading
                        SongCacheState.FAILED -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.CloudDownload
                    },
                    contentDescription = null,
                    tint = if (state == SongCacheState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.song_offline_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (state) {
                            SongCacheState.DOWNLOADED -> stringResource(R.string.song_offline_card_desc_downloaded)
                            SongCacheState.DOWNLOADING -> percent?.let {
                                stringResource(R.string.song_offline_card_desc_downloading_percent, it)
                            } ?: stringResource(R.string.song_offline_card_desc_downloading)
                            // El motivo real del fallo es más útil que un texto genérico, y es
                            // justo lo que antes se escribía y no leía nadie.
                            SongCacheState.FAILED -> failureReason
                                ?: stringResource(R.string.song_offline_failed)
                            else -> stringResource(R.string.song_offline_card_desc)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == SongCacheState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Determinada si el servidor mandó Content-Length; indeterminada si no, en vez de
            // fingir un porcentaje que no se puede calcular.
            if (state == SongCacheState.DOWNLOADING) {
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            FilledTonalButton(
                onClick = {
                    if (state == SongCacheState.DOWNLOADED) {
                        onRemoveDownload(spotifyId)
                    } else {
                        onDownload(spotifyId)
                    }
                },
                enabled = state != SongCacheState.DOWNLOADING,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        when (state) {
                            SongCacheState.DOWNLOADED -> R.string.song_offline_action_remove
                            SongCacheState.DOWNLOADING -> R.string.song_offline_action_downloading
                            SongCacheState.FAILED -> R.string.song_offline_action_retry
                            else -> R.string.song_offline_action_download
                        }
                    )
                )
            }
        }
    }
}
