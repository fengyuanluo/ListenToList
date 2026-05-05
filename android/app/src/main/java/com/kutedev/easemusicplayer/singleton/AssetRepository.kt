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

internal data class BitmapRequestSize(
    val maxWidthPx: Int?,
    val maxHeightPx: Int?,
)

private data class BitmapCacheKey(
    val source: DataSourceKeyH,
    val requestSize: BitmapRequestSize,
)

internal fun calculateBitmapSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxWidthPx: Int?,
    maxHeightPx: Int?,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return 1
    }

    val targetWidth = maxWidthPx?.takeIf { it > 0 }
    val targetHeight = maxHeightPx?.takeIf { it > 0 }
    if (targetWidth == null && targetHeight == null) {
        return 1
    }

    var sampleSize = 1
    while (true) {
        val nextSampleSize = sampleSize * 2
        val nextWidth = sourceWidth / nextSampleSize
        val nextHeight = sourceHeight / nextSampleSize
        val widthStillUseful = targetWidth == null || nextWidth >= targetWidth
        val heightStillUseful = targetHeight == null || nextHeight >= targetHeight
        if (!widthStillUseful || !heightStillUseful) {
            return sampleSize
        }
        sampleSize = nextSampleSize
    }
}

private fun decodeSampledBitmap(buf: ByteArray, requestSize: BitmapRequestSize): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(buf, 0, buf.size, bounds)
    val sampleSize = calculateBitmapSampleSize(
        sourceWidth = bounds.outWidth,
        sourceHeight = bounds.outHeight,
        maxWidthPx = requestSize.maxWidthPx,
        maxHeightPx = requestSize.maxHeightPx,
    )
    val bitmap = BitmapFactory.decodeByteArray(
        buf,
        0,
        buf.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        },
    ) ?: return null
    return bitmap.asImageBitmap()
}

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
    private val bitmapCache = object : LruCache<BitmapCacheKey, ImageBitmap>(MAX_BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: BitmapCacheKey, value: ImageBitmap): Int {
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

    suspend fun loadBitmap(
        key: DataSourceKey,
        maxWidthPx: Int? = null,
        maxHeightPx: Int? = null,
    ): ImageBitmap? {
        val cacheKey = BitmapCacheKey(
            source = DataSourceKeyH(key),
            requestSize = BitmapRequestSize(maxWidthPx, maxHeightPx),
        )
        bitmapCache.get(cacheKey)?.let {
            return it
        }

        val buf = load(key) ?: return null
        val bitmap = decodeSampledBitmap(buf, cacheKey.requestSize) ?: return null
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun get(key: DataSourceKey): ByteArray? {
        return bufCache.get(DataSourceKeyH(key))
    }

    fun getBitmap(
        key: DataSourceKey,
        maxWidthPx: Int? = null,
        maxHeightPx: Int? = null,
    ): ImageBitmap? {
        return bitmapCache.get(
            BitmapCacheKey(
                source = DataSourceKeyH(key),
                requestSize = BitmapRequestSize(maxWidthPx, maxHeightPx),
            )
        )
    }
}
