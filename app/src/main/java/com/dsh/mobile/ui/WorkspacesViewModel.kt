package com.dsh.mobile.ui
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.net.RpcClient
import com.dsh.mobile.net.WorkspaceRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
data class WorkspacesUiState(
    val workspaces: List<WorkspaceRow> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
class WorkspacesViewModel(
    private val settingsViewModel: SettingsViewModel
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val _uiState = MutableStateFlow(WorkspacesUiState())
    val uiState: StateFlow<WorkspacesUiState> = _uiState.asStateFlow()
    val baseUrl = settingsViewModel.baseUrl
    fun loadWorkspaces() {
        viewModelScope.launch {
            // 等待服务器地址就绪（SettingsViewModel 异步从 DataStore 加载）
            val readyUrl = settingsViewModel.baseUrl.filter { it.isNotBlank() }.first()
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = RpcClient().call(
                    method = "workspace.list",
                    payload = buildJsonObject { },
                    baseUrl = readyUrl
                )
                // workspace.list returns { items: [...] } — never a bare array
                val itemsJson = result["items"]?.jsonArray ?: throw Exception("workspace.list: missing items")
                val items = itemsJson.map { item ->
                    json.decodeFromString(WorkspaceRow.serializer(), item.toString())
                }
                _uiState.value = _uiState.value.copy(
                    workspaces = items,
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                Log.e("WorkspacesViewModel", "loadWorkspaces error", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载工作区失败"
                )
            }
        }
    }
    fun refresh() {
        loadWorkspaces()
    }
}
