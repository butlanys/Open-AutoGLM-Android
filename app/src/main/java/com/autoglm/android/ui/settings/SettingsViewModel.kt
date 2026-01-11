package com.autoglm.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autoglm.android.data.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val modelName: String = "autoglm-phone",
    val maxSteps: Int = 100,
    val language: String = "cn",
    val useLargeModelAppList: Boolean = false,
    val saved: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        apiUrl = settings.apiUrl,
                        apiKey = settings.apiKey,
                        modelName = settings.modelName,
                        maxSteps = settings.maxSteps,
                        language = settings.language,
                        useLargeModelAppList = settings.useLargeModelAppList
                    )
                }
            }
        }
    }
    
    fun updateApiUrl(url: String) {
        _uiState.update { it.copy(apiUrl = url) }
    }
    
    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }
    
    fun updateModelName(name: String) {
        _uiState.update { it.copy(modelName = name) }
    }
    
    fun updateMaxSteps(steps: Int) {
        _uiState.update { it.copy(maxSteps = steps) }
    }
    
    fun updateLanguage(lang: String) {
        _uiState.update { it.copy(language = lang) }
    }
    
    fun updateUseLargeModelAppList(use: Boolean) {
        _uiState.update { it.copy(useLargeModelAppList = use) }
    }
    
    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.setApiUrl(state.apiUrl)
            settingsRepository.setApiKey(state.apiKey)
            settingsRepository.setModelName(state.modelName)
            settingsRepository.setMaxSteps(state.maxSteps)
            settingsRepository.setLanguage(state.language)
            settingsRepository.setUseLargeModelAppList(state.useLargeModelAppList)
            _uiState.update { it.copy(saved = true) }
        }
    }
    
    fun resetSaved() {
        _uiState.update { it.copy(saved = false) }
    }
    
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as Application
                SettingsViewModel(application)
            }
        }
    }
}
