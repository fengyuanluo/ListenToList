package com.kutedev.easemusicplayer.debug

import android.content.Context
import android.util.Log
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DIRECT_HTTP
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DOWNLOADED_CONTENT
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_DOWNLOADED_FILE
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_LOCAL_FILE
import com.kutedev.easemusicplayer.core.PLAYBACK_ROUTE_STREAM_FALLBACK
import com.kutedev.easemusicplayer.core.PLAYBACK_SOURCE_TAG_PLAYBACK
import com.kutedev.easemusicplayer.core.PlaybackDiagnostics
import com.kutedev.easemusicplayer.singleton.Bridge
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import com.kutedev.easemusicplayer.singleton.DownloadTaskStatus
import com.kutedev.easemusicplayer.singleton.PlayerControllerRepository
import com.kutedev.easemusicplayer.singleton.PlayerRepository
import com.kutedev.easemusicplayer.singleton.PlaylistRepository
import com.kutedev.easemusicplayer.singleton.StorageRepository
import com.kutedev.easemusicplayer.viewmodels.entryTyp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ease_client_backend.RetCreatePlaylist
import uniffi.ease_client_backend.ArgCreatePlaylist
import uniffi.ease_client_backend.ArgUpsertStorage
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.ToAddMusicEntry
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctCreatePlaylist
import uniffi.ease_client_backend.ctListStorageEntryChildren
import uniffi.ease_client_backend.ctRemovePlaylist
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.StorageEntryLoc

private const val DEBUG_SMOKE_PLAYBACK_ROUTE_TIMEOUT_MS = 2_000L

