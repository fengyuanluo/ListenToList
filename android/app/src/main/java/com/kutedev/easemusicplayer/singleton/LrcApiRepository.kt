package com.kutedev.easemusicplayer.singleton

import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.core.ProbedMusicMetadata
import com.kutedev.easemusicplayer.core.probeMusicMetadataDirectly
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.ease_client_backend.ArgUpdateMusicCover
import uniffi.ease_client_backend.LrcApiConfig
import uniffi.ease_client_backend.LrcApiFetchResult
import uniffi.ease_client_backend.LrcApiFetchStatus
import uniffi.ease_client_backend.LrcApiQuery
import uniffi.ease_client_backend.Lyrics
import uniffi.ease_client_backend.Music
import uniffi.ease_client_backend.ctFetchLrcapiMusicSupplement
import uniffi.ease_client_backend.ctGetMusic
import uniffi.ease_client_backend.ctsUpdateMusicCover
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_backend.easeLog
import uniffi.ease_client_backend.LyricLoadState
import uniffi.ease_client_schema.MusicId

data class LrcApiLyricsUiState(
    val musicId: MusicId? = null,
    val lyrics: Lyrics? = null,
    val state: LyricLoadState = LyricLoadState.MISSING,
)

private data class LrcApiCacheKey(
    val musicId: Long,
    val baseUrl: String,
    val authKey: String,
    val title: String,
    val artist: String,
    val album: String,
)

internal data class LrcApiCachedResult(
    val lyrics: Lyrics?,
    val lyricsStatus: LrcApiFetchStatus,
    val cover: ByteArray?,
    val coverStatus: LrcApiFetchStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LrcApiCachedResult) return false

        if (lyrics != other.lyrics) return false
        if (lyricsStatus != other.lyricsStatus) return false
        if (cover != null) {
            if (other.cover == null) return false
            if (!cover.contentEquals(other.cover)) return false
        } else if (other.cover != null) {
            return false
        }
        return coverStatus == other.coverStatus
    }

    override fun hashCode(): Int {
        var result = lyrics?.hashCode() ?: 0
        result = 31 * result + lyricsStatus.hashCode()
        result = 31 * result + (cover?.contentHashCode() ?: 0)
        result = 31 * result + coverStatus.hashCode()
        return result
    }
}

private const val MAX_LRCAPI_CACHE_ENTRIES = 64

