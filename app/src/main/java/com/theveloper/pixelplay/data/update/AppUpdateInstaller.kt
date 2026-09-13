package com.theveloper.pixelplay.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileInputStream

internal object AppUpdateInstaller {
    const val ACTION_INSTALL_STATUS = "com.theveloper.pixelplay.action.APP_UPDATE_INSTALL_STATUS"

    /**
     * Streams the verified APK into a [PackageInstaller] session. On Android 12+ the session asks
     * not to require confirmation: that is honoured once this app is the installer of record, i.e.
     * from the second self-update on. The very first one (the app was sideloaded) always shows
     * Android's confirmation screen, delivered as STATUS_PENDING_USER_ACTION.
     */
    fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, AppUpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                // Mutable a propósito: el sistema rellena EXTRA_STATUS. El intent es explícito.
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }
}

/** Not exported: only Android's package installer (install status) and the system (package replaced) reach it. */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = EntryPointAccessors
            .fromApplication(context.applicationContext, AppUpdateEntryPoint::class.java)
            .appUpdateManager()
        when (intent.action) {
            AppUpdateInstaller.ACTION_INSTALL_STATUS -> handleInstallStatus(context, intent, manager)
            Intent.ACTION_MY_PACKAGE_REPLACED -> manager.onAppReplaced()
        }
    }

    private fun handleInstallStatus(context: Context, intent: Intent, manager: AppUpdateManager) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            manager.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            return
        }
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        if (confirm == null) {
            manager.onInstallStatus(PackageInstaller.STATUS_FAILURE, "Missing confirmation intent")
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The user just tapped Install in the app (or its notification, which opens the app first),
        // so we are in the foreground and allowed to start Android's confirmation screen.
        runCatching { context.startActivity(confirm) }
            .onFailure { manager.onInstallStatus(PackageInstaller.STATUS_FAILURE, it.message) }
    }
}
