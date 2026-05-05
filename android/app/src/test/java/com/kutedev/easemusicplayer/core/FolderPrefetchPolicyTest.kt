package com.kutedev.easemusicplayer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_schema.PlayMode

class FolderPrefetchPolicyTest {
    @Test
    fun shouldPrefetchFolderForPlayMode_onlyAllowsListModes() {
        assertFalse(shouldPrefetchFolderForPlayMode(PlayMode.SINGLE))
        assertFalse(shouldPrefetchFolderForPlayMode(PlayMode.SINGLE_LOOP))
        assertTrue(shouldPrefetchFolderForPlayMode(PlayMode.LIST))
        assertTrue(shouldPrefetchFolderForPlayMode(PlayMode.LIST_LOOP))
    }

    @Test
    fun shouldPrefetchFolder_skipsWhenPlaybackIsLoading() {
        assertTrue(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST,
                isPlaybackLoading = false,
                isNetworkMetered = false,
            )
        )
        assertTrue(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST_LOOP,
                isPlaybackLoading = false,
                isNetworkMetered = false,
            )
        )
        assertFalse(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST,
                isPlaybackLoading = true,
                isNetworkMetered = false,
            )
        )
        assertFalse(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST_LOOP,
                isPlaybackLoading = true,
                isNetworkMetered = false,
            )
        )
        assertFalse(
            shouldPrefetchFolder(
                playMode = PlayMode.SINGLE,
                isPlaybackLoading = false,
                isNetworkMetered = false,
            )
        )
    }

    @Test
    fun shouldPrefetchFolder_skipsOnMeteredNetwork() {
        assertFalse(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST,
                isPlaybackLoading = false,
                isNetworkMetered = true,
            )
        )
        assertFalse(
            shouldPrefetchFolder(
                playMode = PlayMode.LIST_LOOP,
                isPlaybackLoading = false,
                isNetworkMetered = true,
            )
        )
    }
}
