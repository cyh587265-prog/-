package com.dsh.mobile.net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
class RpcClient {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable
    private data class RpcRequest(val rpcId: String, val payload: JsonObject)
    @Serializable
    private data class RpcResponse(
        val type: String,
        val rpcId: String,
        val result: RpcResult
    )
    @Serializable
    private data class RpcResult(
        val ok: Boolean,
        val value: JsonObject? = null,
        val error: JsonObject? = null
    )
    suspend fun call(method: String, payload: JsonObject, baseUrl: String): JsonObject =
        withContext(Dispatchers.IO) {
            val rpcId = UUID.randomUUID().toString()
            val url = "$baseUrl${Constants.API_PREFIX}/$method"
            val requestBody = RpcRequest(rpcId, payload)
            val bodyJson = json.encodeToString(RpcRequest.serializer(), requestBody)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = DshHttpClient.client.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                throw Exception("RPC call failed with code ${response.code}: $responseBody")
            }
            val parsed = json.decodeFromString(RpcResponse.serializer(), responseBody)
            if (!parsed.result.ok) {
                throw Exception("RPC result error: ${parsed.result.error}")
            }
            parsed.result.value ?: buildJsonObject { }
        }
    suspend fun sessionList(cursor: String? = null, baseUrl: String): SessionPage {
        val payload = buildJsonObject {
            cursor?.let { put("cursor", it) }
        }
        val result = call("session.list", payload, baseUrl)
        return json.decodeFromString(SessionPage.serializer(), result.toString())
    }
}
