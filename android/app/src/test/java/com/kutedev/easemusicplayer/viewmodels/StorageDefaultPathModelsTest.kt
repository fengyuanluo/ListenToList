package com.kutedev.easemusicplayer.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_backend.ArgUpsertStorage
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType

class StorageDefaultPathModelsTest {
    @Test
    fun normalizeStorageDefaultPath_handles_blank_and_slashes() {
        assertEquals("/", normalizeStorageDefaultPath(""))
        assertEquals("/", normalizeStorageDefaultPath(" / "))
        assertEquals("/Music", normalizeStorageDefaultPath("Music"))
        assertEquals("/Music/Sub", normalizeStorageDefaultPath("/Music/Sub/"))
    }

    @Test
    fun onlyOpenListSupportsDefaultPathForNow() {
        assertTrue(StorageType.OPEN_LIST.supportsStorageDefaultPath())
        assertFalse(StorageType.WEBDAV.supportsStorageDefaultPath())
        assertFalse(StorageType.ONE_DRIVE.supportsStorageDefaultPath())
        assertFalse(StorageType.LOCAL.supportsStorageDefaultPath())
    }

    @Test
    fun browserEntryDefaultPathOnlyExistsForSupportedStorage() {
        val openList = sampleStorage(StorageType.OPEN_LIST, defaultPath = "/Media")
        val webDav = sampleStorage(StorageType.WEBDAV, defaultPath = "/Media")

        assertEquals("/Media", openList.browserEntryDefaultPathOrNull())
        assertNull(webDav.browserEntryDefaultPathOrNull())
    }

    @Test
    fun argSupportTracksStorageTypeMatrix() {
        val openList = sampleArg(StorageType.OPEN_LIST)
        val webDav = sampleArg(StorageType.WEBDAV)

        assertTrue(openList.supportsStorageDefaultPath())
        assertFalse(webDav.supportsStorageDefaultPath())
    }

    private fun sampleStorage(typ: StorageType, defaultPath: String): Storage {
        return Storage(
            id = StorageId(1),
            addr = "https://example.com",
            alias = "demo",
            username = "",
            password = "",
            isAnonymous = true,
            typ = typ,
            defaultPath = defaultPath,
            musicCount = 0uL,
        )
    }

    private fun sampleArg(typ: StorageType): ArgUpsertStorage {
        return ArgUpsertStorage(
            id = null,
            addr = "https://example.com",
            alias = "demo",
            username = "",
            password = "",
            isAnonymous = true,
            typ = typ,
            defaultPath = "/Media",
        )
    }
}
