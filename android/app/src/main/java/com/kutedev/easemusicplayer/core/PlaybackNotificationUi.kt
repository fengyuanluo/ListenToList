package com.kutedev.easemusicplayer.core

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.kutedev.easemusicplayer.R
import uniffi.ease_client_schema.PlayMode

const val PLAYER_CYCLE_PLAY_MODE_COMMAND = "PLAYER_CYCLE_PLAY_MODE_COMMAND"
const val PLAYER_STOP_PLAYBACK_COMMAND = "PLAYER_STOP_PLAYBACK_COMMAND"

internal fun buildPlaybackNotificationButtons(
    playMode: PlayMode,
    playModeLabel: CharSequence,
    stopLabel: CharSequence,
): ImmutableList<CommandButton> {
    return ImmutableList.of(
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(PLAYER_CYCLE_PLAY_MODE_COMMAND, Bundle()))
            .setCustomIconResId(playModeNotificationIconRes(playMode))
            .setDisplayName(playModeLabel)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_STOP)
            .setSessionCommand(SessionCommand(PLAYER_STOP_PLAYBACK_COMMAND, Bundle()))
            .setDisplayName(stopLabel)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )
}

internal fun playModeNotificationIconRes(playMode: PlayMode): Int {
    return when (playMode) {
        PlayMode.SINGLE -> R.drawable.icon_mode_one
        PlayMode.SINGLE_LOOP -> R.drawable.icon_mode_repeatone
        PlayMode.LIST -> R.drawable.icon_mode_list
        PlayMode.LIST_LOOP -> R.drawable.icon_mode_repeat
    }
}

internal fun resolvePlaybackNotificationContentText(
    metadata: MediaMetadata,
    fallbackText: CharSequence,
): CharSequence {
    return metadata.artist
        ?.takeIf { it.isNotBlank() }
        ?: metadata.albumTitle?.takeIf { it.isNotBlank() }
        ?: fallbackText
}

internal data class PlaybackNotificationDisplayMetadata(
    val title: CharSequence,
    val text: CharSequence,
)

internal fun resolvePlaybackNotificationDisplayMetadata(
    itemMetadata: MediaMetadata?,
    playerMetadata: MediaMetadata,
    fallbackTitle: CharSequence,
    fallbackText: CharSequence,
): PlaybackNotificationDisplayMetadata {
    val resolvedTitle = itemMetadata?.title
        ?.takeIf { it.isNotBlank() }
        ?: playerMetadata.title?.takeIf { it.isNotBlank() }
        ?: fallbackTitle
    val resolvedText = itemMetadata?.artist
        ?.takeIf { it.isNotBlank() }
        ?: itemMetadata?.albumTitle?.takeIf { it.isNotBlank() }
        ?: resolvePlaybackNotificationContentText(playerMetadata, fallbackText)
    return PlaybackNotificationDisplayMetadata(
        title = resolvedTitle,
        text = resolvedText,
    )
}

internal class EasePlaybackNotificationProvider(
    private val appContext: Context,
) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(appContext).apply {
        setSmallIcon(R.drawable.icon_music_note)
    }

    override fun createNotification(
        session: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val baseNotification = delegate.createNotification(session, customLayout, actionFactory, callback)
        val fallbackText = appContext.getString(R.string.app_name)
        val displayMetadata = resolvePlaybackNotificationDisplayMetadata(
            itemMetadata = session.player.currentMediaItem?.mediaMetadata,
            playerMetadata = session.player.mediaMetadata,
            fallbackTitle = fallbackText,
            fallbackText = fallbackText,
        )
        val rebuiltNotification = Notification.Builder
            .recoverBuilder(appContext, baseNotification.notification)
            .setContentTitle(displayMetadata.title)
            .setContentText(displayMetadata.text)
            .build()
        return MediaNotification(baseNotification.notificationId, rebuiltNotification)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean {
        return delegate.handleCustomCommand(session, action, extras)
    }
}
