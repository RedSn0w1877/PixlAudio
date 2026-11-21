package com.theveloper.pixelplay.presentation.songtap

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws [text] as a QR code, black on white (scanners want the contrast, whatever the theme), once per text
 * and off the main thread. Error correction L: the link is about 400 characters and a lower level keeps the
 * modules big enough to read across a table.
 */
@Composable
fun rememberQrImage(text: String?, sizePx: Int = 720): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, text, sizePx) {
        value = if (text == null) null else withContext(Dispatchers.Default) { renderQr(text, sizePx) }
    }
    return image
}

internal fun renderQr(text: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val row = y * matrix.width
        for (x in 0 until matrix.width) pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bitmap.asImageBitmap()
}
