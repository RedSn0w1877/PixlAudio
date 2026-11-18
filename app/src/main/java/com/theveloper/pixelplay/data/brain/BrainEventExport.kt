package com.theveloper.pixelplay.data.brain

import android.content.Context
import com.theveloper.pixelplay.data.brain.store.BrainStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the event log to one file, for the evaluation harness (docs/brain-spec.md, 11): one
 * `PlayEvent.encode()` line per play, oldest first, which is also what the iOS `events-YYYY-MM.pbl`
 * segments hold. The file goes to the app's own folder on shared storage, where `adb pull` finds it:
 *
 *     adb pull /sdcard/Android/data/<package>/files/play-events.jsonl
 *
 * Nothing is uploaded and nothing is shared; the listener asks for it in Developer mode and it stays
 * where it is until they delete it.
 */
@Singleton
class BrainEventExport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BrainStore,
) {
    /** The file written, or null when the log could not be read or written. Runs off the main thread. */
    suspend fun export(): File? = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            val temp = File(dir, "$FILE_NAME.part")
            temp.bufferedWriter(Charsets.UTF_8).use { out ->
                var after = Long.MIN_VALUE
                while (true) {
                    val page = store.eventsAfter(after, PAGE)
                    if (page.isEmpty()) break
                    for (event in page) {
                        out.write(event.encode())
                        out.write("\n")
                    }
                    after = page.last().eventId
                }
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            file
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            timber.log.Timber.w(e, "brain: exporting the play events failed")
            null
        }
    }

    companion object {
        const val FILE_NAME = "play-events.jsonl"
        private const val PAGE = 1_000
    }
}
