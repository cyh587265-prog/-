package com.dsh.mobile.ui
import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
private val Context.dataStore by preferencesDataStore("settings")
/**
 * 服务器地址管理 ViewModel：多地址存储 + 活跃地址 + 旧版迁移。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore: DataStore<Preferences>
        get() = getApplication<Application>().dataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val saveMutex = Mutex()

    private val _urls = MutableStateFlow<List<String>>(emptyList())
    val urls: StateFlow<List<String>> = _urls.asStateFlow()

    private val _activeUrl = MutableStateFlow<String?>(null)
    val activeUrl: StateFlow<String?> = _activeUrl.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = dataStore.data.first()
            val oldBaseUrl = prefs[stringPreferencesKey("server_base_url")]
            val urlsJson = prefs[stringPreferencesKey("server_urls")]
            var urlList: List<String> = emptyList()
            if (!urlsJson.isNullOrEmpty()) {
                try {
                    urlList = json.decodeFromString(ListSerializer(String.serializer()), urlsJson)
                } catch (e: Exception) {
                    // 解析失败则忽略
                }
            }
            // 旧版迁移
            if (urlList.isEmpty() && !oldBaseUrl.isNullOrEmpty()) {
                urlList = listOf(oldBaseUrl)
                saveUrls(urlList)
                // 迁移成功后清理旧 key，避免旧代码读到过期值
                dataStore.edit { it.remove(stringPreferencesKey("server_base_url")) }
            }
            _urls.value = urlList
            // 优先恢复上次选中的活跃地址
            val savedActive = prefs[stringPreferencesKey("server_active_url")]
            _activeUrl.value = if (savedActive != null && savedActive in urlList) savedActive else urlList.firstOrNull()
        }
    }

    fun addUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val current = _urls.value.toMutableList()
        if (current.contains(trimmed)) return false
        current.add(trimmed)
        _urls.value = current
        if (_activeUrl.value == null) _activeUrl.value = trimmed
        viewModelScope.launch { saveUrls(current) }
        return true
    }

    fun removeUrl(url: String): Boolean {
        val current = _urls.value.toMutableList()
        if (!current.remove(url)) return false
        _urls.value = current
        if (_activeUrl.value == url) _activeUrl.value = current.firstOrNull()
        viewModelScope.launch { saveUrls(current) }
        return true
    }

    fun setActiveUrl(url: String): Boolean {
        if (!_urls.value.contains(url)) return false
        _activeUrl.value = url
        // 持久化活跃地址，重启后恢复
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[stringPreferencesKey("server_active_url")] = url }
        }
        return true
    }

    /**
     * 等待活跃地址就绪（挂起直到非空，或超时返回 null——避免无地址时永久挂起）。
     */
    suspend fun awaitActiveUrl(timeoutMs: Long = 8000): String? =
        withTimeoutOrNull(timeoutMs) { _activeUrl.filter { it != null }.first() }

    private suspend fun saveUrls(urls: List<String>) = saveMutex.withLock {
        val urlsJson = json.encodeToString(ListSerializer(String.serializer()), urls)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("server_urls")] = urlsJson
        }
    }
}
