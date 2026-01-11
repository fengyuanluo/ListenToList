package com.kutedev.easemusicplayer.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_schema.StorageId

class StorageBrowserUtilsTest {
    @Test
    fun formatSize_shouldFormatReadableUnits() {
        assertEquals("", StorageBrowserUtils.formatSize(null))
        assertEquals("", StorageBrowserUtils.formatSize(0))
        assertEquals("1.0 KB", StorageBrowserUtils.formatSize(1024))
        assertEquals("1.0 MB", StorageBrowserUtils.formatSize(1024 * 1024))
    }

    @Test
    fun buildFolderPlaylistName_shouldUseLastSegment() {
        assertEquals(
            "Folder Play - Root",
            StorageBrowserUtils.buildFolderPlaylistName("/", "Folder Play", "Root")
        )
        assertEquals(
            "Folder Play - 更新日志",
            StorageBrowserUtils.buildFolderPlaylistName(
                "/%E6%9B%B4%E6%96%B0%E6%97%A5%E5%BF%97",
                "Folder Play",
                "Root"
            )
        )
    }

    @Test
    fun resolveSelectedMusicEntries_shouldCollectRecursiveSongs() = runBlocking {
        val storageId = StorageId(1)
        val rootEntries = listOf(
            StorageEntry(
                storageId = storageId,
                name = "folder",
                path = "/folder",
                size = null,
                isDir = true
            ),
            StorageEntry(
                storageId = storageId,
                name = "song1.mp3",
                path = "/song1.mp3",
                size = 100uL,
                isDir = false
            ),
            StorageEntry(
                storageId = storageId,
                name = "note.txt",
                path = "/note.txt",
                size = 10uL,
                isDir = false
            )
        )
        val folderEntries = listOf(
            StorageEntry(
                storageId = storageId,
                name = "song2.flac",
                path = "/folder/song2.flac",
                size = 200uL,
                isDir = false
            ),
            StorageEntry(
                storageId = storageId,
                name = "sub",
                path = "/folder/sub",
                size = null,
                isDir = true
            )
        )
        val subEntries = listOf(
            StorageEntry(
                storageId = storageId,
                name = "song3.ogg",
                path = "/folder/sub/song3.ogg",
                size = 300uL,
                isDir = false
            )
        )

        val result = StorageBrowserUtils.resolveSelectedMusicEntries(
            selectedPaths = setOf("/folder", "/song1.mp3"),
            currentEntries = rootEntries,
            listChildren = { dir ->
                when (dir) {
                    "/folder" -> folderEntries
                    "/folder/sub" -> subEntries
                    else -> emptyList()
                }
            }
        )

        assertEquals(3, result.size)
        assertTrue(result.any { it.path == "/song1.mp3" })
        assertTrue(result.any { it.path == "/folder/song2.flac" })
        assertTrue(result.any { it.path == "/folder/sub/song3.ogg" })
    }
}
