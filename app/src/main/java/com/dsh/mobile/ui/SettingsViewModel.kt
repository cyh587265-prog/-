package com.dsh.mobile.ui
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
private val Context.dataStore by preferencesDataStore("settings")
class SettingsViewModel : ViewModel() {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl
    init {
        // Load from DataStore
        runBlocking {
            val prefs = dataStore.data.first()
            _baseUrl.value = prefs[stringPreferencesKey("server_base_url")] ?: ""
        }
    }
    fun setBaseUrl(url: String) {
        _baseUrl.value = url
        runBlocking {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("server_base_url")] = url
            }
        }
    }
}
