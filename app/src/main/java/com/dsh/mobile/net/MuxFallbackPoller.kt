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
    private val seenSeqs = ConcurrentHashMap<Int, Boolean>()
    private var lastSeq = AtomicLong(0L)
    private var streamJob: Job? = null
    private var pollerJob: Job? = null

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
                val events = result["events"]?.jsonArray ?: return
                val messages = events.mapNotNull { entry ->
                    val eventObj = entry.jsonObject["event"]?.jsonObject
                        ?: entry.jsonObject
                    parseWireEvent(eventObj)
                }.filter { it.seq > lastSeq.get() }  // incremental
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

        // SSE main channel: emit complete messages only; chunk frames keep the channel alive
        streamJob = scope.launch {
            try {
                MuxStream().connect(baseUrl).collect { frame ->
                    lastDataTime.set(System.currentTimeMillis())
                    when (frame) {
                        is AssistantMessageFrame -> {
                            parseWireEvent(frame.payload ?: return@collect).let { msg ->
                                if (msg != null) processMessages(listOf(msg))
                            }
                        }
                        is MessageChunkFrame -> { /* streamed deltas ignored; complete message follows */ }
                        is SessionEventFrame -> { }
                    }
                }
            } catch (e: Exception) {
                Log.w("MuxFallbackPoller", "SSE stream ended", e)
            }
        }

        // Silent detection: SSE stays open but zero bytes under quick tunnels.
        // Every 3s, if no SSE data for 5s, switch to polling (loop until started).
        scope.launch {
            var pollStarted = false
            while (isActive && !pollStarted) {
                delay(3000)
                if (System.currentTimeMillis() - lastDataTime.get() > 5000) {
                    pollStarted = true
                    streamJob?.cancel()
                    startPoller(2000)
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
