package com.theveloper.pixelplay.data.tais.dsp

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri

/**
 * Opens [source] on [extractor] the way that actually works under scoped storage.
 *
 * `MediaExtractor.setDataSource(String path)` only reliably opens files the app owns outright —
 * a `content://` URI (or any non-`file` scheme) needs the `Context`-aware overload so it goes
 * through the `ContentResolver`'s permission grant instead of a raw filesystem open, which is
 * exactly what fails with "Failed to instantiate extractor." otherwise. [source] is normally
 * [com.theveloper.pixelplay.data.model.Song.contentUriString] (the same URI PixelPlayer's own
 * playback path already uses successfully) but a plain absolute file path is also accepted as a
 * fallback for callers that only have one.
 */
internal fun openDataSource(context: Context, extractor: MediaExtractor, source: String) {
    val uri = runCatching { Uri.parse(source) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (uri != null && scheme != null && scheme != "file") {
        extractor.setDataSource(context, uri, null)
    } else {
        val filePath = if (scheme == "file") uri.path ?: source else source
        extractor.setDataSource(filePath)
    }
}
