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
}
