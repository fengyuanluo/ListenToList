package com.kutedev.easemusicplayer.singleton

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kutedev.easemusicplayer.core.DataSourceKeyH
import javax.inject.Inject
import javax.inject.Singleton
import uniffi.ease_client_backend.ctGetAsset
import uniffi.ease_client_backend.easeError
import uniffi.ease_client_schema.DataSourceKey

@Singleton
class AssetRepository @Inject constructor(private val bridge: Bridge) {
    companion object {
        private const val MAX_BUF_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAX_BITMAP_CACHE_BYTES = 24 * 1024 * 1024
    }

    private val bufCache = object : LruCache<DataSourceKeyH, ByteArray>(MAX_BUF_CACHE_BYTES) {
        override fun sizeOf(key: DataSourceKeyH, value: ByteArray): Int {
            return value.size
        }
    }
    private val bitmapCache = object : LruCache<DataSourceKeyH, ImageBitmap>(MAX_BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: DataSourceKeyH, value: ImageBitmap): Int {
            return value.width * value.height * 4
        }
    }

    suspend fun load(key: DataSourceKey): ByteArray? {
        val keyH = DataSourceKeyH(key)
        bufCache.get(keyH)?.let {
            return it
        }

        return try {
            val buf = bridge.run { ctGetAsset(it, key) }
            if (buf != null) {
                bufCache.put(keyH, buf)
            }
            buf
        } catch (e: Exception) {
            easeError(e.toString())
            null
        }
    }

    suspend fun loadBitmap(key: DataSourceKey): ImageBitmap? {
        val keyH = DataSourceKeyH(key)
        bitmapCache.get(keyH)?.let {
            return it
        }

        val buf = load(key) ?: return null
        val bm = BitmapFactory.decodeByteArray(buf, 0, buf.size) ?: return null
        val bitmap = bm.asImageBitmap()
        bitmapCache.put(keyH, bitmap)
        return bitmap
    }

    fun get(key: DataSourceKey): ByteArray? {
        return bufCache.get(DataSourceKeyH(key))
    }

    fun getBitmap(key: DataSourceKey): ImageBitmap? {
        return bitmapCache.get(DataSourceKeyH(key))
    }
}
