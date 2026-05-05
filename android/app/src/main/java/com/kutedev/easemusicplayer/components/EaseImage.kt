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
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.core.DataSourceKeyH
import com.kutedev.easemusicplayer.viewmodels.AssetVM
import uniffi.ease_client_schema.DataSourceKey

@Composable
fun EaseImage(
    modifier: Modifier = Modifier,
    dataSourceKey: DataSourceKey,
    contentScale: ContentScale,
    vm: AssetVM = hiltViewModel()
) {
    var oldKey: DataSourceKeyH by remember { mutableStateOf(DataSourceKeyH(dataSourceKey)) }
    val key = DataSourceKeyH(dataSourceKey)

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth.takeIf { it != Int.MAX_VALUE && it > 0 }
        val maxHeightPx = constraints.maxHeight.takeIf { it != Int.MAX_VALUE && it > 0 }
        var bitmap: ImageBitmap? by remember(maxWidthPx, maxHeightPx) {
            mutableStateOf(vm.getBitmap(dataSourceKey, maxWidthPx, maxHeightPx))
        }

        LaunchedEffect(key.hashCode(), maxWidthPx, maxHeightPx, bitmap != null) {
            if (key != oldKey || bitmap == null) {
                oldKey = key
                bitmap = vm.loadBitmap(key.value(), maxWidthPx, maxHeightPx)
            }
        }

        val target = bitmap ?: return@BoxWithConstraints

        Image(
            modifier = Modifier.fillMaxSize(),
            bitmap = target,
            contentDescription = null,
            contentScale = contentScale,
        )
    }
}
