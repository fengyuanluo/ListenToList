package com.kutedev.easemusicplayer.widgets.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.kutedev.easemusicplayer.R
import com.kutedev.easemusicplayer.components.EaseTextButton
import com.kutedev.easemusicplayer.components.EaseTextButtonSize
import com.kutedev.easemusicplayer.components.EaseTextButtonType
import com.kutedev.easemusicplayer.viewmodels.LogVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import uniffi.ease_client_backend.ListLogFile

private val paddingX = SettingPaddingX

@Composable
fun LogPage(
    logVM: LogVM = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by logVM.logs.collectAsState()
    var previewLog by remember { mutableStateOf<ListLogFile?>(null) }
    var previewContent by remember { mutableStateOf("") }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewTruncated by remember { mutableStateOf(false) }
    val logShareTitle = stringResource(id = R.string.log_action_share)
    val logCopyDone = stringResource(id = R.string.log_copy_done)
    val logPreviewFailed = stringResource(id = R.string.log_preview_failed)
    val logPreviewEmpty = stringResource(id = R.string.log_preview_empty)

    fun logUri(log: ListLogFile) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(log.path)
    )

    fun openLog(log: ListLogFile) {
        val uri = logUri(log)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun shareLog(log: ListLogFile) {
        val uri = logUri(log)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, logShareTitle))
    }

    fun copyLogPath(log: ListLogFile) {
        clipboardManager.setText(AnnotatedString(log.path))
        Toast.makeText(context, logCopyDone, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        logVM.reload()
    }

    LaunchedEffect(previewLog?.path) {
        val log = previewLog ?: return@LaunchedEffect
        previewError = null
        previewContent = ""
        previewTruncated = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(log.path)
                if (!file.exists()) {
                    return@runCatching Pair("", false)
                }
                file.bufferedReader().useLines { lines ->
                    val collected = lines.take(201).toList()
                    val isTruncated = collected.size > 200
                    val contentLines = if (isTruncated) collected.dropLast(1) else collected
                    Pair(contentLines.joinToString("\n"), isTruncated)
                }
            }.getOrNull()
        }
        if (result == null) {
            previewError = logPreviewFailed
            return@LaunchedEffect
        }
        previewContent = result.first
        previewTruncated = result.second
        if (previewContent.isBlank()) {
            previewError = logPreviewEmpty
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column {
            Text(
                modifier = Modifier.padding(start = paddingX, end = paddingX, top = 24.dp, bottom = 4.dp),
                text = stringResource(id = R.string.log_title),
                fontSize = 32.sp,
            )
            Text(
                modifier = Modifier.padding(horizontal = paddingX),
                text = pluralStringResource(id = R.plurals.log_desc, count = logs.size, logs.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Box(modifier = Modifier.height(24.dp))
            LazyColumn(
                modifier = Modifier.weight(1.0f)
            ) {
                items(logs) { log ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                previewLog = log
                            }
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = paddingX, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = log.name,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = log.path,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.height(24.dp))
        }
    }

    if (previewLog != null) {
        Dialog(onDismissRequest = { previewLog = null }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp, 20.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.log_preview_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = previewLog?.name.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    text = previewLog?.path.orEmpty(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.height(12.dp))
                if (previewError != null) {
                    Text(
                        text = previewError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                } else {
                    Text(
                        text = previewContent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    if (previewTruncated) {
                        Box(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(id = R.string.log_preview_truncated),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row {
                        EaseTextButton(
                            text = stringResource(id = R.string.log_action_copy),
                            type = EaseTextButtonType.Default,
                            size = EaseTextButtonSize.Medium,
                            onClick = { previewLog?.let { copyLogPath(it) } }
                        )
                        EaseTextButton(
                            text = stringResource(id = R.string.log_action_share),
                            type = EaseTextButtonType.Default,
                            size = EaseTextButtonSize.Medium,
                            onClick = { previewLog?.let { shareLog(it) } }
                        )
                        EaseTextButton(
                            text = stringResource(id = R.string.log_action_open),
                            type = EaseTextButtonType.Default,
                            size = EaseTextButtonSize.Medium,
                            onClick = { previewLog?.let { openLog(it) } }
                        )
                    }
                    EaseTextButton(
                        text = stringResource(id = R.string.log_action_close),
                        type = EaseTextButtonType.Primary,
                        size = EaseTextButtonSize.Medium,
                        onClick = { previewLog = null }
                    )
                }
            }
        }
    }
}
