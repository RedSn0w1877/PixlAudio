package com.theveloper.pixelplay.data.ai.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The model file the old "Import model file" option copied in (a MediaPipe `.task`). MediaPipe
 * LLM Inference is gone — LiteRT-LM can't read those files — so the only thing left to do with
 * it is offer to free its space ("Remove old imported model" in Settings).
 */
@Singleton
class LegacyImportedModel @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "on_device_models")

    /** Bytes the old import still takes, or null when there is none. */
    suspend fun sizeBytes(): Long? = withContext(Dispatchers.IO) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext null
        files.sumOf { it.length() }.takeIf { it > 0L }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }
}
