package com.dsh.mobile.net
import kotlinx.serialization.Serializable
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
    val id: String,
    val name: String,
    val workspaceId: String? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
    val model: String? = null
)
@Serializable
data class WorkspaceRow(
    val id: String,
    val name: String,
    val description: String? = null
)
@Serializable
data class SessionPage(
    val items: List<SessionRow>,
    val hasMore: Boolean,
    val nextCursor: String? = null
)
