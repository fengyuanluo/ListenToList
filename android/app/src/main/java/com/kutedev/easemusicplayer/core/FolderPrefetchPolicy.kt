package com.kutedev.easemusicplayer.core

import uniffi.ease_client_schema.PlayMode

internal fun shouldPrefetchFolderForPlayMode(playMode: PlayMode): Boolean {
    return when (playMode) {
        PlayMode.LIST, PlayMode.LIST_LOOP -> true
        PlayMode.SINGLE, PlayMode.SINGLE_LOOP -> false
    }
}

internal fun shouldPrefetchFolder(
    playMode: PlayMode,
    isPlaybackLoading: Boolean,
    isNetworkMetered: Boolean,
): Boolean {
    return shouldPrefetchFolderForPlayMode(playMode) && !isPlaybackLoading && !isNetworkMetered
}
