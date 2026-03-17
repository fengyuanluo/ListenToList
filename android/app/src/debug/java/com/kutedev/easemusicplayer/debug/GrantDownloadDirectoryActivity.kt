package com.kutedev.easemusicplayer.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val GRANT_DOWNLOAD_DIRECTORY_LOG_PREFIX = "GRANT_DOWNLOAD_DIRECTORY"

class GrantDownloadDirectoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent?.dataString
        val result = runCatching {
            val uri = uriString?.toUri() ?: error("missing data uri")
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            EntryPointAccessors.fromApplication(
                applicationContext,
                GrantDownloadDirectoryEntryPoint::class.java,
            ).downloadRepository().setDownloadDirectory(uri.toString())
            "ok uri=$uriString"
        }.getOrElse { error ->
            "error uri=$uriString message=${error.message ?: error}"
        }
        Log.i("GrantDownloadDir", "$GRANT_DOWNLOAD_DIRECTORY_LOG_PREFIX $result")
        finish()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GrantDownloadDirectoryEntryPoint {
    fun downloadRepository(): DownloadRepository
}
