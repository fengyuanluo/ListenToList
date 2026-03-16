package com.kutedev.easemusicplayer.singleton

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadRepositoryTest {
    @Test
    fun mergePersistedDownloadRecords_marksFinishedTasksInactive() {
        val completedId = UUID.randomUUID()
        val cancelledId = UUID.randomUUID()
        val records = listOf(
            PersistedDownloadRecord(
                id = completedId.toString(),
                title = "song-a",
                sourcePath = "/music/song-a.mp3",
                fileName = "song-a.mp3",
                storageId = 1L,
                createdAtMs = 10L,
                status = DownloadTaskStatus.QUEUED.name,
                bytesDownloaded = 0L,
                totalBytes = 1024L,
                destinationPath = "/downloads/song-a.mp3",
            ),
            PersistedDownloadRecord(
                id = cancelledId.toString(),
                title = "song-b",
                sourcePath = "/music/song-b.mp3",
                fileName = "song-b.mp3",
                storageId = 1L,
                createdAtMs = 20L,
                status = DownloadTaskStatus.RUNNING.name,
                bytesDownloaded = 256L,
                totalBytes = 2048L,
                destinationPath = "/downloads/song-b.mp3",
            ),
        )
        val infos = listOf(
            WorkInfo(
                completedId,
                WorkInfo.State.SUCCEEDED,
                emptySet(),
                Data.Builder()
                    .putLong(DownloadWorkKeys.OUTPUT_DOWNLOADED_BYTES, 1024L)
                    .putString(DownloadWorkKeys.OUTPUT_DEST_PATH, "/downloads/song-a-finished.mp3")
                    .build(),
                Data.Builder()
                    .putLong(DownloadWorkKeys.PROGRESS_BYTES, 1024L)
                    .putLong(DownloadWorkKeys.PROGRESS_TOTAL_BYTES, 1024L)
                    .build(),
            ),
            WorkInfo(
                cancelledId,
                WorkInfo.State.CANCELLED,
                emptySet(),
                Data.Builder()
                    .putString(DownloadWorkKeys.OUTPUT_ERROR_MESSAGE, "cancelled")
                    .build(),
                Data.EMPTY,
            ),
        )

        val merged = mergePersistedDownloadRecords(records, infos)

        val completed = merged.first { it.id == completedId.toString() }
        assertEquals(DownloadTaskStatus.COMPLETED.name, completed.status)
        assertFalse(DownloadTaskStatus.valueOf(completed.status).active)
        assertEquals(1024L, completed.bytesDownloaded)
        assertEquals(1024L, completed.totalBytes)
        assertEquals("/downloads/song-a-finished.mp3", completed.destinationPath)

        val cancelled = merged.first { it.id == cancelledId.toString() }
        assertEquals(DownloadTaskStatus.CANCELLED.name, cancelled.status)
        assertFalse(DownloadTaskStatus.valueOf(cancelled.status).active)
        assertEquals("cancelled", cancelled.errorMessage)
    }
}
