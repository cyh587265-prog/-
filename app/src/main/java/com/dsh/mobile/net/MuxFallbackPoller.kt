package com.dsh.mobile.net
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
 * 单消息入口点：SSE 优先，静默检测降级到轮询。
 * 
 * 支持两种输出模式：
 * 1. observe(): 仅输出完整消息（向后兼容）
 * 2. observeWithDeltas(): 输出完整消息 + 流式增量
 */
class MuxFallbackPoller(
    private val rpcClient: RpcClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    /** 解析 WireEvent 为完整 WireMessage，非完整消息返回 null */
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
                pending = false,
                turn = data["turn"]?.jsonPrimitive?.int,
                step = data["step"]?.jsonPrimitive?.int
            )
        } catch (e: Exception) {
            Log.w("MuxFallbackPoller", "parseWireEvent failed", e)
            null
        }
    }
    /**
     * 解析 assistant/chunk 增量事件为 ChunkDelta
     * 只处理 text-delta 和 reasoning-delta 类型
     */
    private fun parseChunkEvent(eventObj: JsonObject): ChunkDelta? {
        return try {
            val type = eventObj["type"]?.jsonPrimitive?.content ?: return null
            if (type != "assistant/chunk") return null
            val seq = eventObj["seq"]?.jsonPrimitive?.int ?: return null
            val data = eventObj["data"]?.jsonObject ?: return null
            val turn = data["turn"]?.jsonPrimitive?.int ?: return null
            val step = data["step"]?.jsonPrimitive?.int ?: return null
            val chunk = data["chunk"]?.jsonObject ?: return null
            val chunkType = chunk["type"]?.jsonPrimitive?.content ?: return null
            // 只处理 delta 类型，忽略 block-start/block-end/finish
            val kind = when (chunkType) {
                "text-delta" -> "text"
                "reasoning-delta" -> "reasoning"
                else -> return null
            }
            val text = chunk["text"]?.jsonPrimitive?.content ?: ""
            if (text.isEmpty()) return null
            ChunkDelta(
                turn = turn,
                step = step,
                kind = kind,
                text = text
            )
        } catch (e: Exception) {
            Log.w("MuxFallbackPoller", "parseChunkEvent failed", e)
            null
        }
    }
    /**
     * 向后兼容：仅输出完整消息
     */
    fun observe(
        sessionId: String,
        onEvent: suspend (WireMessage) -> Unit
    ): Flow<WireMessage> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val lastDataTime = AtomicLong(System.currentTimeMillis())
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
        streamJob = scope.launch {
            while (isActive) {
                if (pollStarted) {
                    delay(2000)
                    continue
                }
                try {
                    MuxStream().connect(baseUrl).collect { frame ->
                        val frameSessionId = frame.payload?.get("sessionId")?.jsonPrimitive?.content
                        if (frameSessionId != null && frameSessionId != sessionId) return@collect
                        when (frame) {
                            is UserMessageFrame, is AssistantMessageFrame -> {
                                lastDataTime.set(System.currentTimeMillis())
                                parseWireEvent(frame.payload ?: return@collect)?.let { msg ->
                                    processMessages(listOf(msg))
                                }
                            }
                            is MessageChunkFrame -> { /* 流式增量在此方法中忽略 */ }
                            is SessionEventFrame -> { }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MuxFallbackPoller", "SSE stream ended, reconnect in 1s", e)
                }
                if (isActive) delay(1000)
            }
        }
        scope.launch {
            while (isActive && !pollStarted) {
                delay(2000)
                if (System.currentTimeMillis() - lastDataTime.get() > 2000) {
                    pollStarted = true
                    streamJob?.cancel()
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
    /**
     * 新方法：输出完整消息 + 流式增量
     * 保持现有 observe 签名不变，新增此方法提供流式能力
     */
    fun observeWithDeltas(
        sessionId: String
    ): Flow<MessageEvent> = callbackFlow {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val lastDataTime = AtomicLong(System.currentTimeMillis())
        // 完整消息去重
        val seenSeqs = ConcurrentHashMap.newKeySet<Int>()
        val lastSeq = AtomicLong(0L)
        // 增量消息去重（独立集合，避免与完整消息冲突）
        val seenChunkSeqs = ConcurrentHashMap.newKeySet<Int>()
        var streamJob: Job? = null
        var pollerJob: Job? = null
        var pollStarted = false
        /**
         * 处理完整消息列表
         */
        suspend fun processMessages(messages: List<WireMessage>) {
            val newMessages = messages
                .filter { it.seq !in seenSeqs }
                .sortedBy { it.seq }
            for (msg in newMessages) {
                if (trySend(MessageEvent.FullMessage(msg)).isSuccess) {
                    seenSeqs.add(msg.seq)
                    if (msg.seq > lastSeq.get()) lastSeq.set(msg.seq.toLong())
                }
            }
        }
        /**
         * 处理增量块列表
         */
        suspend fun processChunks(chunks: List<ChunkDelta>) {
            // 注意：chunk 事件没有 seq 字段，但我们用 turn+step+kind 组合去重
            // 但为了精确去重，我们需要 seq 来去重。实际上 chunk 事件也有 seq
            // 但由于 parseChunkEvent 需要 seq 来去重，我们修改解析逻辑
            // 实际上去重由调用方传入已过滤的 chunks
            for (chunk in chunks) {
                // 使用 turn+step+kind 作为去重键（同一轮次同一步骤同类型只输出一次）
                // 但我们使用 seq 去重更准确，需要修改 parseChunkEvent 返回 seq
                // 为了简化，我们在这里使用 turn+step+kind 去重
                // 但更好的方式是在解析时传入 seq
                // 重构：使用组合键去重
                val key = "${chunk.turn}_${chunk.step}_${chunk.kind}"
                // 由于增量可能多次到达同一 turn/step/kind，我们需要累积
                // 但实际上每个 delta 都应该输出，由 ViewModel 负责累积
                // 去重应该基于 seq，但 chunk 事件的 seq 在事件级别
                // 我们修改 parseChunkEvent 返回 Pair<ChunkDelta, Int>
                // 但由于我们需要保持代码简洁，改为在解析时直接处理
                // 这里我们用另一个方式：直接发送，由 ViewModel 去重
                // 但为了精确，我们改为在解析时传递 seq
                // 这里重构：processChunks 接收 List<Pair<ChunkDelta, Int>>
                // 但由于时间关系，我们直接在解析处处理
                trySend(MessageEvent.Chunk(chunk))
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
                // 分离完整消息和增量
                val fullMessages = mutableListOf<WireMessage>()
                val chunks = mutableListOf<ChunkDelta>()
                for (entry in events) {
                    val eventObj = entry.jsonObject["event"]?.jsonObject
                        ?: entry.jsonObject
                    val type = eventObj["type"]?.jsonPrimitive?.content ?: continue
                    when {
                        // 完整消息
                        type == "user/message" || type == "assistant/message" -> {
                            parseWireEvent(eventObj)?.let { msg ->
                                if (msg.seq !in seenSeqs) {
                                    fullMessages.add(msg)
                                }
                            }
                        }
                        // 增量事件
                        type == "assistant/chunk" -> {
                            val seq = eventObj["seq"]?.jsonPrimitive?.int ?: continue
                            if (seq in seenChunkSeqs) continue
                            parseChunkEvent(eventObj)?.let { chunk ->
                                if (seenChunkSeqs.add(seq) && seenChunkSeqs.size > 5000) seenChunkSeqs.clear()
                                chunks.add(chunk)
                            }
                        }
                    }
                }
                // 先发送完整消息（按 seq 排序）
                if (fullMessages.isNotEmpty()) {
                    processMessages(fullMessages.sortedBy { it.seq })
                }
                // 再发送增量（按 seq 排序，但增量没有 seq，按 turn/step 排序）
                if (chunks.isNotEmpty()) {
                    chunks.sortedWith(compareBy<ChunkDelta> { it.turn }.thenBy { it.step })
                        .forEach { chunk ->
                            trySend(MessageEvent.Chunk(chunk))
                        }
                }
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
        streamJob = scope.launch {
            while (isActive) {
                if (pollStarted) {
                    delay(2000)
                    continue
                }
                try {
                    MuxStream().connect(baseUrl).collect { frame ->
                        val frameSessionId = frame.payload?.get("sessionId")?.jsonPrimitive?.content
                        if (frameSessionId != null && frameSessionId != sessionId) return@collect
                        when (frame) {
                            is UserMessageFrame, is AssistantMessageFrame -> {
                                lastDataTime.set(System.currentTimeMillis())
                                parseWireEvent(frame.payload ?: return@collect)?.let { msg ->
                                    if (msg.seq !in seenSeqs) {
                                        if (trySend(MessageEvent.FullMessage(msg)).isSuccess) {
                                            seenSeqs.add(msg.seq)
                                        }
                                    }
                                }
                            }
                            is MessageChunkFrame -> {
                                // 局域网 SSE 也支持流式增量
                                lastDataTime.set(System.currentTimeMillis())
                                val payload = frame.payload ?: return@collect
                                val seq = payload["seq"]?.jsonPrimitive?.int ?: return@collect
                                if (seq in seenChunkSeqs) return@collect
                                parseChunkEvent(payload)?.let { chunk ->
                                    if (seenChunkSeqs.add(seq) && seenChunkSeqs.size > 5000) seenChunkSeqs.clear()
                                    trySend(MessageEvent.Chunk(chunk))
                                }
                            }
                            is SessionEventFrame -> { }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("MuxFallbackPoller", "SSE stream ended, reconnect in 1s", e)
                }
                if (isActive) delay(1000)
            }
        }
        scope.launch {
            while (isActive && !pollStarted) {
                delay(2000)
                if (System.currentTimeMillis() - lastDataTime.get() > 2000) {
                    pollStarted = true
                    streamJob?.cancel()
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
