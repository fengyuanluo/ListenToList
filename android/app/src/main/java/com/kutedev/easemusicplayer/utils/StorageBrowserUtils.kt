package com.kutedev.easemusicplayer.utils

import com.kutedev.easemusicplayer.viewmodels.entryTyp
import java.net.URLDecoder
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType

object StorageBrowserUtils {
    fun formatSize(size: Long?): String {
        if (size == null || size <= 0) {
            return ""
        }
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024
            idx++
        }
        return String.format("%.1f %s", value, units[idx])
    }

    fun buildFolderPlaylistName(
        path: String,
        prefix: String,
        rootName: String,
    ): String {
        val parts = path.split('/').filter { it.isNotBlank() }
        val folderName = parts.lastOrNull()?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: Exception) {
                it
            }
        } ?: rootName
        return "$prefix - $folderName"
    }

    suspend fun resolveSelectedMusicEntries(
        selectedPaths: Set<String>,
        currentEntries: List<StorageEntry>,
        listChildren: suspend (String) -> List<StorageEntry>?
    ): List<StorageEntry> {
        if (selectedPaths.isEmpty()) {
            return emptyList()
        }
        val entryMap = currentEntries.associateBy { it.path }
        val initialFiles = mutableListOf<StorageEntry>()
        val queue = ArrayDeque<String>()

        for (path in selectedPaths) {
            val entry = entryMap[path] ?: continue
            if (entry.isDir) {
                queue.add(entry.path)
            } else if (entry.entryTyp() == StorageEntryType.MUSIC) {
                initialFiles.add(entry)
            }
        }

        val visited = HashSet<String>()
        val allFiles = mutableListOf<StorageEntry>()
        allFiles.addAll(initialFiles)

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!visited.add(dir)) {
                continue
            }
            val children = listChildren(dir) ?: return emptyList()
            for (child in children) {
                if (child.isDir) {
                    queue.add(child.path)
                } else if (child.entryTyp() == StorageEntryType.MUSIC) {
                    allFiles.add(child)
                }
            }
        }
        return allFiles.distinctBy { it.path }
    }
}
