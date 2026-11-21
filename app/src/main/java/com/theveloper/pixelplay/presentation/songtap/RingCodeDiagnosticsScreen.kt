package com.theveloper.pixelplay.presentation.songtap

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.songtap.ring.RingCodeSlots
import com.theveloper.pixelplay.data.songtap.ring.RingDiagSnapshot
import com.theveloper.pixelplay.presentation.viewmodel.SongTapViewModel
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import com.theveloper.pixelplay.songtap.ring.image.RingCodeStyle
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Developer mode > Ring code diagnostics. Point the camera at a ring code: the picture is what the scanner sees (grey, upright), with the
 * plate's circle, the art circle and the orange start dot it found drawn over it; below are the numbers of the last scan (frame rate,
 * dots matched, Reed-Solomon fixes, erasures), the confidence of every one of the 200 slots, the last 20 results with a "Copy log" button, and
 * the renderer at this phone's own colour in light and dark mode with the contrast figures, so the dots and plate can be judged by eye.
 * It does not arm Song Tap: scanning here never saves a song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingCodeDiagnosticsScreen(
    navController: NavController,
    viewModel: SongTapViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hub = viewModel.ringDiag
    val snap by hub.snapshot.collectAsStateWithLifecycle()
    val log by hub.log.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val cameraExists = remember { hasCamera(context) }

    // The hub copies pictures and publishes only while this screen is open.
    DisposableEffect(hub) {
        hub.setEnabled(true)
        onDispose { hub.setEnabled(false) }
    }
    LaunchedEffect(copied) {
        if (copied) { delay(1_500); copied = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ringdiag_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagBlock(stringResource(R.string.ringdiag_camera)) {
                Text(
                    stringResource(R.string.ringdiag_camera_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cameraExists && granted) {
                    // The camera runs without a preview of its own: the picture below is the one the scanner works from.
                    Box(Modifier.size(1.dp)) {
                        RingCodeCamera(sink = viewModel.ringSink, onCameraFailed = viewModel::ringCameraFailed, showPreview = false)
                    }
                    ScanPicture(snap)
                } else if (cameraExists) {
                    Text(stringResource(R.string.songtap_camera_needed), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.songtap_allow_camera)) }
                } else {
                    Text(stringResource(R.string.songtap_ring_no_camera), style = MaterialTheme.typography.bodyMedium)
                }
            }

            DiagBlock(stringResource(R.string.ringdiag_numbers)) {
                val yes = stringResource(R.string.ringdiag_yes)
                val no = stringResource(R.string.ringdiag_no)
                Text(
                    stringResource(R.string.ringdiag_stats, snap.framesPerSecond, snap.scansPerSecond, snap.scanMillis, snap.frameWidth, snap.frameHeight),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.ringdiag_found, if (snap.found) yes else no, if (snap.markerFound) yes else no, snap.matched, snap.candidates),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.ringdiag_rs, snap.rsCorrected, snap.erasures, snap.residualPx),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.ringdiag_outcome, snap.outcome.ifEmpty { "-" }), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (snap.tooSlow) Text(stringResource(R.string.ringdiag_too_slow), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            DiagBlock(stringResource(R.string.ringdiag_slots)) {
                Text(
                    stringResource(R.string.ringdiag_slots_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SlotMap(snap)
            }

            DiagBlock(stringResource(R.string.ringdiag_results)) {
                if (log.isEmpty()) {
                    Text(stringResource(R.string.ringdiag_results_empty), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                for (line in log.asReversed()) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Ring code diagnostics", hub.copyText("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")))
                        copied = true
                    }) { Text(stringResource(if (copied) R.string.songtap_diag_copied else R.string.songtap_diag_copy)) }
                }
            }

            ColourPreview()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The last picture the scanner worked from, with what it found drawn over it. */
@Composable
private fun ScanPicture(snap: RingDiagSnapshot) {
    val bitmap = remember(snap.serial) {
        if (snap.previewWidth == 0) {
            null
        } else {
            val pixels = IntArray(snap.previewWidth * snap.previewHeight)
            for (i in pixels.indices) {
                val g = snap.preview[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            Bitmap.createBitmap(pixels, snap.previewWidth, snap.previewHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    val aspect = if (snap.previewWidth > 0) snap.previewWidth.toFloat() / snap.previewHeight else 3f / 4f
    val green = Color(0xFF39D353)
    val cyan = Color(0xFF3FC8E4)
    val orange = Color(0xFFFF7A1A)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        if (bitmap != null) {
            drawImage(
                bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
        if (snap.found) {
            val c = Offset(snap.centreX * size.width, snap.centreY * size.height)
            val outline = snap.radius * size.width
            val stroke = Stroke(width = 2.dp.toPx())
            // The outline's outer edge, the art circle inside it and the plate's reach outside it.
            drawCircle(green, radius = outline, center = c, style = stroke)
            drawCircle(cyan, radius = outline * RingCodeLayout.ART_RADIUS / RingCodeLayout.BEZEL_OUTER, center = c, style = stroke)
            drawCircle(Color.White.copy(alpha = 0.35f), radius = outline * 1000f / RingCodeLayout.BEZEL_OUTER, center = c, style = Stroke(width = 1.dp.toPx()))
        }
        if (snap.markerFound) {
            drawCircle(orange, radius = 9.dp.toPx(), center = Offset(snap.markerX * size.width, snap.markerY * size.height), style = Stroke(width = 3.dp.toPx()))
        }
    }
}

/** All 200 slots as the plate lays them out, each dot as bright as the scanner was sure of it (big dots large, small dots small). */
@Composable
private fun SlotMap(snap: RingDiagSnapshot) {
    val seen = snap.confidence.any { it > 0 }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val half = size.minDimension / 2f
        val u = half / 1000f
        val c = Offset(size.width / 2f, size.height / 2f)
        for (slot in 0 until RingCodeLayout.SLOTS) {
            val ring = RingCodeLayout.ringOf(slot)
            val index = RingCodeLayout.indexInRing(slot)
            val a = index.toDouble() / RingCodeLayout.RING_SLOTS[ring] * 2.0 * Math.PI
            val r = RingCodeLayout.RING_RADIUS[ring] * u
            val big = snap.bits[slot].toInt() != 0
            val conf = snap.confidence[slot]
            val colour = when {
                !seen -> Color.Gray.copy(alpha = 0.4f)
                conf == 0 -> Color(0xFFE5484D)
                conf < 40 -> Color(0xFFF5A524)
                else -> Color(0xFF30A46C)
            }
            drawCircle(
                colour,
                radius = (if (big) RingCodeLayout.BIG_RADIUS else RingCodeLayout.SMALL_RADIUS) * u,
                center = Offset(c.x + (r * sin(a)).toFloat(), c.y - (r * cos(a)).toFloat()),
                alpha = if (seen) 0.35f + 0.65f * (conf / 100f).coerceIn(0f, 1f) else 1f,
            )
        }
        drawCircle(Color(0xFFD9480F), radius = RingCodeLayout.MARKER_RADIUS * u, center = Offset(c.x, c.y - RingCodeLayout.MARKER_RADIUS_AT * u))
    }
}

/** The renderer at this phone's colour, light and dark side by side, with the contrast figures, and a button that plays the beam. */
@Composable
private fun ColourPreview() {
    val light = rememberRingPalette(dark = false)
    val dark = rememberRingPalette(dark = true)
    val slots = remember { RingCodeSlots(RingCodePayloads.encodeSlots("dQw4w9WgXcQ", 12, 77)!!, 1) }
    var beamAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(beamAt) {
        if (beamAt != 0L) { delay(2_800); beamAt = 0L }
    }
    val rootHeight = remember { androidx.compose.runtime.mutableIntStateOf(1600) }
    DiagBlock(stringResource(R.string.ringdiag_preview)) {
        Text(
            stringResource(R.string.ringdiag_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((label, palette, isDark) in listOf(Triple(R.string.ringdiag_preview_light, light, false), Triple(R.string.ringdiag_preview_dark, dark, true))) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isDark) Color(0xFF141218) else Color(0xFFFFFBFE))
                            .padding(8.dp),
                    ) {
                        RingCodeView(
                            slots = slots,
                            palette = palette,
                            beamStartMs = if (!isDark) beamAt else 0L,
                            rootHeight = rootHeight,
                            pillText = if (!isDark) stringResource(R.string.songtap_ring_sent) else null,
                        ) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF8C6D3F)))
                        }
                    }
                    Text(
                        stringResource(R.string.ringdiag_contrast, hex(palette.dot), hex(palette.plate), palette.contrast, palette.dotTone.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (palette.clamped) Text(stringResource(R.string.ringdiag_darkened), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = { beamAt = System.currentTimeMillis() + 150 }) { Text(stringResource(R.string.ringdiag_play_beam)) }
    }
}

private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

@Composable
private fun DiagBlock(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
