package com.dsh.mobile.net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        var response: Response? = null
        try {
            response = streamClient.newCall(request).execute()
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
                            val jsonObj = json.decodeFromString<JsonObject>(data)
                            val envelope = json.decodeFromString<ServerEnvelope>(data)
                            val frame = parseFrame(envelope)
                            if (frame != null) {
                                trySend(frame)
                            }
                        } catch (e: Exception) {
                            // Ignore malformed JSON
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
            response?.close()
        }
    }.withContext(Dispatchers.IO)
    private fun parseFrame(envelope: ServerEnvelope): MuxFrame? {
        return when (envelope.type) {
            "session/event" -> SessionEventFrame(
                type = envelope.type,
                rpcId = envelope.rpcId,
                payload = envelope.payload
            )
            "assistant/message" -> AssistantMessageFrame(
                type = envelope.type,
                rpcId = envelope.rpcId,
                payload = envelope.payload
            )
            "assistant/chunk", "message/chunk" -> MessageChunkFrame(
                type = envelope.type,
                rpcId = envelope.rpcId,
                payload = envelope.payload
            )
            else -> null // Unknown type, discard
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
