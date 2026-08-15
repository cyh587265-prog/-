package com.dsh.mobile.ui
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.net.MuxFallbackPoller
import com.dsh.mobile.net.RpcClient
import com.dsh.mobile.net.WireMessage
import com.dsh.mobile.ui.SettingsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import java.util.UUID
class ChatViewModel(
    private val workspaceId: String,
    private val sessionId: String,
    private val settingsViewModel: SettingsViewModel,
    private val rpcClient: RpcClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var currentMinSeq: Int? = null
    private val maxMessages = 500
    private val HISTORY_PAGE_SIZE = 30
    private var pendingMessageId: String? = null
    private var sendTimeoutJob: Job? = null
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    init {
        loadHistory()
        startMessageStream()
    }
    private fun startMessageStream() {
        viewModelScope.launch {
            val baseUrl = settingsViewModel.baseUrl.value
            if (baseUrl.isBlank()) return@launch
            // 统一消息入口：Poller 内部 SSE 优先 + 静默检测切轮询，输出完整 WireMessage
            // 关键：消息经 Flow 发射，必须在 collect 里消费（onEvent 参数 Poller 不调用）
            MuxFallbackPoller(rpcClient, baseUrl)
                .observe(sessionId) { /* onEvent unused; flow is the channel */ }
                .catch { e -> Log.e("ChatViewModel", "Message stream error", e) }
                .collect { wireMessage -> handleWireMessage(wireMessage) }
        }
    }
    private fun handleWireMessage(wireMessage: WireMessage) {
        // 只有 assistant 完整消息到达才取消发送超时
        if (wireMessage.kind == "assistant") sendTimeoutJob?.cancel()
        // 按 seq 去重
        val existingSeq = _uiState.value.messages.any { it.seq == wireMessage.seq }
        if (existingSeq) return
        // 从 WireMessage 解析内容
        var text = ""
        var reasoning = ""
        wireMessage.content.forEach { block ->
            when (block.type) {
                "text" -> text += block.text
                "reasoning" -> reasoning += block.text
            }
        }
        val kind = when (wireMessage.kind) {
            "user" -> MessageKind.User
            else -> MessageKind.Assistant
        }
        val message = ChatMessageUi(
            id = wireMessage.id,
            text = text,
            reasoning = reasoning,
            kind = kind,
            isPending = false,
            seq = wireMessage.seq
        )
        _uiState.update { state ->
            state.copy(messages = state.messages + message, isSending = false)
        }
        limitMessages()
    }
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val userMessage = ChatMessageUi(
            id = "user_${System.currentTimeMillis()}",
            text = text,
            reasoning = "",
            kind = MessageKind.User
        )
        addMessage(userMessage)
        _uiState.update { it.copy(inputText = "", isSending = true) }
        viewModelScope.launch {
            try {
                val baseUrl = settingsViewModel.baseUrl.value
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                    put("mode", "queue")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                }
                rpcClient.call("session.prompt", payload, baseUrl)
                Log.d("ChatViewModel", "Message sent")
                // 超时兜底：30 秒无消息回推则解除发送中状态；收到消息时取消此 job
                sendTimeoutJob?.cancel()
                sendTimeoutJob = viewModelScope.launch {
                    delay(30_000)
                    _uiState.update { it.copy(isSending = false) }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send message", e)
                _uiState.update { 
                    it.copy(
                        isSending = false,
                        error = "发送失败: ${e.message}"
                    )
                }
                pendingMessageId?.let { id ->
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.filter { it.id != id },
                            isSending = false
                        )
                    }
                    pendingMessageId = null
                }
            }
        }
    }
    fun loadHistory() {
        if (_uiState.value.isLoadingHistory) return
        _uiState.update { it.copy(isLoadingHistory = true) }
        viewModelScope.launch {
            try {
                val baseUrl = settingsViewModel.baseUrl.value
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                    put("maxMessages", 30)
                    currentMinSeq?.let { put("beforeSeq", it) }
                }
                val result = rpcClient.call("session.history", payload, baseUrl)
                // session.history value: { events: [{ event: {type,seq,time,data}, view? }], hasMore, projections? }
                // NOTE: server ignores maxMessages and returns ALL events (chunk/tool noise included).
                // Parse only complete messages, keep the most recent PAGE_SIZE.
                val events = result["events"]?.jsonArray ?: emptyList()
                val parsed = events.mapNotNull { entry ->
                    val eventObj = entry.jsonObject["event"]?.jsonObject
                        ?: entry.jsonObject // 容错：兼容直接是 event 的情况
                    parseWireMessageFromJson(eventObj)
                }.sortedBy { it.seq }
                val messages = if (parsed.size > HISTORY_PAGE_SIZE) {
                    parsed.takeLast(HISTORY_PAGE_SIZE)
                } else {
                    parsed
                }
                if (messages.isNotEmpty()) {
                    currentMinSeq = messages.first().seq
                }
                _uiState.update { state ->
                    val existingIds = state.messages.map { it.id }.toSet()
                    val newMessages = messages.filter { it.id !in existingIds }
                    state.copy(
                        messages = newMessages + state.messages,
                        isLoadingHistory = false,
                        hasMoreHistory = false
                    )
                }
                limitMessages()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load history", e)
                _uiState.update { 
                    it.copy(
                        isLoadingHistory = false,
                        error = "加载历史失败: ${e.message}"
                    )
                }
            }
        }
    }
    fun loadOlder() {
        if (!_uiState.value.hasMoreHistory || _uiState.value.isLoadingHistory) return
        loadHistory()
    }
    fun loadModels() {
        if (_uiState.value.isLoadingModels) return
        _uiState.update { it.copy(isLoadingModels = true, showModelDialog = true) }
        viewModelScope.launch {
            try {
                val baseUrl = settingsViewModel.baseUrl.value
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                }
                val result = rpcClient.call("session.models", payload, baseUrl)
                // 解析当前模型
                val current = result["current"]?.jsonObject
                val currentModel = current?.get("model")?.jsonPrimitive?.content
                    ?: current?.get("name")?.jsonPrimitive?.content
                // 解析模型列表：group = {id, name, models:[{id,name,reasoning:{defaultEffort,...}}]}
                val groups = result["groups"]?.jsonArray ?: emptyList()
                val modelsMap = mutableMapOf<String, MutableList<ModelItem>>()
                groups.forEach { group ->
                    val groupObj = group.jsonObject
                    val providerId = groupObj["id"]?.jsonPrimitive?.content ?: "unknown"
                    val groupName = groupObj["name"]?.jsonPrimitive?.content ?: providerId
                    val modelList = groupObj["models"]?.jsonArray ?: return@forEach
                    val items = modelList.mapNotNull { model ->
                        val modelObj = model.jsonObject
                        val id = modelObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = modelObj["name"]?.jsonPrimitive?.content ?: id
                        val effort = modelObj["reasoning"]?.jsonObject
                            ?.get("defaultEffort")?.jsonPrimitive?.content
                        ModelItem(id, name, providerId, effort)
                    }
                    modelsMap.getOrPut(groupName) { mutableListOf() }.addAll(items)
                }
                _uiState.update { state ->
                    state.copy(
                        currentModel = currentModel,
                        models = modelsMap,
                        isLoadingModels = false,
                        showModelDialog = true
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load models", e)
                _uiState.update { 
                    it.copy(
                        isLoadingModels = false,
                        error = "加载模型失败: ${e.message}"
                    )
                }
            }
        }
    }
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        viewModelScope.launch {
            try {
                val baseUrl = settingsViewModel.baseUrl.value
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                    put("provider", provider)
                    put("model", model)
                    reasoningEffort?.let { put("reasoningEffort", it) }
                }
                rpcClient.call("session.selectModel", payload, baseUrl)
                _uiState.update { 
                    it.copy(
                        currentModel = model,
                        showModelDialog = false
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to select model", e)
                _uiState.update { it.copy(error = "选择模型失败: ${e.message}") }
            }
        }
    }
    private fun parseWireMessageFromJson(eventObj: JsonObject): ChatMessageUi? {
        try {
            // WireEvent: { type, seq, time, data }
            val typeName = eventObj["type"]?.jsonPrimitive?.content ?: return null
            val seq = eventObj["seq"]?.jsonPrimitive?.int ?: return null
            val data = eventObj["data"]?.jsonObject ?: return null
            // Only complete messages: user/message content is data.content,
            // assistant/message content is data.message.content (asymmetric!)
            val isUser = typeName == "user/message"
            val isAssistant = typeName == "assistant/message"
            if (!isUser && !isAssistant) return null
            val content = if (isUser) {
                data["content"]?.jsonArray
            } else {
                data["message"]?.jsonObject?.get("content")?.jsonArray
            } ?: return null
            var text = ""
            var reasoning = ""
            content.forEach { block ->
                val blockObj = block.jsonObject
                val type = blockObj["type"]?.jsonPrimitive?.content ?: ""
                val blockText = blockObj["text"]?.jsonPrimitive?.content ?: ""
                when (type) {
                    "text" -> text += blockText
                    "reasoning" -> reasoning += blockText
                }
            }
            val id = data["id"]?.jsonPrimitive?.content
                ?: data["message"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: "msg-$seq"
            return ChatMessageUi(
                id = id,
                text = text,
                reasoning = reasoning,
                kind = if (isUser) MessageKind.User else MessageKind.Assistant,
                isPending = false,
                seq = seq
            )
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to parse message", e)
            return null
        }
    }
    private fun addMessage(message: ChatMessageUi) {
        _uiState.update { state ->
            val newMessages = state.messages + message
            state.copy(messages = newMessages)
        }
        limitMessages()
    }
    private fun limitMessages() {
        _uiState.update { state ->
            if (state.messages.size > maxMessages) {
                state.copy(messages = state.messages.takeLast(maxMessages))
            } else {
                state
            }
        }
    }
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }
    fun dismissModelDialog() {
        _uiState.update { it.copy(showModelDialog = false, isLoadingModels = false) }
    }
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    companion object {
        fun factory(workspaceId: String, sessionId: String): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras
                ): T {
                    if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                            ?: throw IllegalStateException("Application not available in extras")
                        val settingsViewModel = SettingsViewModel(application)
                        val rpcClient = RpcClient()
                        return ChatViewModel(workspaceId, sessionId, settingsViewModel, rpcClient) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}
