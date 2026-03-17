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
    val downloadDirectoryState = downloadRepository.downloadDirectoryState

    fun pause(id: UUID) {
        viewModelScope.launch {
            downloadRepository.pause(id)
        }
    }

    fun start(id: UUID) {
        viewModelScope.launch {
            downloadRepository.start(id)
        }
    }

    fun delete(id: UUID, deleteFile: Boolean) {
        viewModelScope.launch {
            downloadRepository.delete(id, deleteFile)
        }
    }

    fun setDownloadDirectory(uri: String) {
        downloadRepository.setDownloadDirectory(uri)
    }

    fun resetDownloadDirectory() {
        downloadRepository.resetDownloadDirectory()
    }
}
