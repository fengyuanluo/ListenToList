package com.kutedev.easemusicplayer.singleton

import com.kutedev.easemusicplayer.viewmodels.entryTyp
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ease_client_backend.ArgSearchStorageEntries
import uniffi.ease_client_backend.ListStorageEntryChildrenResp
import uniffi.ease_client_backend.SearchStorageEntriesResp
import uniffi.ease_client_backend.StorageEntryType
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_backend.ctListStorageEntryChildren
import uniffi.ease_client_backend.ctSearchStorageEntries
import uniffi.ease_client_schema.StorageEntryLoc

@Singleton
class StorageSearchRepository @Inject constructor(
    private val bridge: Bridge,
    private val playerControllerRepository: PlayerControllerRepository,
    private val toastRepository: ToastRepository,
) {
    suspend fun search(
        storageId: uniffi.ease_client_schema.StorageId,
        parent: String,
        keywords: String,
        scope: StorageSearchScope,
        page: Int,
        perPage: Int,
    ): SearchStorageEntriesResp {
        val response = bridge.run {
            ctSearchStorageEntries(
                it,
                ArgSearchStorageEntries(
                    storageId = storageId,
                    parent = parent,
                    keywords = keywords,
                    scope = scope,
                    page = page.toUInt(),
                    perPage = perPage.toUInt(),
                )
            )
        }
        return response ?: SearchStorageEntriesResp.Unknown
    }

    suspend fun playSearchEntry(entry: StorageSearchEntry): Boolean {
        val response = try {
            bridge.runRaw {
                ctListStorageEntryChildren(
                    it,
                    StorageEntryLoc(entry.storageId, entry.parentPath)
                )
            }
        } catch (_: Exception) {
            toastRepository.emitToastRes(com.kutedev.easemusicplayer.R.string.storage_search_play_failed)
            return false
        }
        return when (response) {
            is ListStorageEntryChildrenResp.Ok -> {
                val songs = response.v1.filter { item -> item.entryTyp() == StorageEntryType.MUSIC }
                val target = songs.firstOrNull { item -> item.path == entry.path }
                if (target == null) {
                    toastRepository.emitToastRes(com.kutedev.easemusicplayer.R.string.storage_search_target_missing)
                    false
                } else {
                    playerControllerRepository.playFolder(
                        storageId = entry.storageId,
                        folderPath = entry.parentPath,
                        songs = songs,
                        targetEntryPath = target.path,
                    )
                    true
                }
            }

            ListStorageEntryChildrenResp.AuthenticationFailed -> {
                toastRepository.emitToastRes(com.kutedev.easemusicplayer.R.string.storage_search_play_auth_failed)
                false
            }

            ListStorageEntryChildrenResp.Timeout -> {
                toastRepository.emitToastRes(com.kutedev.easemusicplayer.R.string.storage_search_play_timeout)
                false
            }

            ListStorageEntryChildrenResp.Unknown -> {
                toastRepository.emitToastRes(com.kutedev.easemusicplayer.R.string.storage_search_play_failed)
                false
            }
        }
    }
}
