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
