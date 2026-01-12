package com.kutedev.easemusicplayer.widgets.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseIconButton
import com.kutedev.easemusicplayer.components.EaseIconButtonSize
import com.kutedev.easemusicplayer.components.EaseIconButtonType
import com.kutedev.easemusicplayer.viewmodels.EditStorageVM
import com.kutedev.easemusicplayer.viewmodels.StoragesVM
import com.kutedev.easemusicplayer.core.LocalNavController
import com.kutedev.easemusicplayer.core.RouteAddDevices
import com.kutedev.easemusicplayer.core.RouteStorageBrowser
import uniffi.ease_client_backend.Storage
import uniffi.ease_client_schema.StorageType
import java.time.LocalDate

private val paddingX = 24.dp
private val paddingY = 12.dp

private val DailyQuotes = listOf(
    "给每一段旋律一段安静的时间。",
    "慢下来，世界会把细节交给你。",
    "喜欢的歌，值得反复播放。",
    "把心事放进歌里，轻一点。",
    "一首歌的长度，也是一段情绪的长度。",
)

private fun pickDailyQuote(): String {
    val day = LocalDate.now().toEpochDay()
    val index = (day % DailyQuotes.size).toInt().coerceAtLeast(0)
    return DailyQuotes[index]
}

@Composable
private fun Title(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
    )
}


@Composable
private fun ColumnScope.DevicesBlock(
    storageItems: List<Storage>,
    editStoragesVM: EditStorageVM = hiltViewModel()
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .weight(1f)
            .padding(paddingX, paddingY)
    ) {
        if (storageItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        navController.navigate(RouteAddDevices((-1).toString()))
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Icon(
                        modifier = Modifier.size(12.dp),
                        painter = painterResource(id = R.drawable.icon_plus),
                        contentDescription = null
                    )
                    Box(modifier = Modifier.size(4.dp))
                    Text(
                        text = stringResource(id = R.string.dashboard_devices_add),
                        textAlign = TextAlign.Center
                    )
                }
            }
            return
        }
        for (item in storageItems) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        navController.navigate(RouteStorageBrowser(item.id.value.toString()))
                    }
                    .padding(0.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val title = item.alias.ifBlank {
                    item.addr
                }
                val subTitle = item.addr

                Box(modifier = Modifier.height(48.dp))
                Icon(
                    modifier = Modifier.size(32.dp),
                    painter = painterResource(id = R.drawable.icon_cloud),
                    contentDescription = null
                )
                Box(
                    modifier = Modifier
                        .width(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subTitle.isNotBlank()) {
                        Text(
                            text = subTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Small,
                    buttonType = EaseIconButtonType.Default,
                    painter = painterResource(id = R.drawable.icon_setting),
                    onClick = {
                        navController.navigate(RouteAddDevices(item.id.value.toString()))
                    }
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock() {
    val quote = remember { pickDailyQuote() }
    val shape = RoundedCornerShape(16.dp)
    val blockAlpha = 0.9f
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(paddingX, 0.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .alpha(blockAlpha)
            .padding(18.dp, 14.dp)
    ) {
        Text(
            text = quote,
            color = textColor,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun DashboardSubpage(
    storageVM: StoragesVM = hiltViewModel(),
    editStoragesVM: EditStorageVM = hiltViewModel()
) {
    val navController = LocalNavController.current
    val storages by storageVM.storages.collectAsState()
    val storageItems = storages.filter { v -> v.typ != StorageType.LOCAL }

    LaunchedEffect(Unit) {
        storageVM.reload()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(modifier = Modifier.height(24.dp))
        QuoteBlock()
        Box(modifier = Modifier.height(32.dp))
        Row(
            modifier = Modifier
                .padding(paddingX, 4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Title(title = stringResource(id = R.string.dashboard_devices))
            if (storageItems.isNotEmpty()) {
                EaseIconButton(
                    sizeType = EaseIconButtonSize.Small,
                    buttonType = EaseIconButtonType.Primary,
                    painter = painterResource(id = R.drawable.icon_plus),
                    onClick = {
                        navController.navigate(RouteAddDevices((-1).toString()))
                    }
                )
            }
        }
        DevicesBlock(storageItems)
    }
}
