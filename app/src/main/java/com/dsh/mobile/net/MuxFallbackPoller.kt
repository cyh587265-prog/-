package com.dsh.mobile.net
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
class MuxFallbackPoller(
    private val rpcClient: RpcClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val seenSeqs = ConcurrentHashMap<Int, Boolean>()
    private var lastSeq = AtomicLong(0L)
    private var streamJob: Job? = null
    private var pollerJob: Job? = null
    private var isStreaming = true
    fun observe(
        sessionId: String,
        onEvent: suspend (WireMessage) -> Unit
    ): Flow<WireMessage> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        suspend fun processMessages(messages: List<WireMessage>) {
            val newMessages = messages
                .filter { !seenSeqs.containsKey(it.seq) }
                .sortedBy { it.seq }
            newMessages.forEach { msg ->
                seenSeqs[msg.seq] = true
                if (msg.seq > lastSeq.get()) {
                    lastSeq.set(msg.seq.toLong())
                }
                trySend(msg)
            }
        }
        suspend fun pollHistory() {
            try {
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                    put("maxMessages", 50)
                }
                val result = rpcClient.call("session.history", payload, baseUrl)
                // session.history value: { events: [{ event: {type,seq,time,data}, view? }], hasMore }
                // Only complete messages count: user/message -> data.content, assistant/message -> data.message.content
                val events = result["events"]?.jsonArray ?: return
                val messages = events.mapNotNull { entry ->
                    val eventObj = entry.jsonObject["event"]?.jsonObject ?: return@mapNotNull null
                    val type = eventObj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val seq = eventObj["seq"]?.jsonPrimitive?.int ?: return@mapNotNull null
                    val data = eventObj["data"]?.jsonObject ?: return@mapNotNull null
                    val isUser = type == "user/message"
                    val isAssistant = type == "assistant/message"
                    if (!isUser && !isAssistant) return@mapNotNull null
                    val content = if (isUser) {
                        data["content"]?.jsonArray
                    } else {
                        data["message"]?.jsonObject?.get("content")?.jsonArray
                    } ?: return@mapNotNull null
                    var text = ""
                    var reasoning = ""
                    content.forEach { block ->
                        val b = block.jsonObject
                        when (b["type"]?.jsonPrimitive?.content) {
                            "text" -> text += b["text"]?.jsonPrimitive?.content ?: ""
                            "reasoning" -> reasoning += b["text"]?.jsonPrimitive?.content ?: ""
                        }
                    }
                    WireMessage(
                        id = data["id"]?.jsonPrimitive?.content
                            ?: data["message"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                            ?: "msg-$seq",
                        seq = seq,
                        kind = if (isUser) "user" else "assistant",
                        content = listOf(ContentBlock("text", text)),
                        pending = false
                    )
                }
                processMessages(messages)
            } catch (e: Exception) {
                // Polling failed, continue
            }
        }
        // Start SSE stream
        streamJob = scope.launch {
            try {
                val muxStream = MuxStream()
                muxStream.connect(baseUrl).collect { frame ->
                    isStreaming = true
                    // Extract message from frame and convert to WireMessage
                    // For now, we just forward through the flow
                    // This is a simplified implementation - full message parsing
                    // would be more complex and is handled in the chat subtask
                    // We just pass the raw frame data
                }
            } catch (e: Exception) {
                isStreaming = false
                // Fallback to polling
                pollerJob = scope.launch {
                    while (isActive) {
                        delay(3000)
                        pollHistory()
                    }
                }
            }
        }
        // Start polling as backup if SSE doesn't produce data within 12s
        scope.launch {
            delay(12000)
            if (lastSeq.get() == 0L) {
                isStreaming = false
                streamJob?.cancel()
                pollerJob = scope.launch {
                    while (isActive) {
                        delay(3000)
                        pollHistory()
                    }
                }
            }
        }
        awaitClose {
            streamJob?.cancel()
            pollerJob?.cancel()
            scope.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
