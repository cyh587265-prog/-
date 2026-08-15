package com.dsh.mobile.net
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
@Serializable
data class ContentBlock(
    val type: String, // "text" or "reasoning"
    val text: String
)
@Serializable
data class WireMessage(
    val id: String,
    val seq: Int,
    val kind: String, // "user", "assistant", etc.
    val content: List<ContentBlock>,
    val turn: Int? = null,
    val step: Int? = null,
    val pending: Boolean = false
)
@Serializable
data class SessionRow(
    val sessionId: String,
    val updatedAt: JsonElement? = null,
    val running: Boolean = false,
    val blank: Boolean = false,
    val parentSessionId: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: JsonElement? = null
)
@Serializable
data class WorkspaceRow(
    val workspaceId: String,
    val path: String? = null,
    val title: String? = null,
    val sessionIds: List<String> = emptyList(),
    val createdAt: JsonElement? = null,
    val updatedAt: JsonElement? = null
)
@Serializable
data class SessionPage(
    val items: List<SessionRow>,
    val hasMore: Boolean,
    val nextCursor: String? = null
)
