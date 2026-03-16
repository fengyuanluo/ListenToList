package com.kutedev.easemusicplayer.viewmodels

import androidx.annotation.StringRes
import com.kutedev.easemusicplayer.R
import uniffi.ease_client_backend.SearchStorageEntriesResp
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_backend.StorageEntry
import uniffi.ease_client_backend.StorageSearchEntry
import uniffi.ease_client_backend.StorageSearchPage
import uniffi.ease_client_backend.StorageSearchScope
import uniffi.ease_client_schema.StorageType

const val STORAGE_BROWSER_SEARCH_PAGE_SIZE = 18
const val STORAGE_AGGREGATE_SEARCH_PAGE_SIZE = 10

enum class StorageSearchErrorType(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
) {
    AuthenticationFailed(
        titleRes = R.string.storage_search_error_authentication_title,
        bodyRes = R.string.storage_search_error_authentication_desc,
    ),
    Timeout(
        titleRes = R.string.storage_search_error_timeout_title,
        bodyRes = R.string.storage_search_error_timeout_desc,
    ),
    Unavailable(
        titleRes = R.string.storage_search_error_unavailable_title,
        bodyRes = R.string.storage_search_error_unavailable_desc,
    ),
    BlockedBySite(
        titleRes = R.string.storage_search_error_blocked_title,
        bodyRes = R.string.storage_search_error_blocked_desc,
    ),
    Unknown(
        titleRes = R.string.storage_search_error_unknown_title,
        bodyRes = R.string.storage_search_error_unknown_desc,
    ),
}

data class StorageSearchListUiState(
    val query: String = "",
    val scope: StorageSearchScope = StorageSearchScope.ALL,
    val parentPath: String = "/",
    val entries: List<StorageSearchEntry> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: StorageSearchErrorType? = null,
) {
    val active: Boolean
        get() = query.isNotBlank()

    val hasResults: Boolean
        get() = entries.isNotEmpty()

    val canLoadMore: Boolean
        get() = !loading && !loadingMore && error == null && entries.size < total
}

data class StorageSearchSectionUiState(
    val storage: Storage,
    val entries: List<StorageSearchEntry> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: StorageSearchErrorType? = null,
    val page: Int = 0,
) {
    val hasResults: Boolean
        get() = entries.isNotEmpty()

    val canLoadMore: Boolean
        get() = !loading && !loadingMore && error == null && entries.size < total
}

fun Storage.isStorageSearchSupported(): Boolean {
    return typ == StorageType.OPEN_LIST
}

fun StorageSearchEntry.entryTyp(): uniffi.ease_client_backend.StorageEntryType {
    if (isDir) {
        return uniffi.ease_client_backend.StorageEntryType.FOLDER
    }
    val lowerPath = path.lowercase()
    return when {
        MUSIC_EXTS.any { lowerPath.endsWith(it) } -> uniffi.ease_client_backend.StorageEntryType.MUSIC
        IMAGE_EXTS.any { lowerPath.endsWith(it) } -> uniffi.ease_client_backend.StorageEntryType.IMAGE
        LYRIC_EXTS.any { lowerPath.endsWith(it) } -> uniffi.ease_client_backend.StorageEntryType.LYRIC
        else -> uniffi.ease_client_backend.StorageEntryType.OTHER
    }
}

fun StorageSearchEntry.toStorageEntry(): StorageEntry {
    return StorageEntry(
        storageId = storageId,
        name = name,
        path = path,
        size = size,
        isDir = isDir,
    )
}

fun SearchStorageEntriesResp.pageOrNull(): StorageSearchPage? {
    return when (this) {
        is SearchStorageEntriesResp.Ok -> v1
        else -> null
    }
}

fun SearchStorageEntriesResp.errorTypeOrNull(): StorageSearchErrorType? {
    return when (this) {
        SearchStorageEntriesResp.AuthenticationFailed -> StorageSearchErrorType.AuthenticationFailed
        SearchStorageEntriesResp.Timeout -> StorageSearchErrorType.Timeout
        SearchStorageEntriesResp.Unavailable -> StorageSearchErrorType.Unavailable
        SearchStorageEntriesResp.BlockedBySite -> StorageSearchErrorType.BlockedBySite
        SearchStorageEntriesResp.Unknown -> StorageSearchErrorType.Unknown
        is SearchStorageEntriesResp.Ok -> null
    }
}

fun StorageSearchScope.labelRes(): Int {
    return when (this) {
        StorageSearchScope.ALL -> R.string.storage_search_scope_all
        StorageSearchScope.DIRECTORY -> R.string.storage_search_scope_directory
        StorageSearchScope.FILE -> R.string.storage_search_scope_file
    }
}

fun mergeSearchPages(
    current: List<StorageSearchEntry>,
    next: List<StorageSearchEntry>,
): List<StorageSearchEntry> {
    if (current.isEmpty()) {
        return next
    }
    val merged = LinkedHashMap<String, StorageSearchEntry>()
    current.forEach { entry ->
        merged[entry.path] = entry
    }
    next.forEach { entry ->
        merged[entry.path] = entry
    }
    return merged.values.toList()
}
