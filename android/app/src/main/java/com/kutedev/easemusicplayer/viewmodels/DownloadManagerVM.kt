package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutedev.easemusicplayer.singleton.DownloadTaskItem
import com.kutedev.easemusicplayer.singleton.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadManagerVM @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    val tasks = downloadRepository.tasks

    fun cancel(id: UUID) {
        viewModelScope.launch {
            downloadRepository.cancel(id)
        }
    }

    fun retry(task: DownloadTaskItem) {
        viewModelScope.launch {
            downloadRepository.retry(task)
        }
    }

    fun downloadDirectory(): String {
        return downloadRepository.downloadDirectory()
    }
}
