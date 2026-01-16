package com.autoglm.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val API_URL = stringPreferencesKey("api_url")
        private val API_KEY = stringPreferencesKey("api_key")
        private val MODEL_NAME = stringPreferencesKey("model_name")
        private val MAX_STEPS = intPreferencesKey("max_steps")
        private val LANGUAGE = stringPreferencesKey("language")
        private val USE_LARGE_MODEL_APP_LIST = booleanPreferencesKey("use_large_model_app_list")
        
        // Orchestrator advanced model settings
        private val ORCH_USE_ADVANCED_MODEL = booleanPreferencesKey("orch_use_advanced_model")
        private val ORCH_ADVANCED_MODEL_URL = stringPreferencesKey("orch_advanced_model_url")
        private val ORCH_ADVANCED_MODEL_KEY = stringPreferencesKey("orch_advanced_model_key")
        private val ORCH_ADVANCED_MODEL_NAME = stringPreferencesKey("orch_advanced_model_name")
        private val ORCH_MAX_CONCURRENT = intPreferencesKey("orch_max_concurrent")
        private val ORCH_ENABLE_VIRTUAL_DISPLAYS = booleanPreferencesKey("orch_enable_virtual_displays")
        private val ORCH_AUTO_DECIDE_MULTI_TASK = booleanPreferencesKey("orch_auto_decide_multi_task")
    }
    
    val apiUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[API_URL] ?: "https://open.bigmodel.cn/api/paas/v4"
    }
    
    val apiKey: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[API_KEY] ?: ""
    }
    
    val modelName: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[MODEL_NAME] ?: "autoglm-phone"
    }
    
    val maxSteps: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MAX_STEPS] ?: 100
    }
    
    val language: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE] ?: "cn"
    }
    
    val useLargeModelAppList: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USE_LARGE_MODEL_APP_LIST] ?: false
    }
    
    suspend fun setApiUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[API_URL] = url
        }
    }
    
    suspend fun setApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = key
        }
    }
    
    suspend fun setModelName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_NAME] = name
        }
    }
    
    suspend fun setMaxSteps(steps: Int) {
        context.dataStore.edit { preferences ->
            preferences[MAX_STEPS] = steps
        }
    }
    
    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE] = lang
        }
    }
    
    suspend fun setUseLargeModelAppList(use: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USE_LARGE_MODEL_APP_LIST] = use
        }
    }
    
    // Orchestrator settings
    data class OrchestratorSettings(
        val useAdvancedModel: Boolean = true,
        val advancedModelUrl: String = "",
        val advancedModelApiKey: String = "",
        val advancedModelName: String = "",
        val maxConcurrentTasks: Int = 3,
        val enableVirtualDisplays: Boolean = true,
        val autoDecideMultiTask: Boolean = true
    )
    
    val orchestratorSettings: Flow<OrchestratorSettings> = context.dataStore.data.map { preferences ->
        OrchestratorSettings(
            useAdvancedModel = preferences[ORCH_USE_ADVANCED_MODEL] ?: true,
            advancedModelUrl = preferences[ORCH_ADVANCED_MODEL_URL] ?: "",
            advancedModelApiKey = preferences[ORCH_ADVANCED_MODEL_KEY] ?: "",
            advancedModelName = preferences[ORCH_ADVANCED_MODEL_NAME] ?: "",
            maxConcurrentTasks = preferences[ORCH_MAX_CONCURRENT] ?: 3,
            enableVirtualDisplays = preferences[ORCH_ENABLE_VIRTUAL_DISPLAYS] ?: true,
            autoDecideMultiTask = preferences[ORCH_AUTO_DECIDE_MULTI_TASK] ?: true
        )
    }
    
    suspend fun saveOrchestratorSettings(settings: OrchestratorSettings) {
        context.dataStore.edit { preferences ->
            preferences[ORCH_USE_ADVANCED_MODEL] = settings.useAdvancedModel
            preferences[ORCH_ADVANCED_MODEL_URL] = settings.advancedModelUrl
            preferences[ORCH_ADVANCED_MODEL_KEY] = settings.advancedModelApiKey
            preferences[ORCH_ADVANCED_MODEL_NAME] = settings.advancedModelName
            preferences[ORCH_MAX_CONCURRENT] = settings.maxConcurrentTasks
            preferences[ORCH_ENABLE_VIRTUAL_DISPLAYS] = settings.enableVirtualDisplays
            preferences[ORCH_AUTO_DECIDE_MULTI_TASK] = settings.autoDecideMultiTask
        }
    }
    
    data class Settings(
        val apiUrl: String = "https://open.bigmodel.cn/api/paas/v4",
        val apiKey: String = "",
        val modelName: String = "autoglm-phone",
        val maxSteps: Int = 100,
        val language: String = "cn",
        val useLargeModelAppList: Boolean = false
    )
    
    val settings: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            apiUrl = preferences[API_URL] ?: "https://open.bigmodel.cn/api/paas/v4",
            apiKey = preferences[API_KEY] ?: "",
            modelName = preferences[MODEL_NAME] ?: "autoglm-phone",
            maxSteps = preferences[MAX_STEPS] ?: 100,
            language = preferences[LANGUAGE] ?: "cn",
            useLargeModelAppList = preferences[USE_LARGE_MODEL_APP_LIST] ?: false
        )
    }
}
