package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.ViewModel
import com.kutedev.easemusicplayer.singleton.LrcApiRepository
import com.kutedev.easemusicplayer.singleton.LrcApiSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LrcApiVM @Inject constructor(
    private val repository: LrcApiRepository,
    private val settingsRepository: LrcApiSettingsRepository,
) : ViewModel() {
    val currentLyricsState = repository.currentLyricsState
    val settings = settingsRepository.settings

    fun retryCurrentMusic() {
        repository.retryCurrentMusic(showFailureToast = true)
    }
}
