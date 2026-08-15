package com.dsh.mobile.net
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
class PairingClient {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable
    private data class PairAcceptRequest(val token: String)
    @Serializable
    private data class PairAcceptResponse(val ok: Boolean, val deviceId: String? = null)
    suspend fun accept(token: String, baseUrl: String): Result<String> {
        return try {
            val url = "$baseUrl${Constants.PAIR_ACCEPT_PATH}"
            val requestBody = PairAcceptRequest(token)
            val bodyJson = json.encodeToString(PairAcceptRequest.serializer(), requestBody)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            val response = DshHttpClient.client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            when (response.code) {
                200 -> {
                    val parsed = json.decodeFromString(PairAcceptResponse.serializer(), responseBody)
                    if (parsed.ok && parsed.deviceId != null) {
                        Result.success(parsed.deviceId)
                    } else {
                        Result.failure(Exception("Pairing failed: deviceId missing"))
                    }
                }
                403 -> Result.failure(Exception("Pairing failed: fence (403)"))
                404 -> Result.failure(Exception("Pairing failed: token not found (404)"))
                409 -> Result.failure(Exception("Pairing failed: token already used (409)"))
                429 -> Result.failure(Exception("Pairing failed: rate limited (429)"))
                else -> Result.failure(Exception("Pairing failed with code ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    fun logout() {
        DshHttpClient.clearCookies()
    }
}
