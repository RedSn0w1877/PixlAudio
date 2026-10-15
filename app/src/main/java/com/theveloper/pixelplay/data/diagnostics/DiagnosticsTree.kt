package com.theveloper.pixelplay.data.diagnostics

import android.util.Log
import timber.log.Timber

/**
 * Timber tree that feeds WARN, ERROR and WTF into the shareable [DiagnosticsLog]. Planted in debug
 * and release builds alike. (Timber.i/d/v are removed by R8 in release; use [DiagnosticsLog.event]
 * for INFO-level facts that must survive.)
 */
class DiagnosticsTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        DiagnosticsLog.record(priority, tag, message, t)
    }
}
