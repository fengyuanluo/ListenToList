package com.kutedev.easemusicplayer.viewmodels

import uniffi.ease_client_backend.ArgUpsertStorage
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_schema.StorageType

fun normalizeStorageDefaultPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) {
        return "/"
    }
    return normalizeBrowserPath(trimmed)
}

fun StorageType.supportsStorageDefaultPath(): Boolean {
    return this == StorageType.OPEN_LIST
}

fun Storage.supportsStorageDefaultPath(): Boolean {
    return typ.supportsStorageDefaultPath()
}

fun ArgUpsertStorage.supportsStorageDefaultPath(): Boolean {
    return typ.supportsStorageDefaultPath()
}

fun Storage.browserEntryDefaultPathOrNull(): String? {
    if (!supportsStorageDefaultPath()) {
        return null
    }
    return normalizeStorageDefaultPath(defaultPath)
}
