package com.dsh.mobile.ui
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.net.RpcClient
import com.dsh.mobile.net.SessionPage
import com.dsh.mobile.net.SessionRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
data class SessionsUiState(
    val sessions: List<SessionRow> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null
)
class SessionListViewModel(
    private val workspaceId: String,
    private val settingsViewModel: SettingsViewModel
) : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()
    val baseUrl = settingsViewModel.baseUrl
    fun loadFirstPage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                sessions = emptyList(),
                hasMore = false,
                nextCursor = null
            )
            try {
                val result = RpcClient().call(
                    method = "session.list",
                    payload = buildJsonObject { },
                    baseUrl = baseUrl.value
                )
                val page = Json.decodeFromString<SessionPage>(result.toString())
                _uiState.value = _uiState.value.copy(
                    sessions = page.items,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("SessionListViewModel", "loadFirstPage error", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载会话失败"
                )
            }
        }
    }
    fun loadNextPage() {
        val currentState = _uiState.value
        if (!currentState.hasMore || currentState.isLoadingMore || currentState.isLoading) {
            return
        }
        viewModelScope.launch {
            _uiState.value = currentState.copy(isLoadingMore = true)
            try {
                val result = RpcClient().call(
                    method = "session.list",
                    payload = buildJsonObject {
                        currentState.nextCursor?.let { put("cursor", it) }
                    },
                    baseUrl = baseUrl.value
                )
                val page = Json.decodeFromString<SessionPage>(result.toString())
                _uiState.value = _uiState.value.copy(
                    sessions = _uiState.value.sessions + page.items,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor,
                    isLoadingMore = false,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("SessionListViewModel", "loadNextPage error", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    error = e.message ?: "加载更多失败"
                )
            }
        }
    }
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                val result = RpcClient().call(
                    method = "session.list",
                    payload = buildJsonObject { },
                    baseUrl = baseUrl.value
                )
                val page = Json.decodeFromString<SessionPage>(result.toString())
                _uiState.value = _uiState.value.copy(
                    sessions = page.items,
                    hasMore = page.hasMore,
                    nextCursor = page.nextCursor,
                    isRefreshing = false,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("SessionListViewModel", "refresh error", e)
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message ?: "刷新失败"
                )
            }
        }
    }
    fun createSession(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = RpcClient().call(
                    method = "session.create",
                    payload = buildJsonObject {
                        put("workspaceId", workspaceId)
                    },
                    baseUrl = baseUrl.value
                )
                val session = Json.decodeFromString<SessionRow>(result.toString())
                // 重新加载列表以显示新会话
                loadFirstPage()
                onSuccess(session.id)
            } catch (e: Exception) {
                Log.e("SessionListViewModel", "createSession error", e)
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "创建会话失败"
                )
            }
        }
    }
    fun renameSession(sessionId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                RpcClient().call(
                    method = "session.rename",
                    payload = buildJsonObject {
                        put("sessionId", sessionId)
                        put("name", newName)
                    },
                    baseUrl = baseUrl.value
                )
                // 更新本地列表
                val updatedSessions = _uiState.value.sessions.map {
                    if (it.id == sessionId) {
                        it.copy(name = newName)
                    } else {
                        it
                    }
                }
                _uiState.value = _uiState.value.copy(sessions = updatedSessions)
            } catch (e: Exception) {
                Log.e("SessionListViewModel", "renameSession error", e)
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "重命名失败"
                )
            }
        }
    }
    companion object {
        fun provideFactory(workspaceId: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val settingsViewModel = ViewModelProvider.AndroidViewModelFactory.getInstance()
                        .create(SettingsViewModel::class.java) as SettingsViewModel
                    return SessionListViewModel(workspaceId, settingsViewModel) as T
                }
            }
        }
    }
}
