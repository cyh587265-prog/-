package com.dsh.mobile.ui
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.net.ChunkDelta
import com.dsh.mobile.net.MessageEvent
import com.dsh.mobile.net.MuxFallbackPoller
import com.dsh.mobile.net.RpcClient
import com.dsh.mobile.net.WireMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
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
    // 流式增量累积：按 turn 索引正在生成的 pending 消息（同一轮多步骤合并到一个气泡）
    private val pendingByTurnStep = mutableMapOf<Int, ChatMessageUi>()
    init {
        viewModelScope.launch {
            // 等待服务器地址就绪（SettingsViewModel 异步从 DataStore 加载）
            settingsViewModel.awaitActiveUrl()
            loadHistory()
            startMessageStream()
        }
    }
    private var streamJob: Job? = null
    private fun startMessageStream() {
        // 防重复订阅：配置变更/重建时避免多个消息流
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            // 等待服务器地址就绪（DataStore 异步加载完成）
            val baseUrl = settingsViewModel.awaitActiveUrl() ?: return@launch
            // 统一消息入口：Poller 内部 SSE 优先 + 静默轮询切换，输出 MessageEvent（完整消息或增量）
            // 关键：消息经 Flow 发射，必须在 collect 里消费（onEvent 参数 Poller 不调用）
            MuxFallbackPoller(rpcClient, baseUrl)
                .observeWithDeltas(sessionId)
                .catch { e -> Log.e("ChatViewModel", "Message stream error", e) }
                .buffer(Channel.UNLIMITED)  // 无界缓冲：UI 慢时不丢消息
                .collect { event -> handleMessageEvent(event) }
        }
    }
    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
    /** 分发完整消息或流式增量事件 */
    private fun handleMessageEvent(event: MessageEvent) {
        when (event) {
            is MessageEvent.FullMessage -> handleFullMessage(event.msg)
            is MessageEvent.Chunk -> handleChunk(event.delta)
        }
    }
    /** 处理流式增量：累积到对应 turn 的 pending 消息（同一轮的多步骤合并到一个气泡） */
    private fun handleChunk(delta: ChunkDelta) {
        val key = delta.turn
        val pending = pendingByTurnStep[key]
        val updated = if (pending != null) {
            pending.copy(
                text = if (delta.kind == "text") pending.text + delta.text else pending.text,
                reasoning = if (delta.kind == "reasoning") pending.reasoning + delta.text else pending.reasoning,
                isPending = true
            )
        } else {
            ChatMessageUi(
                id = "pending-$key",
                text = if (delta.kind == "text") delta.text else "",
                reasoning = if (delta.kind == "reasoning") delta.text else "",
                kind = MessageKind.Assistant,
                isPending = true,
                turn = key,
                step = null,
                seq = null
            )
        }
        pendingByTurnStep[key] = updated
        _uiState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == updated.id }
            val newMessages = if (idx >= 0) {
                // 已存在：替换内容，保持位置
                state.messages.toMutableList().apply { set(idx, updated) }
            } else {
                // 新 pending：追加到末尾
                state.messages + updated
            }
            // 多轮生成：只要还有 pending 就保持 isSending
            state.copy(messages = newMessages, isSending = pendingByTurnStep.isNotEmpty())
        }
        // limitMessages 移到完整消息/历史合并处，避免每个 chunk 都复制截断
    }
    /** 处理完整消息：assistant 到达时替换对应 pending；user 消息直接追加 */
    private fun handleFullMessage(wireMessage: WireMessage) {
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
        // 尝试按 turn 匹配并移除对应 pending（同轮多步骤合并）
        val turn = wireMessage.turn
        val key = turn
        val pending = key?.let { pendingByTurnStep.remove(it) }
        val message = ChatMessageUi(
            // 若有 pending 则保留其 id，避免 LazyColumn key 变化导致重组闪烁
            id = pending?.id ?: wireMessage.id,
            text = text,
            reasoning = reasoning,
            kind = kind,
            isPending = false,
            turn = turn,
            step = step,
            seq = wireMessage.seq
        )
        _uiState.update { state ->
            val newMessages = if (pending != null && kind == MessageKind.Assistant) {
                // 替换同 id 的 pending 消息
                state.messages.map { if (it.id == pending.id) message else it }
            } else {
                state.messages + message
            }
            // 多轮生成：isSending 跟随剩余 pending 数
            state.copy(messages = newMessages, isSending = pendingByTurnStep.isNotEmpty())
        }
        limitMessages()
    }
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        // 不插入 optimistic 用户消息：等服务端 user/message 事件回显（SSE 或轮询），避免重复
        _uiState.update { it.copy(inputText = "", isSending = true) }
        viewModelScope.launch {
            try {
                val baseUrl = settingsViewModel.awaitActiveUrl() ?: return@launch
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
                val baseUrl = settingsViewModel.awaitActiveUrl() ?: return@launch
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
                        ?: entry.jsonObject // 容错：直接是 event 的情况
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
                val baseUrl = settingsViewModel.awaitActiveUrl() ?: return@launch
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
                val baseUrl = settingsViewModel.awaitActiveUrl() ?: return@launch
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
            // 尝试解析 turn / step（部分服务端事件可能携带）
            val turn = try {
                data["turn"]?.jsonPrimitive?.int
                    ?: data["message"]?.jsonObject?.get("turn")?.jsonPrimitive?.int
            } catch (_: Exception) { null }
            val step = try {
                data["step"]?.jsonPrimitive?.int
                    ?: data["message"]?.jsonObject?.get("step")?.jsonPrimitive?.int
            } catch (_: Exception) { null }
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
                turn = turn,
                step = step,
                seq = seq
            )
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to parse message", e)
            return null
        }
    }
    private fun limitMessages() {
        _uiState.update { state ->
            if (state.messages.size > maxMessages) {
                val trimmed = state.messages.takeLast(maxMessages)
                // 清理已不在消息列表中的 pending 引用，防止内存泄漏
                val remainingIds = trimmed.map { it.id }.toSet()
                pendingByTurnStep.entries.removeIf { (_, msg) -> msg.id !in remainingIds }
                state.copy(messages = trimmed)
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
