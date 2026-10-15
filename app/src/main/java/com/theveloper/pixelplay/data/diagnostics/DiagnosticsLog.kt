package com.theveloper.pixelplay.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.theveloper.pixelplay.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The shareable diagnostics log. Release builds strip Timber's DEBUG/INFO calls and send nothing
 * anywhere, so when something breaks on the owner's phone there is nothing to look at. This keeps
 * a small redacted history in a ~1 MB rotating file that the user can share from
 * Settings > About > Diagnostics:
 *
 *  - every Timber WARN/ERROR/WTF line ([DiagnosticsTree], planted in debug and release),
 *  - a few explicit [event]s (app start, Spotify sign-in state, worker runs, HTTP failures,
 *    playback start/failure, lyrics result),
 *  - uncaught exceptions (written before the crash is handed on to the previous handler), and
 *  - the system's record of why the previous processes ended (`ApplicationExitInfo`).
 *
 * Everything goes through [DiagnosticsRedactor] and is written on a background thread, never the
 * caller's. Use [event], not `Timber.i`: R8 deletes `Timber.i` / `Log.i` calls in release builds
 * (see proguard-rules.pro), so an INFO line sent through Timber would silently vanish there.
 */
object DiagnosticsLog {

    @Volatile private var buffer: RingBufferLog? = null

    // One low-priority thread; a bounded queue so a log storm sheds lines instead of memory.
    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(2048),
            { r -> Thread(r, "diagnostics-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
            ThreadPoolExecutor.DiscardPolicy()
        )
    }

    /** Call once, first thing in `Application.onCreate` (after the crash-dialog handler is installed). */
    fun init(context: Context) {
        if (buffer != null) return
        val app = context.applicationContext
        // noBackupFilesDir: never part of a cloud backup or device transfer.
        buffer = RingBufferLog(File(app.noBackupFilesDir, "diagnostics"))
        installCrashHook()
        executor.execute { runCatching { recordNewExitReasons(app) } }
    }

    /** An explicit INFO-level event. [message] must already avoid personal data; it is redacted anyway. */
    fun event(tag: String, message: String) = enqueue(Log.INFO, tag, message, null)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        enqueue(Log.WARN, tag, message, throwable)

    /** Timber entry point (see [DiagnosticsTree]). */
    internal fun record(priority: Int, tag: String?, message: String, throwable: Throwable?) =
        enqueue(priority, tag ?: "App", message, throwable)

    private fun enqueue(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val sink = buffer ?: return
        val now = System.currentTimeMillis()
        runCatching {
            executor.execute { runCatching { sink.append(formatLine(now, priority, tag, message, throwable)) } }
        }
    }

    internal fun formatLine(nowMs: Long, priority: Int, tag: String, message: String, throwable: Throwable?): String {
        val level = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        }
        val body = buildString {
            append(message)
            if (throwable != null) {
                if (isNotEmpty()) append('\n')
                append(stackTraceOf(throwable))
            }
        }
        return "${DiagnosticsTime.iso(nowMs)} $level/${DiagnosticsRedactor.redact(tag)}: ${DiagnosticsRedactor.redact(body)}"
    }

    private fun stackTraceOf(throwable: Throwable, maxLines: Int = 40): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = sw.toString().trimEnd()
        val lines = text.lines()
        return if (lines.size <= maxLines) text
        else (lines.take(maxLines) + "\t... ${lines.size - maxLines} more lines").joinToString("\n")
    }

    // -- crashes -----------------------------------------------------------------

    private fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeFatal(thread, throwable) }
            // Hand on unchanged: the crash dialog's handler (CrashHandler) and the system's.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeFatal(thread: Thread, throwable: Throwable) {
        val sink = buffer ?: return
        // Let already-queued lines land first so the order stays right; give up quickly.
        runCatching { executor.submit(Runnable { }).get(400, TimeUnit.MILLISECONDS) }
        sink.append(
            formatLine(
                System.currentTimeMillis(), Log.ASSERT, "Fatal",
                "UNCAUGHT EXCEPTION in thread \"${thread.name}\"", throwable
            )
        )
    }

    // -- previous process exits ------------------------------------------------------

    private data class ExitRecord(val timestamp: Long, val text: String)

    private fun readExitReasons(context: Context): List<ExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return runCatching {
            am.getHistoricalProcessExitReasons(null, 0, 8).map {
                ExitRecord(
                    timestamp = it.timestamp,
                    text = DiagnosticsReport.describeExit(
                        it.timestamp, it.reason, it.status, it.importance, it.description, it.rss
                    )
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Writes exits newer than the last one already logged (so a restart does not repeat old ones). */
    private fun recordNewExitReasons(context: Context) {
        val sink = buffer ?: return
        val prefs = context.getSharedPreferences("diagnostics_state", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("last_exit_ts", 0L)
        val fresh = readExitReasons(context).filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        fresh.forEach {
            sink.append("${DiagnosticsTime.iso(System.currentTimeMillis())} I/Exit: previous process ended: ${it.text}")
        }
        fresh.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit_ts", it).apply() }
    }

    // -- report for sharing ---------------------------------------------------------------

    fun info(context: Context): DiagnosticsInfo = DiagnosticsInfo(
        appId = context.packageName,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
        commit = BuildConfig.GIT_SHA,
        buildDate = BuildConfig.BUILD_DATE,
        device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        locale = Locale.getDefault().toLanguageTag(),
        spotifySignInConfigured = BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty(),
        cloudKeysBundled = BuildConfig.CLOUD_DEFAULTS_KEY.isNotEmpty(),
        generatedAtMs = System.currentTimeMillis()
    )

    /** The full text of the report. Does disk I/O: call off the main thread. */
    fun buildReport(context: Context): String {
        runCatching { executor.submit(Runnable { }).get(500, TimeUnit.MILLISECONDS) }
        val exits = readExitReasons(context).sortedByDescending { it.timestamp }.map { it.text }
        return DiagnosticsReport.render(info(context), exits, buffer?.readAll().orEmpty())
    }

    /** Writes the report to a cache file for the share sheet; null if it could not be written. */
    fun writeShareFile(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, "diagnostics-share").apply { mkdirs() }
        File(dir, "pixlaudio-diagnostics.txt").also { it.writeText(buildReport(context), Charsets.UTF_8) }
    }.getOrNull()

    /** What the Diagnostics screen shows. Does disk I/O: call off the main thread. */
    data class Snapshot(
        val info: DiagnosticsInfo,
        val exitReasons: List<String>,
        val recentLog: String,
        val sizeBytes: Long
    )

    fun snapshot(context: Context, recentLines: Int = 40): Snapshot {
        runCatching { executor.submit(Runnable { }).get(500, TimeUnit.MILLISECONDS) }
        val exits = readExitReasons(context).sortedByDescending { it.timestamp }.map { it.text }
        val tail = buffer?.readAll().orEmpty().trimEnd('\n').lines().takeLast(recentLines).joinToString("\n")
        return Snapshot(info(context), exits, tail, buffer?.sizeOnDisk() ?: 0L)
    }

    fun logSizeBytes(): Long = buffer?.sizeOnDisk() ?: 0L

    fun clear(context: Context) {
        runCatching { executor.submit(Runnable { }).get(500, TimeUnit.MILLISECONDS) }
        buffer?.clear()
        runCatching { File(context.cacheDir, "diagnostics-share").deleteRecursively() }
        event("Diagnostics", "log cleared by user")
    }
}
