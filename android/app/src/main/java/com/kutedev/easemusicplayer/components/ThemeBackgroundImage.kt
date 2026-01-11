package com.kutedev.easemusicplayer.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return@withContext null
            input.use {
                val decoded = BitmapFactory.decodeStream(it) ?: return@withContext null
                decoded.asImageBitmap()
            }
        }
    }

    val target = bitmap ?: return
    Image(
        modifier = modifier,
        bitmap = target,
        contentDescription = null,
        contentScale = contentScale,
        alpha = alpha,
    )
}
