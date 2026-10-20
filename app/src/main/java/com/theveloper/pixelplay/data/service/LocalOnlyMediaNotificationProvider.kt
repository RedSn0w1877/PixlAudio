package com.theveloper.pixelplay.data.service

import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Wraps Media3's default provider and marks playback notifications as local-only
 * so they don't get bridged to Wear OS as generic remote media controls.
 *
 * The flag is set on the builder Media3 is already filling in (see [LocalOnlyDefaultProvider]),
 * instead of rebuilding the finished notification with `Notification.Builder.recoverBuilder`,
 * which re-parsed and rebuilt the whole notification on every update. The result is identical.
 */
@UnstableApi
class LocalOnlyMediaNotificationProvider(
    context: Context,
    private val delegate: DefaultMediaNotificationProvider = LocalOnlyDefaultProvider(context),
) : MediaNotification.Provider {

    fun setSmallIcon(iconResId: Int) {
        delegate.setSmallIcon(iconResId)
    }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        return delegate.createNotification(
            mediaSession,
            customLayout,
            actionFactory,
            callback
        )
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.getNotificationChannelInfo()
}

/**
 * Media3's default provider with `setLocalOnly(true)` applied while the notification is being
 * built (the builder is handed to [addNotificationActions] before `build()`).
 */
@UnstableApi
private class LocalOnlyDefaultProvider(context: Context) : DefaultMediaNotificationProvider(context) {
    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        builder.setLocalOnly(true)
        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }
}
