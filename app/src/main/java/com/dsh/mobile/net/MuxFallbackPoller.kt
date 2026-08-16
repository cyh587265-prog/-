package com.dsh.mobile.net
import android.util.Log
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
/**
 * Single message entry point: SSE first, silent-detection fallback to polling.
 * Outputs complete WireMessage only (user/message + assistant/message).
 */
class MuxFallbackPoller(
    private val rpcClient: RpcClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a WireEvent ({type,seq,time,data}) into a complete WireMessage, or null for noise. */
    private fun parseWireEvent(eventObj: JsonObject): WireMessage? {
        return try {
            val type = eventObj["type"]?.jsonPrimitive?.content ?: return null
            val seq = eventObj["seq"]?.jsonPrimitive?.int ?: return null
            val data = eventObj["data"]?.jsonObject ?: return null
            val isUser = type == "user/message"
            val isAssistant = type == "assistant/message"
            if (!isUser && !isAssistant) return null
            val content = if (isUser) {
                data["content"]?.jsonArray
            } else {
                data["message"]?.jsonObject?.get("content")?.jsonArray
                    ?: data["content"]?.jsonArray // 降级兼容
            } ?: return null
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
                content = listOf(ContentBlock("text", text), ContentBlock("reasoning", reasoning)),
                pending = false
            )
        } catch (e: Exception) {
            Log.w("MuxFallbackPoller", "parseWireEvent failed", e)
            null
        }
    }

    fun observe(
        sessionId: String,
        onEvent: suspend (WireMessage) -> Unit
    ): Flow<WireMessage> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val lastDataTime = AtomicLong(System.currentTimeMillis())
        // Per-observe dedup state (session-scoped, cleared on new observe); thread-safe for SSE+poll concurrency
        val seenSeqs = ConcurrentHashMap.newKeySet<Int>()
        val lastSeq = AtomicLong(0L)
        var streamJob: Job? = null
        var pollerJob: Job? = null
        var pollStarted = false

        suspend fun processMessages(messages: List<WireMessage>) {
            val newMessages = messages
                .filter { it.seq !in seenSeqs }
                .sortedBy { it.seq }
            for (msg in newMessages) {
                // Advance watermark only after the message is accepted into the channel
                if (trySend(msg).isSuccess) {
                    seenSeqs.add(msg.seq)
                    if (msg.seq > lastSeq.get()) lastSeq.set(msg.seq.toLong())
                }
            }
        }

        suspend fun pollHistory() {
            try {
                val payload = buildJsonObject {
                    put("sessionId", sessionId)
                    put("maxMessages", 50)
                }
                val result = rpcClient.call("session.history", payload, baseUrl)
                val events = result["events"]?.jsonArray ?: return
                // 全量解析 + seenSeqs 去重（不依赖 lastSeq 过滤：即使 watermark 已推进，
                // 未消费的消息仍会因 seq 不在 seenSeqs 而重新 emit，保证不丢）
                val messages = events.mapNotNull { entry ->
                    val eventObj = entry.jsonObject["event"]?.jsonObject
                        ?: entry.jsonObject
                    parseWireEvent(eventObj)
                }
                processMessages(messages)
            } catch (e: Exception) {
                Log.w("MuxFallbackPoller", "pollHistory failed", e)
            }
        }

        fun startPoller(intervalMs: Long) {
            pollerJob?.cancel()
            pollerJob = scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    pollHistory()
                }
            }
        }

        // SSE main channel: emit complete messages only; reconnect on drop (LAN flake recovery)
        streamJob = scope.launch {
            while (isActive) {
                // 轮询已启动（隧道降级）后不再建立 SSE 连接，避免双通道
                if (pollStarted) {
                    delay(2000)
                    continue
                }
                try {
                    MuxStream().connect(baseUrl).collect { frame ->
                        // 会话隔离：帧 payload 若带 sessionId，只处理当前会话的
                        val frameSessionId = frame.payload?.get("sessionId")?.jsonPrimitive?.content
                        if (frameSessionId != null && frameSessionId != sessionId) return@collect
                        when (frame) {
                            is UserMessageFrame, is AssistantMessageFrame -> {
                                // 只有有效消息帧才算 SSE 活跃（控制帧如 session/subscribed 不算，
                                // 否则公网隧道下控制帧照常到达、消息帧不透传时会阻止轮询降级）
                                lastDataTime.set(System.currentTimeMillis())
                                parseWireEvent(frame.payload ?: return@collect)?.let { msg ->
                                    processMessages(listOf(msg))
                                }
                            }
                            is MessageChunkFrame -> { /* streamed deltas ignored; complete message follows */ }
                            is SessionEventFrame -> { }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // 不吞取消
                } catch (e: Exception) {
                    Log.w("MuxFallbackPoller", "SSE stream ended, reconnect in 1s", e)
                }
                if (isActive) delay(1000)
            }
        }

        // Silent detection: SSE stays open but zero bytes under quick tunnels.
        // Every 2s, if no valid message for 2s, switch to polling fast (quick tunnel gives up immediately).
        scope.launch {
            while (isActive && !pollStarted) {
                delay(2000)
                if (System.currentTimeMillis() - lastDataTime.get() > 2000) {
                    pollStarted = true
                    streamJob?.cancel()
                    // catch-up：切换前先拉一次历史，避免切换窗口丢消息
                    pollHistory()
                    startPoller(1000)
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
