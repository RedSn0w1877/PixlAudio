package com.theveloper.pixelplay.presentation.songtap

import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theveloper.pixelplay.data.songtap.ring.RingFrameBuilder
import com.theveloper.pixelplay.data.songtap.ring.RingScanPacer
import com.theveloper.pixelplay.data.songtap.ring.RingScanSink
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import com.theveloper.pixelplay.songtap.ring.image.RingScanner
import java.util.concurrent.Executors
import timber.log.Timber

/** True when the phone has a camera at all (the manifest asks for one with `required="false"`). */
fun hasCamera(context: android.content.Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The camera, reading Pixl ring codes: a preview for aiming and an analysis stream for the scanner. It exists only while it is
 * composed (the lifecycle owner binds and unbinds it, so leaving the screen or the app stops it), analyses on one background thread
 * with "keep only the latest" frames, and looks at a frame only as often as [RingScanPacer] allows. Results go to [sink] on the
 * analysis thread. A camera that will not start calls [onCameraFailed].
 */
@Composable
fun RingCodeCamera(
    sink: RingScanSink,
    onCameraFailed: () -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "ring-scan").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 2 } }
    }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val latestSink = androidx.compose.runtime.rememberUpdatedState(sink)
    val latestFailed = androidx.compose.runtime.rememberUpdatedState(onCameraFailed)

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analyzer: RingCodeAnalyzer? = null
        val listener = Runnable {
            try {
                val p = providerFuture.get()
                provider = p
                val preview = if (showPreview) Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) } else null
                // 1280 x 720 reads a 130 pixel plate from further away than the 640 x 480 default.
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        val a = RingCodeAnalyzer(object : RingScanSink {
                            override fun onFrame(nowMs: Long) = latestSink.value.onFrame(nowMs)
                            override fun onScan(frame: com.theveloper.pixelplay.songtap.ring.image.RingFrame, result: RingScanResult, scanner: RingScanner, rotationDegrees: Int, nowMs: Long) =
                                latestSink.value.onScan(frame, result, scanner, rotationDegrees, nowMs)
                            override fun onSlow() = latestSink.value.onSlow()
                        })
                        analyzer = a
                        it.setAnalyzer(executor, a)
                    }
                p.unbindAll()
                if (preview != null) p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                else p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Timber.w(e, "ring code: the camera could not start")
                latestFailed.value()
            }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analyzer?.stop()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxSize())
}

/**
 * Turns each camera frame into a [com.theveloper.pixelplay.songtap.ring.image.RingFrame] and scans it, at the pace [RingScanPacer] sets.
 * One instance per camera session; [analyze] runs on the analysis thread only.
 */
internal class RingCodeAnalyzer(
    private val sink: RingScanSink,
    private val pacer: RingScanPacer = RingScanPacer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ImageAnalysis.Analyzer {

    private val scanner = RingScanner()
    private val builder = RingFrameBuilder()
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (stopped) return
            val now = clock()
            sink.onFrame(now)
            if (!pacer.shouldScan(now)) return
            val planes = image.planes
            if (planes.size < 3) return
            val frame = builder.build(
                width = image.width,
                height = image.height,
                y = planes[0].buffer,
                yRowStride = planes[0].rowStride,
                u = planes[1].buffer,
                v = planes[2].buffer,
                uvRowStride = planes[1].rowStride,
                uvPixelStride = planes[1].pixelStride,
            )
            val result = scanner.scan(frame, System::nanoTime)
            val done = clock()
            pacer.recordScan(scanner.diagnostics.scanMicros, done)
            sink.onScan(frame, result, scanner, image.imageInfo.rotationDegrees, done)
            if (pacer.tooSlow()) sink.onSlow()
        } catch (e: Exception) {
            Timber.v(e, "ring code: a frame could not be read")
        } finally {
            image.close()
        }
    }
}
