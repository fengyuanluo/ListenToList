package com.kutedev.easemusicplayer.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_backend.SearchStorageEntriesResp
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchPage
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType
import uniffi.ease_client_backend.Storage

class StorageSearchModelsTest {
    @Test
    fun mergeSearchPagesKeepsOrderAndDeduplicatesByPath() {
        val storageId = StorageId(1)
        val current = listOf(
            StorageSearchEntry(storageId, "song-a.mp3", "/music/song-a.mp3", "/music", 12uL, false),
            StorageSearchEntry(storageId, "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
        )
        val next = listOf(
            StorageSearchEntry(storageId, "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
            StorageSearchEntry(storageId, "song-c.mp3", "/music/song-c.mp3", "/music", 20uL, false),
        )

        val merged = mergeSearchPages(current, next)

        assertEquals(listOf("/music/song-a.mp3", "/music/song-b.mp3", "/music/song-c.mp3"), merged.map { it.path })
    }

    @Test
    fun responseMappingExposesPageAndErrors() {
        val storageId = StorageId(1)
        val ok = SearchStorageEntriesResp.Ok(
            StorageSearchPage(
                entries = listOf(
                    StorageSearchEntry(storageId, "song-a.mp3", "/music/song-a.mp3", "/music", 12uL, false)
                ),
                total = 1uL,
                page = 1u,
                perPage = 10u,
            )
        )

        assertEquals(1, ok.pageOrNull()!!.entries.size)
        assertEquals(null, ok.errorTypeOrNull())
        assertEquals(StorageSearchErrorType.BlockedBySite, SearchStorageEntriesResp.BlockedBySite.errorTypeOrNull())
        assertEquals(StorageSearchErrorType.Unavailable, SearchStorageEntriesResp.Unavailable.errorTypeOrNull())
    }

    @Test
    fun onlyOpenListStorageIsSearchable() {
        val openList = Storage(
            id = StorageId(1),
            addr = "https://example.com",
            alias = "OpenList",
            username = "",
            password = "",
            isAnonymous = true,
            typ = StorageType.OPEN_LIST,
            defaultPath = "/",
            musicCount = 0uL,
        )
        val webDav = openList.copy(typ = StorageType.WEBDAV)

        assertTrue(openList.isStorageSearchSupported())
        assertFalse(webDav.isStorageSearchSupported())
    }
}
