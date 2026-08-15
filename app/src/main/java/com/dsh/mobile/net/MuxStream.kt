package com.dsh.mobile.net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
class MuxStream {
    private val json = Json { ignoreUnknownKeys = true }
    private val streamClient = OkHttpClient.Builder()
        .cookieJar(DshHttpClient.client.cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for SSE
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    fun connect(baseUrl: String): Flow<MuxFrame> = callbackFlow {
        val request = Request.Builder()
            .url("$baseUrl${Constants.MUX_EVENTS_PATH}")
            .get()
            .build()
        val call = streamClient.newCall(request)
        var response: Response? = null
        try {
            response = call.execute()
            if (!response.isSuccessful) {
                close(Exception("SSE connection failed: ${response.code}"))
                return@callbackFlow
            }
            val source = response.body?.source() ?: run {
                close(Exception("Empty response body"))
                return@callbackFlow
            }
            while (isActive) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.substring(6)
                    if (data.isNotEmpty()) {
                        try {
                            val envelope = json.decodeFromString<ServerEnvelope>(data)
                            val frame = parseFrame(envelope)
                            if (frame != null) {
                                trySend(frame)
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("MuxStream", "Failed to parse SSE frame", e)
                        }
                    }
                } else if (line.startsWith(": ping")) {
                    // Heartbeat, ignore
                }
            }
        } catch (e: IOException) {
            close(e)
        } catch (e: Exception) {
            close(e)
        } finally {
            response?.close()
        }
        awaitClose {
            call.cancel()  // 主动取消，释放阻塞的 readUtf8Line
            response?.close()
        }
    }.flowOn(Dispatchers.IO)
    private fun parseFrame(envelope: ServerEnvelope): MuxFrame? {
        // SSE data frames are server-request envelopes: { type: 'server-request', rpcId, payload: WireEvent }.
        // The real frame type lives inside payload.type (e.g. 'assistant/chunk', 'session/event').
        val payload = envelope.payload ?: return null
        val frameType = payload["type"]?.jsonPrimitive?.content ?: return null
        return when (frameType) {
            "session/event" -> SessionEventFrame(
                type = frameType,
                rpcId = envelope.rpcId,
                payload = payload
            )
            "user/message" -> UserMessageFrame(
                type = frameType,
                rpcId = envelope.rpcId,
                payload = payload
            )
            "assistant/message" -> AssistantMessageFrame(
                type = frameType,
                rpcId = envelope.rpcId,
                payload = payload
            )
            "assistant/chunk", "message/chunk" -> MessageChunkFrame(
                type = frameType,
                rpcId = envelope.rpcId,
                payload = payload
            )
            else -> null // Unknown frame type (session/subscribed, session/jobs, ...), discard
        }
    }
    @kotlinx.serialization.Serializable
    data class ServerEnvelope(
        val type: String,
        val rpcId: String? = null,
        val payload: JsonObject? = null
    )
}
sealed class MuxFrame {
    abstract val type: String
    abstract val rpcId: String?
    abstract val payload: JsonObject?
}
data class SessionEventFrame(
    override val type: String,
    override val rpcId: String?,
    override val payload: JsonObject?
) : MuxFrame()
data class UserMessageFrame(
    override val type: String,
    override val rpcId: String?,
    override val payload: JsonObject?
) : MuxFrame()
data class AssistantMessageFrame(
    override val type: String,
    override val rpcId: String?,
    override val payload: JsonObject?
) : MuxFrame()
data class MessageChunkFrame(
    override val type: String,
    override val rpcId: String?,
    override val payload: JsonObject?
) : MuxFrame()
