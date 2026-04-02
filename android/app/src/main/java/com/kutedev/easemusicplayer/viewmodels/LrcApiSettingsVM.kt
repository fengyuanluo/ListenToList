package com.kutedev.easemusicplayer.viewmodels

import androidx.lifecycle.ViewModel
import com.kutedev.easemusicplayer.singleton.LrcApiSettings
import com.kutedev.easemusicplayer.singleton.LrcApiSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LrcApiSettingsVM @Inject constructor(
    private val repository: LrcApiSettingsRepository,
) : ViewModel() {
    val settings = repository.settings

    fun save(settings: LrcApiSettings) {
        repository.save(settings)
    }

    fun setEnabled(enabled: Boolean) {
        repository.setEnabled(enabled)
    }

    fun setBaseUrl(baseUrl: String) {
        repository.setBaseUrl(baseUrl)
    }

    fun setAuthKey(authKey: String) {
        repository.setAuthKey(authKey)
    }

    fun reset() {
        repository.reset()
    }
}
