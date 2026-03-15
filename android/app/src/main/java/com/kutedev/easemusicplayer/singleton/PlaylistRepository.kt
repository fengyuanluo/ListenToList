package com.kutedev.easemusicplayer.singleton

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kutedev.easemusicplayer.core.BuildMediaContext
import com.kutedev.easemusicplayer.core.PlaybackDataSourceFactory
import com.kutedev.easemusicplayer.core.PlaybackCache
import com.kutedev.easemusicplayer.core.PLAYBACK_SOURCE_TAG_METADATA
import com.kutedev.easemusicplayer.core.buildMediaItem
import com.kutedev.easemusicplayer.core.syncMetadataUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import uniffi.ease_client_backend.AddedMusic
import uniffi.ease_client_backend.ArgCreatePlaylist
import uniffi.ease_client_backend.ArgRemoveMusicFromPlaylist
import uniffi.ease_client_backend.ArgReorderPlaylist
import uniffi.ease_client_backend.ArgUpdatePlaylist
import uniffi.ease_client_backend.MusicAbstract
import uniffi.ease_client_backend.PlaylistAbstract
import uniffi.ease_client_backend.ctCreatePlaylist
import uniffi.ease_client_backend.ctListPlaylist
import uniffi.ease_client_backend.ctRemoveMusicFromPlaylist
import uniffi.ease_client_backend.ctRemovePlaylist
import uniffi.ease_client_backend.ctUpdatePlaylist
import uniffi.ease_client_backend.ctsGetMusicAbstract
import uniffi.ease_client_backend.ctsReorderPlaylist
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_schema.MusicId
import uniffi.ease_client_schema.PlaylistId

private enum class MetadataSyncPriority {
    PLAYBACK_PRIME,
    IMPORT_BACKLOG,
}

private data class MetadataSyncRequest(
    val musicId: MusicId,
    val priority: MetadataSyncPriority,
)

private const val METADATA_SYNC_LOADING_WAIT_MS = 15_000L
private const val METADATA_SYNC_TIMEOUT_MS = 20_000L

