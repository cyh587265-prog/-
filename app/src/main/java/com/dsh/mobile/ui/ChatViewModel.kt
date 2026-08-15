package com.dsh.mobile.ui
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.net.AssistantMessageFrame
import com.dsh.mobile.net.MessageChunkFrame
import com.dsh.mobile.net.MuxFallbackPoller
import com.dsh.mobile.net.MuxStream
import com.dsh.mobile.net.RpcClient
import com.dsh.mobile.net.SessionEventFrame
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
            // SSE 流：消息主通道（解析帧）
            launch {
                MuxStream().connect(baseUrl)
                    .catch { e -> Log.e("ChatViewModel", "SSE stream error", e) }
                    .collect { frame ->
                        when (frame) {
                            is SessionEventFrame -> {
                                Log.d("ChatViewModel", "Session event: ${frame.type}")
                            }
                            is AssistantMessageFrame -> {
                                handleAssistantMessage(frame.payload)
                            }
                            is MessageChunkFrame -> {
                                handleMessageChunk(frame.payload)
                            }
                        }
                    }
            }
            // 轮询兜底：Poller 内部 SSE 断流后自动轮询 history，输出 WireMessage
            launch {
                MuxFallbackPoller(rpcClient, baseUrl).observe(sessionId) { wireMessage ->
                    handleWireMessage(wireMessage)
                }.catch { e -> Log.e("ChatViewModel", "Fallback poller error", e) }.collect { }
            }
        }
    }
    private fun handleWireMessage(wireMessage: WireMessage) {
        sendTimeoutJob?.cancel()
        // 按 seq 去重
        val existingSeq = _uiState.value.messages.any { it.seq == wireMessage.seq }
        if (existingSeq) return
        // 从 WireMessage 解析内容
        var text = ""
        var reasoning = ""
        wireMessage.content.forEach { block ->
            when (block.type) {
                "text" -> text += block.text ?: ""
                "reasoning" -> reasoning += block.text ?: ""
            }
        }
        val kind = when (wireMessage.kind) {
            "user" -> MessageKind.User
            "assistant" -> MessageKind.Assistant
            else -> MessageKind.Assistant
        }
        val message = ChatMessageUi(
            id = wireMessage.id,
            text = text,
            reasoning = reasoning,
            kind = kind,
            isPending = wireMessage.pending ?: false,
            turn = wireMessage.turn,
            step = wireMessage.step,
            seq = wireMessage.seq
        )
        // 如果这是 pending 消息，替换之前的 pending
        if (wireMessage.pending == true) {
            pendingMessageId?.let { pendingId ->
                _uiState.update { state ->
                    val filtered = state.messages.filter { it.id != pendingId && it.id != message.id }
                    state.copy(messages = filtered + message, isSending = true)
                }
            } ?: run {
                addMessage(message)
            }
            pendingMessageId = message.id
        } else {
            // 完成的消息，取消 pending 状态
            if (pendingMessageId == message.id || pendingMessageId != null) {
                _uiState.update { state ->
                    val filtered = state.messages.filter { it.id != pendingMessageId }
                    state.copy(messages = filtered + message, isSending = false)
                }
                pendingMessageId = null
            } else {
                addMessage(message)
            }
        }
    }
    private fun handleAssistantMessage(payload: JsonObject?) {
        if (payload == null) return
        sendTimeoutJob?.cancel()
        try {
            // mux frame payload is a WireEvent: assistant message content lives at data.message.content
            val data = payload["data"]?.jsonObject ?: payload
            val messageObj = data["message"]?.jsonObject
            val content = messageObj?.get("content")?.jsonArray ?: return
            val turn = data["turn"]?.jsonPrimitive?.int ?: payload["turn"]?.jsonPrimitive?.int
            val step = data["step"]?.jsonPrimitive?.int ?: payload["step"]?.jsonPrimitive?.int
            val messageId = messageObj["id"]?.jsonPrimitive?.content
                ?: data["id"]?.jsonPrimitive?.content
                ?: "msg_${System.currentTimeMillis()}"
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
            if (pendingMessageId != null) {
                _uiState.update { state ->
                    val updatedMessages = state.messages.map { msg ->
                        if (msg.id == pendingMessageId) {
                            msg.copy(
                                text = text,
                                reasoning = reasoning,
                                isPending = false,
                                turn = turn,
                                step = step
                            )
                        } else msg
                    }
                    state.copy(messages = updatedMessages, isSending = false)
                }
                pendingMessageId = null
            } else {
                val newMessage = ChatMessageUi(
                    id = messageId,
                    text = text,
                    reasoning = reasoning,
                    kind = MessageKind.Assistant,
                    isPending = false,
                    turn = turn,
                    step = step
                )
                addMessage(newMessage)
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to parse assistant message", e)
        }
    }
    private fun handleMessageChunk(payload: JsonObject?) {
        if (payload == null) return
        try {
            // chunk data lives under WireEvent.data
            val data = payload["data"]?.jsonObject ?: payload
            val chunk = data["chunk"]?.jsonObject ?: data
            val type = chunk["type"]?.jsonPrimitive?.content ?: "text"
            val text = chunk["text"]?.jsonPrimitive?.content ?: ""
            val turn = data["turn"]?.jsonPrimitive?.int ?: payload["turn"]?.jsonPrimitive?.int
            val step = data["step"]?.jsonPrimitive?.int ?: payload["step"]?.jsonPrimitive?.int
            val messageId = data["id"]?.jsonPrimitive?.content
                ?: data["messageId"]?.jsonPrimitive?.content
                ?: "msg_${System.currentTimeMillis()}_${turn}_${step}"
            val isReasoning = type == "reasoning" || type == "reasoning-delta"
            val isText = type == "text" || type == "text-delta"
            if (!isReasoning && !isText) return
            _uiState.update { state ->
                val existingIndex = state.messages.indexOfFirst { 
                    it.id == messageId || (it.turn == turn && it.step == step)
                }
                if (existingIndex >= 0) {
                    val existing = state.messages[existingIndex]
                    val newMessages = state.messages.toMutableList()
                    if (isReasoning) {
                        newMessages[existingIndex] = existing.copy(
                            reasoning = existing.reasoning + text,
                            isPending = true
                        )
                    } else {
                        newMessages[existingIndex] = existing.copy(
                            text = existing.text + text,
                            isPending = true
                        )
                    }
                    state.copy(messages = newMessages)
                } else {
                    val newMessage = ChatMessageUi(
                        id = messageId,
                        text = if (isText) text else "",
                        reasoning = if (isReasoning) text else "",
                        kind = MessageKind.Assistant,
                        isPending = true,
                        turn = turn,
                        step = step
                    )
                    if (pendingMessageId != null) {
                        val filtered = state.messages.filter { it.id != pendingMessageId }
                        pendingMessageId = messageId
                        state.copy(messages = filtered + newMessage, isSending = true)
                    } else {
                        pendingMessageId = messageId
                        state.copy(messages = state.messages + newMessage, isSending = true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to handle chunk", e)
        }
    }
    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isSending) return
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
                        hasMoreHistory = parsed.size > HISTORY_PAGE_SIZE
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
                // 解析模型列表
                val groups = result["groups"]?.jsonArray ?: emptyList()
                val modelsMap = mutableMapOf<String, MutableList<ModelItem>>()
                groups.forEach { group ->
                    val groupObj = group.jsonObject
                    val provider = groupObj["provider"]?.jsonPrimitive?.content ?: "未知"
                    val modelList = groupObj["models"]?.jsonArray ?: return@forEach
                    val items = modelList.mapNotNull { model ->
                        val modelObj = model.jsonObject
                        val id = modelObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = modelObj["name"]?.jsonPrimitive?.content ?: id
                        val effort = modelObj["reasoningEffort"]?.jsonPrimitive?.intOrNull
                        ModelItem(id, name, provider, effort)
                    }
                    modelsMap.getOrPut(provider) { mutableListOf() }.addAll(items)
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
    fun selectModel(provider: String, model: String, reasoningEffort: Int?) {
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