@Singleton
class DebugSmokeExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: Bridge,
    private val storageRepository: StorageRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadRepository: DownloadRepository,
    private val playerControllerRepository: PlayerControllerRepository,
    private val playerRepository: PlayerRepository,
) {
    suspend fun execute(request: DebugSmokeRequest): DebugSmokeResult {
        return runCatching {
            ensureReady()
            if (!request.play.auto) {
                PlaybackDiagnostics.reset()
            }
            val prepared = preparePlayback(request)
            request.download?.let { download ->
                ensureDownloaded(
                    targetEntry = prepared.targetEntry
                        ?: error("existingPlayback 模式下不支持下载准备步骤"),
                    timeoutMs = download.waitTimeoutMs,
                )
            }

            if (request.play.auto) {
                withContext(Dispatchers.Main.immediate) {
                    PlaybackDiagnostics.reset()
                    playerControllerRepository.play(
                        id = uniffi.ease_client_schema.MusicId(prepared.musicId),
                        playlistId = uniffi.ease_client_schema.PlaylistId(prepared.playlistId),
                    )
                }
                val ready = awaitPlaybackReady(
                    targetMusicId = prepared.musicId,
                    timeoutMs = request.play.awaitReadyTimeoutMs,
                )
                var missingSourceTags = emptySet<String>()
                if (ready) {
                    val expectedTags = buildSet {
                        add(PLAYBACK_SOURCE_TAG_PLAYBACK)
                        addAll(request.assertions.requiredSourceTags)
                    }
                    val tagsReady = awaitRouteTags(
                        expectedTags = expectedTags,
                        timeoutMs = DEBUG_SMOKE_PLAYBACK_ROUTE_TIMEOUT_MS,
                    )
                    if (!tagsReady) {
                        val actualTags = PlaybackDiagnostics.historySnapshot()
                            .mapNotNull { it.sourceTag }
                            .toSet()
                        missingSourceTags = expectedTags - actualTags
                    }
                }
                val routeHistory = playbackRouteHistory()
                val playbackRoute = routeHistory.firstOrNull { it.sourceTag == PLAYBACK_SOURCE_TAG_PLAYBACK }
                val fallbackSnapshot = PlaybackDiagnostics.currentSnapshot()
                val actualResolverMode = playbackRoute?.resolverMode ?: fallbackSnapshot.route.toResolverMode()
                val resolvedUri = playbackRoute?.resolvedUri ?: fallbackSnapshot.resolvedUri
                val nextMusicId = prepared.nextMusicId
                val currentDurationSynced = if (request.assertions.requireCurrentMetadataDuration) {
                    awaitMetadataDuration(
                        musicId = prepared.musicId,
                        timeoutMs = request.assertions.metadataWaitTimeoutMs,
                    )
                } else {
                    null
                }
                val nextDurationSynced = if (request.assertions.requireNextMetadataDuration && nextMusicId != null) {
                    awaitMetadataDuration(
                        musicId = nextMusicId,
                        timeoutMs = request.assertions.metadataWaitTimeoutMs,
                    )
                } else {
                    null
                }
                if (!ready) {
                    DebugSmokeResult(
                        requestId = request.requestId,
                        status = "error",
                        stage = "play",
                        message = "等待播放就绪超时",
                        storageId = prepared.storageId,
                        playlistId = prepared.playlistId,
                        musicId = prepared.musicId,
                        targetEntryPath = prepared.targetEntryPath,
                        expectedResolverMode = request.assertions.expectedResolverMode,
                        actualResolverMode = actualResolverMode,
                        resolvedUri = resolvedUri,
                        routeHistory = routeHistory,
                        currentMetadataDurationSynced = currentDurationSynced,
                        nextMetadataDurationSynced = nextDurationSynced,
                    )
                } else {
                    val expectedResolverMode = request.assertions.expectedResolverMode
                    if (expectedResolverMode != null && actualResolverMode != expectedResolverMode) {
                        return@runCatching DebugSmokeResult(
                            requestId = request.requestId,
                            status = "error",
                            stage = "assert",
                            message = "resolver 路由不符合预期: expected=$expectedResolverMode actual=$actualResolverMode",
                            storageId = prepared.storageId,
                            playlistId = prepared.playlistId,
                            musicId = prepared.musicId,
                            targetEntryPath = prepared.targetEntryPath,
                            durationMs = readDurationOnMain(),
                            expectedResolverMode = expectedResolverMode,
                            actualResolverMode = actualResolverMode,
                            resolvedUri = resolvedUri,
                            routeHistory = routeHistory,
                            currentMetadataDurationSynced = currentDurationSynced,
                            nextMetadataDurationSynced = nextDurationSynced,
                        )
                    }
                    if (missingSourceTags.isNotEmpty()) {
                        return@runCatching DebugSmokeResult(
                            requestId = request.requestId,
                            status = "error",
                            stage = "assert",
                            message = "sourceTag 未在超时内出现: ${missingSourceTags.joinToString()}",
                            storageId = prepared.storageId,
                            playlistId = prepared.playlistId,
                            musicId = prepared.musicId,
                            targetEntryPath = prepared.targetEntryPath,
                            durationMs = readDurationOnMain(),
                            expectedResolverMode = expectedResolverMode,
                            actualResolverMode = actualResolverMode,
                            resolvedUri = resolvedUri,
                            routeHistory = routeHistory,
                            currentMetadataDurationSynced = currentDurationSynced,
                            nextMetadataDurationSynced = nextDurationSynced,
                        )
                    }
                    if (request.assertions.requireCurrentMetadataDuration && currentDurationSynced != true) {
                        return@runCatching DebugSmokeResult(
                            requestId = request.requestId,
                            status = "error",
                            stage = "assert",
                            message = "当前曲目 metadata duration 未在超时内回填",
                            storageId = prepared.storageId,
                            playlistId = prepared.playlistId,
                            musicId = prepared.musicId,
                            targetEntryPath = prepared.targetEntryPath,
                            durationMs = readDurationOnMain(),
                            expectedResolverMode = expectedResolverMode,
                            actualResolverMode = actualResolverMode,
                            resolvedUri = resolvedUri,
                            routeHistory = routeHistory,
                            currentMetadataDurationSynced = currentDurationSynced,
                            nextMetadataDurationSynced = nextDurationSynced,
                        )
                    }
                    if (request.assertions.requireNextMetadataDuration && nextMusicId != null && nextDurationSynced != true) {
                        return@runCatching DebugSmokeResult(
                            requestId = request.requestId,
                            status = "error",
                            stage = "assert",
                            message = "下一首曲目 metadata duration 未在超时内回填",
                            storageId = prepared.storageId,
                            playlistId = prepared.playlistId,
                            musicId = prepared.musicId,
                            targetEntryPath = prepared.targetEntryPath,
                            durationMs = readDurationOnMain(),
                            expectedResolverMode = expectedResolverMode,
                            actualResolverMode = actualResolverMode,
                            resolvedUri = resolvedUri,
                            routeHistory = routeHistory,
                            currentMetadataDurationSynced = currentDurationSynced,
                            nextMetadataDurationSynced = nextDurationSynced,
                        )
                    }
                    if (request.play.seekToMs > 0) {
                        withContext(Dispatchers.Main.immediate) {
                            playerControllerRepository.seek(request.play.seekToMs.toULong())
                        }
                    }
                    DebugSmokeResult(
                        requestId = request.requestId,
                        status = "ok",
                        stage = "play",
                        message = "debug smoke 执行成功",
                        storageId = prepared.storageId,
                        playlistId = prepared.playlistId,
                        musicId = prepared.musicId,
                        targetEntryPath = prepared.targetEntryPath,
                        durationMs = readDurationOnMain(),
                        expectedResolverMode = request.assertions.expectedResolverMode,
                        actualResolverMode = actualResolverMode,
                        resolvedUri = resolvedUri,
                        routeHistory = routeHistory,
                        currentMetadataDurationSynced = currentDurationSynced,
                        nextMetadataDurationSynced = nextDurationSynced,
                    )
                }
            } else {
                val routeHistory = playbackRouteHistory()
                val playbackRoute = routeHistory.firstOrNull { it.sourceTag == PLAYBACK_SOURCE_TAG_PLAYBACK }
                DebugSmokeResult(
                    requestId = request.requestId,
                    status = "ok",
                    stage = if (request.download != null) "download" else "playlist",
                    message = if (request.download != null) "下载准备完成" else "debug smoke 执行成功",
                    storageId = prepared.storageId,
                    playlistId = prepared.playlistId,
                    musicId = prepared.musicId,
                    targetEntryPath = prepared.targetEntryPath,
                    durationMs = readDurationOnMain(),
                    expectedResolverMode = request.assertions.expectedResolverMode,
                    actualResolverMode = playbackRoute?.resolverMode
                        ?: PlaybackDiagnostics.currentSnapshot().route.toResolverMode(),
                    resolvedUri = playbackRoute?.resolvedUri ?: PlaybackDiagnostics.currentSnapshot().resolvedUri,
                    routeHistory = routeHistory,
                    currentMetadataDurationSynced = null,
                    nextMetadataDurationSynced = null,
                )
            }
        }.getOrElse { error ->
            easeError("debug smoke execute failed: $error")
            val routeHistory = playbackRouteHistory()
            DebugSmokeResult(
                requestId = request.requestId,
                status = "error",
                stage = "execute",
                message = error.message ?: error.toString(),
                targetEntryPath = request.playlist.targetEntryPath,
                expectedResolverMode = request.assertions.expectedResolverMode,
                actualResolverMode = PlaybackDiagnostics.currentSnapshot().route.toResolverMode(),
                resolvedUri = PlaybackDiagnostics.currentSnapshot().resolvedUri,
                routeHistory = routeHistory,
                currentMetadataDurationSynced = null,
                nextMetadataDurationSynced = null,
            )
        }.also { result ->
            writeImmediateResult(result)
        }
    }

    private suspend fun preparePlayback(request: DebugSmokeRequest): PreparedPlaybackContext {
        request.existingPlayback?.let { existing ->
            return PreparedPlaybackContext(
                storageId = null,
                playlistId = existing.playlistId,
                musicId = existing.musicId,
                nextMusicId = null,
                targetEntryPath = request.playlist.targetEntryPath,
                targetEntry = null,
            )
        }

        val storage = upsertStorage(request)
        val songs = listFolderSongs(storage, request.playlist.folderPath)
        val targetEntryPath = request.download?.targetEntryPath ?: request.playlist.targetEntryPath
        val targetIndex = songs.indexOfFirst { it.path == targetEntryPath }
        require(targetIndex >= 0) {
            "目标歌曲不存在于目录中: $targetEntryPath"
        }
        val created = createFolderPlaylist(request, songs)
        val playlistId = created.id.value
        val musicId = created.musicIds.getOrNull(targetIndex)?.id?.value
            ?: error("未能定位创建后的曲目索引: $targetIndex")
        val nextMusicId = created.musicIds.getOrNull(targetIndex + 1)?.id?.value
        return PreparedPlaybackContext(
            storageId = storage.id.value,
            playlistId = playlistId,
            musicId = musicId,
            nextMusicId = nextMusicId,
            targetEntryPath = targetEntryPath,
            targetEntry = songs[targetIndex],
        )
    }

    fun writeImmediateResult(result: DebugSmokeResult) {
        val file = File(context.filesDir, DEBUG_SMOKE_RESULT_FILE)
        writeDebugSmokeResult(file, result)
        Log.i("DebugSmoke", "$DEBUG_SMOKE_LOG_PREFIX ${encodeDebugSmokeResult(result)}")
        easeLog("$DEBUG_SMOKE_LOG_PREFIX ${encodeDebugSmokeResult(result)}")
    }

    private suspend fun ensureReady() {
        bridge.initialize()
        storageRepository.reload()
        playlistRepository.reload()
        playerRepository.reload()
        val hasController = withContext(Dispatchers.Main.immediate) {
            playerControllerRepository.hasMediaController()
        }
        if (!hasController) {
            val controller = connectDebugMediaController(context)
            withContext(Dispatchers.Main.immediate) {
                playerControllerRepository.setupMediaController(controller)
            }
        }
    }

    private suspend fun upsertStorage(request: DebugSmokeRequest): Storage {
        if (request.resetBefore && request.storage.replaceExistingAlias) {
            storageRepository.reload()
            storageRepository.storages.value
                .firstOrNull { it.alias == request.storage.alias }
                ?.let { existing ->
                    storageRepository.remove(existing.id)
                }
        }

        val existingId = storageRepository.storages.value
            .firstOrNull { it.alias == request.storage.alias }
            ?.id

        storageRepository.upsertStorage(
            ArgUpsertStorage(
                id = existingId,
                addr = request.storage.addr,
                alias = request.storage.alias,
                username = request.storage.username,
                password = request.storage.password,
                isAnonymous = request.storage.isAnonymous,
                typ = request.storage.type.toStorageType(),
                defaultPath = "/",
            )
        )
        storageRepository.reload()
        return storageRepository.storages.value
            .firstOrNull { it.alias == request.storage.alias }
            ?: error("未能创建或刷新存储: ${request.storage.alias}")
    }

    private suspend fun listFolderSongs(storage: Storage, folderPath: String): List<StorageEntry> {
        val response = bridge.runRaw { backend ->
            ctListStorageEntryChildren(
                backend,
                StorageEntryLoc(storage.id, folderPath)
            )
        }
        val entries = when (response) {
            is ListStorageEntryChildrenResp.Ok -> response.v1
            ListStorageEntryChildrenResp.AuthenticationFailed -> {
                error("列目录认证失败: ${storage.alias}")
            }
            ListStorageEntryChildrenResp.Timeout -> {
                error("列目录超时: ${storage.alias}")
            }
            ListStorageEntryChildrenResp.Unavailable -> {
                error("列目录能力不可用: ${storage.alias}")
            }
            ListStorageEntryChildrenResp.BlockedBySite -> {
                error("列目录被站点拦截: ${storage.alias}")
            }
            ListStorageEntryChildrenResp.Unknown -> {
                error("列目录失败: ${storage.alias}")
            }
        }
        return entries.filter { it.entryTyp() == StorageEntryType.MUSIC }
    }

    private suspend fun createFolderPlaylist(
        request: DebugSmokeRequest,
        songs: List<StorageEntry>,
    ): RetCreatePlaylist {
        if (request.resetBefore) {
            playlistRepository.playlists.value
                .firstOrNull { it.meta.title == request.playlist.playlistName }
                ?.let { existing ->
                    bridge.runRaw { backend -> ctRemovePlaylist(backend, existing.meta.id) }
                    playlistRepository.reload()
                }
        }

        val created = bridge.runRaw { backend ->
            ctCreatePlaylist(
                backend,
                ArgCreatePlaylist(
                    title = request.playlist.playlistName,
                    cover = null,
                    entries = songs.map { song -> ToAddMusicEntry(song, song.name) },
                )
            )
        }
        playlistRepository.reload()
        return created
    }

    private suspend fun awaitPlaybackReady(
        targetMusicId: Long,
        timeoutMs: Long,
    ): Boolean {
        val ready = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val current = playerRepository.music.value?.meta?.id?.value
                val duration = readDurationOnMain()
                val isReady = current == targetMusicId &&
                    !playerRepository.loading.value &&
                    (playerRepository.playing.value || duration != null)
                if (isReady) {
                    return@withTimeoutOrNull true
                }
                delay(200)
            }
        }
        return ready == true
    }

    private suspend fun readDurationOnMain(): Long? {
        return withContext(Dispatchers.Main.immediate) {
            playerControllerRepository.getDuration()
        }
    }

    private suspend fun awaitMetadataDuration(
        musicId: Long,
        timeoutMs: Long,
    ): Boolean {
        val synced = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val music = bridge.runRaw { backend ->
                    ctGetMusic(backend, uniffi.ease_client_schema.MusicId(musicId))
                }
                if (music?.meta?.duration != null) {
                    return@withTimeoutOrNull true
                }
                delay(250)
            }
        }
        return synced == true
    }

    private suspend fun ensureDownloaded(
        targetEntry: StorageEntry,
        timeoutMs: Long,
    ) {
        downloadRepository.enqueueEntries(listOf(targetEntry))
        val completed = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val resolved = downloadRepository.resolveCompletedPlaybackSource(
                    storageId = targetEntry.storageId.value,
                    sourcePath = targetEntry.path,
                )
                if (resolved != null) {
                    return@withTimeoutOrNull true
                }
                val task = downloadRepository.tasks.first()
                    .firstOrNull { item ->
                        item.storageId == targetEntry.storageId.value &&
                            item.sourcePath == targetEntry.path
                    }
                if (task != null && task.status in setOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.CANCELLED)) {
                    error("下载任务失败: ${task.errorMessage ?: targetEntry.path}")
                }
                delay(250)
            }
        }
        check(completed == true) {
            "等待下载完成超时: ${targetEntry.path}"
        }
    }

    private fun String?.toResolverMode(): DebugSmokeResolverMode? {
        return when (this) {
            PLAYBACK_ROUTE_DIRECT_HTTP -> DebugSmokeResolverMode.DIRECT_HTTP
            PLAYBACK_ROUTE_LOCAL_FILE -> DebugSmokeResolverMode.LOCAL_FILE
            PLAYBACK_ROUTE_DOWNLOADED_FILE -> DebugSmokeResolverMode.DOWNLOADED_FILE
            PLAYBACK_ROUTE_DOWNLOADED_CONTENT -> DebugSmokeResolverMode.DOWNLOADED_CONTENT
            PLAYBACK_ROUTE_STREAM_FALLBACK -> DebugSmokeResolverMode.STREAM_FALLBACK
            else -> null
        }
    }

    private fun playbackRouteHistory(): List<DebugSmokeRouteRecord> {
        return PlaybackDiagnostics.historySnapshot().map { snapshot ->
            DebugSmokeRouteRecord(
                musicId = snapshot.musicId,
                route = snapshot.route,
                resolvedUri = snapshot.resolvedUri,
                sourceTag = snapshot.sourceTag,
                resolverMode = snapshot.route.toResolverMode(),
                routeRefreshCount = snapshot.routeRefreshCount,
                recoverySkipCount = snapshot.recoverySkipCount,
                cacheBypassCount = snapshot.cacheBypassCount,
                metadataFailureCount = snapshot.metadataFailureCount,
                lastPlaybackErrorCode = snapshot.lastPlaybackErrorCode,
                lastPlaybackErrorName = snapshot.lastPlaybackErrorName,
                lastCacheBypassReason = snapshot.lastCacheBypassReason,
                lastMetadataFailureMusicId = snapshot.lastMetadataFailureMusicId,
                lastMetadataFailureStage = snapshot.lastMetadataFailureStage,
                lastMetadataFailureMessage = snapshot.lastMetadataFailureMessage,
            )
        }
    }

    private suspend fun awaitRouteTags(
        expectedTags: Set<String>,
        timeoutMs: Long,
    ): Boolean {
        val satisfied = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val tags = PlaybackDiagnostics.historySnapshot()
                    .mapNotNull { it.sourceTag }
                    .toSet()
                if (expectedTags.all(tags::contains)) {
                    return@withTimeoutOrNull true
                }
                delay(250)
            }
        }
        return satisfied == true
    }
}

private data class PreparedPlaybackContext(
    val storageId: Long?,
    val playlistId: Long,
    val musicId: Long,
    val nextMusicId: Long?,
    val targetEntryPath: String,
    val targetEntry: StorageEntry?,
)
