package com.kutedev.easemusicplayer.singleton

import androidx.work.Data
import androidx.work.WorkInfo
import com.kutedev.easemusicplayer.core.ResolvedMusicPlaybackSource
import com.kutedev.easemusicplayer.core.shouldRejectResumeState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun mergePersistedDownloadRecords_keepsPausedStatusWhenCancellationIsRepositoryInitiated() {
        val businessId = UUID.randomUUID()
        val workId = UUID.randomUUID()
        val records = listOf(
            PersistedDownloadRecord(
                id = businessId.toString(),
                workId = workId.toString(),
                title = "song-paused",
                sourcePath = "/music/song-paused.mp3",
                fileName = "song-paused.mp3",
                storageId = 1L,
                createdAtMs = 30L,
                status = DownloadTaskStatus.PAUSED.name,
                bytesDownloaded = 512L,
                totalBytes = 4096L,
                destinationPath = "/downloads/song-paused.mp3",
            ),
        )
        val infos = listOf(
            WorkInfo(
                workId,
                WorkInfo.State.CANCELLED,
                emptySet(),
                Data.Builder()
                    .putString(DownloadWorkKeys.OUTPUT_ERROR_MESSAGE, "cancelled")
                    .build(),
                Data.EMPTY,
            ),
        )

        val merged = mergePersistedDownloadRecords(records, infos)
        val paused = merged.single()

        assertEquals(DownloadTaskStatus.PAUSED.name, paused.status)
        assertEquals(null, paused.workId)
        assertEquals(null, paused.errorMessage)
    }

    @Test
    fun shouldRejectResumeState_rejectsStaleTempAndUnsupportedRange() {
        assertFalse(shouldRejectResumeState(existingBytes = 0L, sizeHint = 100L, remainingBytesAfterOffset = 0L))
        assertFalse(shouldRejectResumeState(existingBytes = 100L, sizeHint = 100L, remainingBytesAfterOffset = null))
        assertEquals(true, shouldRejectResumeState(existingBytes = 101L, sizeHint = 100L, remainingBytesAfterOffset = null))
        assertEquals(true, shouldRejectResumeState(existingBytes = 20L, sizeHint = 100L, remainingBytesAfterOffset = 0L))
        assertFalse(shouldRejectResumeState(existingBytes = 20L, sizeHint = 100L, remainingBytesAfterOffset = 80L))
    }

    @Test
    fun resolveCompletedPlaybackSourceFromRecord_returnsNullAndFailsRecordWhenOfflineFileMissing() {
        val record = PersistedDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = "missing-song",
            sourcePath = "/music/missing-song.mp3",
            fileName = "missing-song.mp3",
            storageId = 1L,
            createdAtMs = 40L,
            status = DownloadTaskStatus.COMPLETED.name,
            bytesDownloaded = 1024L,
            totalBytes = 1024L,
            destinationPath = "/downloads/missing-song.mp3",
        )

        val resolution = resolveCompletedPlaybackSourceFromRecord(
            record = record,
            canReadContentUri = { false },
            canReadFile = { false },
        )

        assertNull(resolution.source)
        assertEquals(DownloadTaskStatus.FAILED.name, resolution.recordUpdate?.status)
        assertEquals("离线文件不可用或已被删除", resolution.recordUpdate?.errorMessage)
    }

    @Test
    fun resolveCompletedPlaybackSourceFromRecord_keepsReadableDownloadedFileAsPlaybackSource() {
        val record = PersistedDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = "downloaded-song",
            sourcePath = "/music/downloaded-song.mp3",
            fileName = "downloaded-song.mp3",
            storageId = 1L,
            createdAtMs = 50L,
            status = DownloadTaskStatus.COMPLETED.name,
            bytesDownloaded = 2048L,
            totalBytes = 2048L,
            destinationPath = "/downloads/downloaded-song.mp3",
        )

        val resolution = resolveCompletedPlaybackSourceFromRecord(
            record = record,
            canReadContentUri = { false },
            canReadFile = { true },
        )

        assertEquals(
            ResolvedMusicPlaybackSource.DownloadedFile("/downloads/downloaded-song.mp3"),
            resolution.source,
        )
        assertNull(resolution.recordUpdate)
    }

    @Test
    fun resolveCompletedPlaybackSourceFromRecord_returnsNullAndFailsRecordWhenDownloadedFileShorterThanExpected() {
        val record = PersistedDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = "short-song",
            sourcePath = "/music/short-song.mp3",
            fileName = "short-song.mp3",
            storageId = 1L,
            createdAtMs = 60L,
            status = DownloadTaskStatus.COMPLETED.name,
            bytesDownloaded = 2048L,
            totalBytes = 2048L,
            destinationPath = "/downloads/short-song.mp3",
        )

        val resolution = resolveCompletedPlaybackSourceFromRecord(
            record = record,
            canReadContentUri = { false },
            canReadFile = { true },
            fileLength = { 1024L },
        )

        assertNull(resolution.source)
        assertEquals(DownloadTaskStatus.FAILED.name, resolution.recordUpdate?.status)
        assertEquals("离线文件大小不匹配，已回退在线源", resolution.recordUpdate?.errorMessage)
    }

    @Test
    fun resolveCompletedPlaybackSourceFromRecord_returnsNullAndFailsRecordWhenContentUriShorterThanExpected() {
        val record = PersistedDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = "short-content",
            sourcePath = "/music/short-content.mp3",
            fileName = "short-content.mp3",
            storageId = 1L,
            createdAtMs = 70L,
            status = DownloadTaskStatus.COMPLETED.name,
            bytesDownloaded = 4096L,
            totalBytes = 4096L,
            destinationUri = "content://downloads/short-content",
        )

        val resolution = resolveCompletedPlaybackSourceFromRecord(
            record = record,
            canReadContentUri = { true },
            contentUriLength = { 1024L },
            canReadFile = { false },
        )

        assertNull(resolution.source)
        assertEquals(DownloadTaskStatus.FAILED.name, resolution.recordUpdate?.status)
        assertEquals("离线文件大小不匹配，已回退在线源", resolution.recordUpdate?.errorMessage)
    }
}