@Singleton
class LrcApiRepository @Inject constructor(
    private val bridge: Bridge,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: LrcApiSettingsRepository,
    private val toastRepository: ToastRepository,
    private val scope: CoroutineScope,
) {
    private val _currentLyricsState = MutableStateFlow(LrcApiLyricsUiState())
    private val cacheLock = Any()
    private val requestMutex = Mutex()
    private val resultCache = object : LinkedHashMap<LrcApiCacheKey, LrcApiCachedResult>(
        MAX_LRCAPI_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LrcApiCacheKey, LrcApiCachedResult>?,
        ): Boolean {
            return size > MAX_LRCAPI_CACHE_ENTRIES
        }
    }
    private var lastSettingsFingerprint: String? = null

    val currentLyricsState = _currentLyricsState.asStateFlow()

    init {
        scope.launch {
            combine(playerRepository.music, settingsRepository.settings) { music, settings ->
                music to settings
            }.collectLatest { (music, settings) ->
                clearCacheIfSettingsChanged(settings)
                refreshCurrentMusic(
                    music = music,
                    settings = settings,
                    forceRefresh = false,
                    showFailureToast = false,
                )
            }
        }
    }

    fun retryCurrentMusic(showFailureToast: Boolean = true) {
        val music = playerRepository.music.value ?: return
        val settings = settingsRepository.settings.value
        scope.launch {
            refreshCurrentMusic(
                music = music,
                settings = settings,
                forceRefresh = true,
                showFailureToast = showFailureToast,
            )
        }
    }

    private suspend fun refreshCurrentMusic(
        music: Music?,
        settings: LrcApiSettings,
        forceRefresh: Boolean,
        showFailureToast: Boolean,
    ) {
        if (music == null) {
            _currentLyricsState.value = LrcApiLyricsUiState()
            return
        }

        val currentMusicId = music.meta.id
        val localLyricLoaded = music.lyric?.loadedState == LyricLoadState.LOADED
        val needsLyrics = !localLyricLoaded
        val needsCover = music.cover == null

        if (!settings.isReadyForFetch()) {
            _currentLyricsState.value = LrcApiLyricsUiState(
                musicId = currentMusicId,
                lyrics = null,
                state = LyricLoadState.MISSING,
            )
            return
        }

        if (!needsLyrics && !needsCover) {
            _currentLyricsState.value = LrcApiLyricsUiState(
                musicId = currentMusicId,
                lyrics = null,
                state = LyricLoadState.MISSING,
            )
            return
        }

        val metadataHint = resolveMetadataHint(currentMusicId)
        val querySpec = buildLrcApiQuerySpec(
            fallbackTitle = music.meta.title,
            fallbackPath = music.loc.path,
            metadataHint = metadataHint,
        )
        if (!querySpec.canQueryLyrics() && !querySpec.canQueryCover()) {
            _currentLyricsState.value = LrcApiLyricsUiState(
                musicId = currentMusicId,
                lyrics = null,
                state = LyricLoadState.MISSING,
            )
            return
        }

        if (needsLyrics) {
            _currentLyricsState.value = LrcApiLyricsUiState(
                musicId = currentMusicId,
                lyrics = null,
                state = LyricLoadState.LOADING,
            )
        } else {
            _currentLyricsState.value = LrcApiLyricsUiState(
                musicId = currentMusicId,
                lyrics = null,
                state = LyricLoadState.MISSING,
            )
        }

        val cacheKey = LrcApiCacheKey(
            musicId = currentMusicId.value,
            baseUrl = normalizeLrcApiBaseUrl(settings.baseUrl),
            authKey = settings.authKey.trim(),
            title = querySpec.title,
            artist = querySpec.artist,
            album = querySpec.album,
        )
        if (forceRefresh) {
            synchronized(cacheLock) {
                resultCache.remove(cacheKey)
            }
        }

        val result = resolveOrFetch(cacheKey, settings, querySpec)
        val activeMusicId = playerRepository.music.value?.meta?.id
        if (activeMusicId != currentMusicId) {
            return
        }

        val fetchedCover = result.cover
        if (needsCover && result.coverStatus == LrcApiFetchStatus.LOADED && fetchedCover != null) {
            persistFetchedCover(currentMusicId, fetchedCover)
        }

        val nextState = mapLyricsUiState(currentMusicId, result)
        _currentLyricsState.value = nextState

        if (showFailureToast) {
            emitFailureToast(result)
        }
    }

    private suspend fun resolveMetadataHint(musicId: MusicId): LrcApiMetadataHint? {
        val metadata = probeMusicMetadataDirectly(bridge, musicId) ?: return null
        return metadata.toLrcApiMetadataHint()
    }

    private suspend fun resolveOrFetch(
        cacheKey: LrcApiCacheKey,
        settings: LrcApiSettings,
        querySpec: LrcApiQuerySpec,
    ): LrcApiFetchResult {
        val cached = synchronized(cacheLock) { resultCache[cacheKey]?.toFetchResult() }
        if (cached != null) {
            return cached
        }

        return requestMutex.withLock {
            val lockedCached = synchronized(cacheLock) { resultCache[cacheKey]?.toFetchResult() }
            if (lockedCached != null) {
                return@withLock lockedCached
            }

            val result = try {
                ctFetchLrcapiMusicSupplement(
                    LrcApiConfig(
                        enabled = settings.enabled,
                        baseUrl = settings.baseUrl,
                        authKey = settings.authKey.ifBlank { null },
                    ),
                    LrcApiQuery(
                        title = querySpec.title,
                        artist = querySpec.artist,
                        album = querySpec.album,
                    ),
                )
            } catch (error: Exception) {
                easeError("lrcapi fetch failed: $error")
                LrcApiFetchResult(
                    lyrics = null,
                    lyricsStatus = LrcApiFetchStatus.FAILED,
                    cover = null,
                    coverStatus = LrcApiFetchStatus.FAILED,
                )
            }

            synchronized(cacheLock) {
                resultCache[cacheKey] = result.toCachedResult()
            }
            result
        }
    }

    private fun clearCacheIfSettingsChanged(settings: LrcApiSettings) {
        val fingerprint = buildSettingsFingerprint(settings)
        synchronized(cacheLock) {
            if (lastSettingsFingerprint == fingerprint) {
                return
            }
            lastSettingsFingerprint = fingerprint
            resultCache.clear()
        }
    }

    private suspend fun persistFetchedCover(musicId: MusicId, cover: ByteArray) {
        withContext(Dispatchers.IO) {
            bridge.runSync { backend ->
                ctsUpdateMusicCover(
                    backend,
                    ArgUpdateMusicCover(
                        id = musicId,
                        cover = cover,
                    )
                )
            }
            val updated = bridge.run { backend -> ctGetMusic(backend, musicId) }
            if (updated != null) {
                playerRepository.updateCurrentMusic(updated)
                easeLog("lrcapi cover updated for music=${musicId.value}")
            }
        }
    }

    private fun mapLyricsUiState(
        musicId: MusicId,
        result: LrcApiFetchResult,
    ): LrcApiLyricsUiState {
        return when (result.lyricsStatus) {
            LrcApiFetchStatus.LOADED -> LrcApiLyricsUiState(
                musicId = musicId,
                lyrics = result.lyrics,
                state = LyricLoadState.LOADED,
            )
            LrcApiFetchStatus.MISSING,
            LrcApiFetchStatus.DISABLED,
            LrcApiFetchStatus.INVALID_CONFIG -> LrcApiLyricsUiState(
                musicId = musicId,
                lyrics = null,
                state = LyricLoadState.MISSING,
            )
            LrcApiFetchStatus.UNAUTHORIZED,
            LrcApiFetchStatus.FAILED -> LrcApiLyricsUiState(
                musicId = musicId,
                lyrics = null,
                state = LyricLoadState.FAILED,
            )
        }
    }

    private fun emitFailureToast(result: LrcApiFetchResult) {
        when (result.lyricsStatus) {
            LrcApiFetchStatus.UNAUTHORIZED,
            LrcApiFetchStatus.INVALID_CONFIG -> toastRepository.emitToastRes(R.string.lrcapi_toast_auth_failed)
            LrcApiFetchStatus.FAILED -> toastRepository.emitToastRes(R.string.lrcapi_toast_fetch_failed)
            LrcApiFetchStatus.MISSING -> toastRepository.emitToastRes(R.string.lrcapi_toast_lyric_missing)
            else -> {}
        }
    }
}

private fun buildSettingsFingerprint(settings: LrcApiSettings): String {
    return listOf(
        settings.enabled.toString(),
        normalizeLrcApiBaseUrl(settings.baseUrl),
        settings.authKey.trim(),
    ).joinToString("|")
}

internal fun LrcApiFetchResult.toCachedResult(): LrcApiCachedResult {
    return LrcApiCachedResult(
        lyrics = lyrics,
        lyricsStatus = lyricsStatus,
        cover = cover,
        coverStatus = coverStatus,
    )
}

internal fun LrcApiCachedResult.toFetchResult(): LrcApiFetchResult {
    return LrcApiFetchResult(
        lyrics = lyrics,
        lyricsStatus = lyricsStatus,
        cover = cover,
        coverStatus = coverStatus,
    )
}

private fun ProbedMusicMetadata.toLrcApiMetadataHint(): LrcApiMetadataHint {
    return LrcApiMetadataHint(
        title = title,
        artist = artist,
        album = album,
    )
}
