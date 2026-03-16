package com.kutedev.easemusicplayer.core

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

fun RouteHome(): String {
    return "Home"
}

fun isRouteHome(route: String): Boolean {
    return route == "Home"
}

fun RouteAddDevices(id: String): String {
    return "AddDevices/${id}"
}

fun RouteStorageBrowser(
    id: String,
    path: String? = null,
    rawPath: Boolean = false,
): String {
    val route = "StorageBrowser/${id}"
    if (path == null) {
        return route
    }
    val value = if (rawPath) path else Uri.encode(path)
    return "$route?path=$value"
}

fun RouteStorageSearch(
    query: String = "",
    rawQuery: Boolean = false,
): String {
    val encoded = if (rawQuery) query else Uri.encode(query)
    return "StorageSearch?query=$encoded"
}

fun RoutePlaylist(id: String): String {
    return "Playlist/${id}"
}

fun isRoutePlaylist(route: String): Boolean {
    return route.startsWith("Playlist/")
}

fun RouteImport(type: String): String {
    return "Import/${type}"
}

fun RouteMusicPlayer(): String {
    return "MusicPlayer"
}

fun RouteLog(): String {
    return "Debug/Log"
}

fun RouteDebugMore(): String {
    return "Debug/More"
}

fun RouteThemeSettings(): String {
    return "Settings/Theme"
}

val LocalNavController = compositionLocalOf<NavHostController> {
    error("No LocalNavController provided")
}

@Composable
fun RoutesProvider(
    block: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalNavController provides rememberNavController()) {
        block()
    }
}