@OptIn(FlowPreview::class)
@Singleton
class PlaylistRepository @Inject constructor(
    private val bridge: Bridge,
    private val storageRepository: StorageRepository,
    private val playerRepository: PlayerRepository,
    @ApplicationContext private val appContext: Context,
    private val _scope: CoroutineScope
) {
    private val _playlists = MutableStateFlow(persistentListOf<PlaylistAbstract>())
    private val _syncedTotalDuration = MutableSharedFlow<MusicId>()
    private val _debouncedReloadEvent = MutableSharedFlow<Unit>()
    private val _preRemovePlaylistEvent = MutableSharedFlow<PlaylistId>()
    private val _preRemoveMusicEvent = MutableSharedFlow<ArgRemoveMusicFromPlaylist>()
    private val metadataQueueLock = Any()
    private val metadataQueue = mutableListOf<MetadataSyncRequest>()
    private var metadataQueueJob: Job? = null
    private var activeMetadataId: MusicId? = null
    private var metadataPlayer: ExoPlayer? = null

    val playlists = _playlists.asStateFlow()
    val syncedTotalDuration = _syncedTotalDuration.asSharedFlow()
    val preRemovePlaylistEvent = _preRemovePlaylistEvent.asSharedFlow()
    val preRemoveMusicEvent = _preRemoveMusicEvent.asSharedFlow()

    init {
        _scope.launch {
            _debouncedReloadEvent.debounce(500).collect {
                reload()
            }
        }
        _scope.launch {
            storageRepository.onRemoveStorageEvent.collect {
                reload()
            }
        }
    }

    fun createPlaylist(arg: ArgCreatePlaylist) {
        _scope.launch {
            val created = bridge.run { ctCreatePlaylist(it, arg) }
            if ((created?.musicIds?.size ?: 0) > 0) {
                requestTotalDuration(created!!.musicIds)
            }
            reload()
        }
    }

    fun editPlaylist(arg: ArgUpdatePlaylist) {
        _scope.launch {
            bridge.run { ctUpdatePlaylist(it, arg) }
            reload()
        }
    }

    fun removePlaylist(id: PlaylistId) {
        _scope.launch {
            _preRemovePlaylistEvent.emit(id)
            bridge.run { ctRemovePlaylist(it, id) }
            reload()
        }
    }

    fun requestTotalDuration(added: List<AddedMusic>) {
        added.forEach { item ->
            enqueueMetadataSync(item.id, MetadataSyncPriority.IMPORT_BACKLOG)
        }
    }

    fun primePlaybackMetadata(currentId: MusicId?, nextId: MusicId?) {
        if (nextId != null) {
            enqueueMetadataSync(nextId, MetadataSyncPriority.PLAYBACK_PRIME)
        }
        if (currentId != null) {
            enqueueMetadataSync(currentId, MetadataSyncPriority.PLAYBACK_PRIME)
        }
    }

    fun playlistMoveTo(fromIndex: Int, toIndex: Int) {
        val from = _playlists.value.getOrNull(fromIndex) ?: return

        _playlists.value = _playlists.value
            .removeAt(fromIndex)
            .add(toIndex, from)

        val a = _playlists.value.getOrNull(toIndex - 1)
        val b = _playlists.value.getOrNull(toIndex + 1)

        _scope.launch {
            bridge.runSync {
                ctsReorderPlaylist(
                    it, ArgReorderPlaylist(
                        id = from.meta.id,
                        a = a?.meta?.id,
                        b = b?.meta?.id,
                    )
                )
            }
            scheduleReload()
        }
    }

    suspend fun removeMusic(playlistId: PlaylistId, musicId: MusicId) {
        val arg = ArgRemoveMusicFromPlaylist(
            playlistId = playlistId,
            musicId = musicId
        )
        _preRemoveMusicEvent.emit(arg)
        bridge.run { backend -> ctRemoveMusicFromPlaylist(backend, arg) }

        reload()
    }

    fun scheduleReload() {
        _scope.launch {
            _debouncedReloadEvent.emit(Unit)
        }
    }

    suspend fun reload() {
        _playlists.value = bridge.run { ctListPlaylist(it).toPersistentList() } ?: persistentListOf()
    }

    private fun enqueueMetadataSync(id: MusicId, priority: MetadataSyncPriority) {
        synchronized(metadataQueueLock) {
            if (activeMetadataId == id) {
                return
            }
            val existingIndex = metadataQueue.indexOfFirst { it.musicId == id }
            if (existingIndex >= 0) {
                if (priority == MetadataSyncPriority.PLAYBACK_PRIME && metadataQueue[existingIndex].priority != priority) {
                    val existing = metadataQueue.removeAt(existingIndex)
                    metadataQueue.add(0, existing.copy(priority = priority))
                }
            } else {
                val request = MetadataSyncRequest(id, priority)
                if (priority == MetadataSyncPriority.PLAYBACK_PRIME) {
                    metadataQueue.add(0, request)
                } else {
                    metadataQueue.add(request)
                }
            }
        }
        startMetadataQueueIfNeeded()
    }

    private fun startMetadataQueueIfNeeded() {
        synchronized(metadataQueueLock) {
            if (metadataQueueJob?.isActive == true) {
                return
            }
            metadataQueueJob = _scope.launch {
                drainMetadataQueue()
            }
        }
    }

    private suspend fun drainMetadataQueue() {
        while (true) {
            val request = synchronized(metadataQueueLock) {
                if (metadataQueue.isEmpty()) {
                    activeMetadataId = null
                    null
                } else {
                    metadataQueue.removeAt(0).also { activeMetadataId = it.musicId }
                }
            } ?: break

            try {
                waitForPlaybackToSettle()
                syncMetadataFor(request.musicId)
            } catch (error: Exception) {
                easeError("metadata sync failed for music=${request.musicId.value}: $error")
            } finally {
                synchronized(metadataQueueLock) {
                    if (activeMetadataId == request.musicId) {
                        activeMetadataId = null
                    }
                }
            }
        }
        metadataQueueJob = null
        synchronized(metadataQueueLock) {
            if (metadataQueue.isNotEmpty()) {
                startMetadataQueueIfNeeded()
            }
        }
    }

    private suspend fun waitForPlaybackToSettle() {
        val settled = withTimeoutOrNull(METADATA_SYNC_LOADING_WAIT_MS) {
            while (playerRepository.loading.value) {
                delay(250)
            }
            true
        }
        if (settled != true) {
            easeError("metadata sync wait for playback settle timed out after ${METADATA_SYNC_LOADING_WAIT_MS}ms")
        }
    }

    private suspend fun syncMetadataFor(id: MusicId) {
        val musicAbstract = bridge.runSync { ctsGetMusicAbstract(it, id) } ?: return
        if (musicAbstract.meta.duration != null) {
            return
        }
        easeLog("metadata sync start: music=${id.value}")
        val player = getMetadataPlayer()
        runMetadataSync(player, musicAbstract)
    }

    private suspend fun getMetadataPlayer(): ExoPlayer = withContext(Dispatchers.Main) {
        metadataPlayer?.let { return@withContext it }
        val upstreamFactory = PlaybackDataSourceFactory.create(
            bridge = bridge,
            scope = _scope,
            sourceTag = PLAYBACK_SOURCE_TAG_METADATA,
        )
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    PlaybackCache.buildCacheDataSourceFactory(appContext, upstreamFactory)
                )
            )
            .build()
        metadataPlayer = player
        player
    }

    private suspend fun runMetadataSync(player: ExoPlayer, musicAbstract: MusicAbstract) {
        withContext(Dispatchers.Main) {
            player.stop()
            player.clearMediaItems()
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            var metadataJob: Job? = null
            val finishSignal = CompletableDeferred<Unit>()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (metadataJob == null) {
                            val job = syncMetadataUtil(
                                scope = _scope,
                                bridge = bridge,
                                player = player,
                                onUpdated = { updatedId ->
                                    _scope.launch {
                                        _syncedTotalDuration.emit(updatedId)
                                        reload()
                                    }
                                },
                                onFinished = {
                                    finishSignal.complete(Unit)
                                }
                            )
                            metadataJob = job
                            if (job == null) {
                                finishSignal.complete(Unit)
                            }
                        }
                    } else if ((playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) && metadataJob?.isActive != true) {
                        finishSignal.complete(Unit)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    easeError("request total duration failed: $error")
                    finishSignal.complete(Unit)
                }
            }

            player.addListener(listener)

            continuation.invokeOnCancellation {
                player.removeListener(listener)
                metadataJob?.cancel()
                _scope.launch(Dispatchers.Main) {
                    player.stop()
                    player.clearMediaItems()
                }
            }

            _scope.launch(Dispatchers.Main) {
                val mediaItem = buildMediaItem(
                    BuildMediaContext(
                        bridge = bridge,
                        scope = _scope,
                    ),
                    musicAbstract
                )
                player.setMediaItem(mediaItem)
                player.prepare()
            }

            _scope.launch {
                val completed = withTimeoutOrNull(METADATA_SYNC_TIMEOUT_MS) {
                    finishSignal.await()
                }
                if (completed == null) {
                    easeError("request total duration timed out after ${METADATA_SYNC_TIMEOUT_MS}ms")
                }
                withContext(Dispatchers.Main) {
                    player.removeListener(listener)
                    metadataJob?.cancel()
                    player.stop()
                    player.clearMediaItems()
                }
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }
}
