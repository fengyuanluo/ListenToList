package com.kutedev.easemusicplayer.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_backend.SearchStorageEntriesResp
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchPage
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_schema.StorageId
import uniffi.ease_client_schema.StorageType
import uniffi.ease_client_backend.Storage

class StorageSearchModelsTest {
    @Test
    fun mergeSearchPagesKeepsOrderAndDeduplicatesByStoragePathAndKind() {
        val storageId = StorageId(1)
        val current = listOf(
            StorageSearchEntry(storageId, "song-a.mp3", "/music/song-a.mp3", "/music", 12uL, false),
            StorageSearchEntry(storageId, "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
        )
        val next = listOf(
            StorageSearchEntry(storageId, "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
            StorageSearchEntry(storageId, "song-c.mp3", "/music/song-c.mp3", "/music", 20uL, false),
            StorageSearchEntry(StorageId(2), "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
            StorageSearchEntry(storageId, "song-b", "/music/song-b.mp3", "/music", null, true),
        )

        val merged = mergeSearchPages(current, next)

        assertEquals(
            listOf(
                "1:/music/song-a.mp3:false",
                "1:/music/song-b.mp3:false",
                "1:/music/song-c.mp3:false",
                "2:/music/song-b.mp3:false",
                "1:/music/song-b.mp3:true",
            ),
            merged.map { "${it.storageId.value}:${it.path}:${it.isDir}" },
        )
    }

    @Test
    fun dedupeSearchEntriesRemovesDuplicatePathsInsideSinglePage() {
        val storageId = StorageId(1)
        val page = listOf(
            StorageSearchEntry(storageId, "dup-a.mp3", "/music/dup-a.mp3", "/music", 12uL, false),
            StorageSearchEntry(storageId, "dup-a.mp3", "/music/dup-a.mp3", "/music", 12uL, false),
            StorageSearchEntry(storageId, "song-b.mp3", "/music/song-b.mp3", "/music", 18uL, false),
        )

        val deduped = dedupeSearchEntries(page)

        assertEquals(listOf("/music/dup-a.mp3", "/music/song-b.mp3"), deduped.map { it.path })
    }

    @Test
    fun resolveSearchTotalShrinksFirstPageTotalAfterDeduplication() {
        val total = resolveSearchTotal(
            rawTotal = 57,
            previousCount = 0,
            mergedCount = 40,
            incomingCount = 57,
            previousTotal = null,
        )

        assertEquals(40, total)
    }

    @Test
    fun resolveSearchTotalStopsPaginationWhenAppendAddsNoUniqueEntries() {
        val total = resolveSearchTotal(
            rawTotal = 57,
            previousCount = 40,
            mergedCount = 40,
            incomingCount = 10,
            previousTotal = 40,
        )

        assertEquals(40, total)
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

    @Test
    fun isSearchRequestStillCurrentRejectsStaleRaceInputs() {
        assertTrue(
            isSearchRequestStillCurrent(
                requestToken = 2,
                currentToken = 2,
                requestQuery = " song ",
                currentQuery = "song",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.ALL,
                requestParentPath = "/Music",
                currentParentPath = "/Music",
                liveParentPath = "/Music",
            )
        )
        assertFalse(
            isSearchRequestStillCurrent(
                requestToken = 1,
                currentToken = 2,
                requestQuery = "song",
                currentQuery = "song",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.ALL,
            )
        )
        assertFalse(
            isSearchRequestStillCurrent(
                requestToken = 2,
                currentToken = 2,
                requestQuery = "song",
                currentQuery = "album",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.ALL,
            )
        )
        assertFalse(
            isSearchRequestStillCurrent(
                requestToken = 2,
                currentToken = 2,
                requestQuery = "song",
                currentQuery = "song",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.FILE,
            )
        )
        assertFalse(
            isSearchRequestStillCurrent(
                requestToken = 2,
                currentToken = 2,
                requestQuery = "song",
                currentQuery = "song",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.ALL,
                requestParentPath = "/Music",
                currentParentPath = "/Podcasts",
                liveParentPath = "/Music",
            )
        )
        assertFalse(
            isSearchRequestStillCurrent(
                requestToken = 2,
                currentToken = 2,
                requestQuery = "song",
                currentQuery = "song",
                requestScope = StorageSearchScope.ALL,
                currentScope = StorageSearchScope.ALL,
                requestParentPath = "/Music",
                currentParentPath = "/Music",
                liveParentPath = "/Podcasts",
            )
        )
    }
}
