package com.kutedev.easemusicplayer.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.kutedev.easemusicplayer.singleton.calculateBitmapSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun ThemeBackgroundImage(
    uri: String?,
    modifier: Modifier = Modifier,
    alpha: Float = 0.2f,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (uri.isNullOrBlank()) {
        return
    }

    val context = LocalContext.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth.takeIf { it != Int.MAX_VALUE && it > 0 }
        val maxHeightPx = constraints.maxHeight.takeIf { it != Int.MAX_VALUE && it > 0 }
        var bitmap by remember(uri, maxWidthPx, maxHeightPx) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(uri, maxWidthPx, maxHeightPx) {
            bitmap = withContext(Dispatchers.IO) {
                val parsedUri = Uri.parse(uri)
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                context.contentResolver.openInputStream(parsedUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: return@withContext null
                val sampleSize = calculateBitmapSampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    maxWidthPx = maxWidthPx,
                    maxHeightPx = maxHeightPx,
                )
                context.contentResolver.openInputStream(parsedUri)?.use {
                    val decoded = BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        },
                    ) ?: return@withContext null
                    decoded.asImageBitmap()
                }
            }
        }

        val target = bitmap ?: return@BoxWithConstraints
        Image(
            modifier = Modifier.fillMaxSize(),
            bitmap = target,
            contentDescription = null,
            contentScale = contentScale,
            alpha = alpha,
        )
    }
}
