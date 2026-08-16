package com.dsh.mobile.net
/**
 * 消息事件密封接口
 * 用于流式输出时区分完整消息和增量块
 */
sealed interface MessageEvent {
    /** 完整消息（用户或助手） */
    data class FullMessage(val msg: WireMessage) : MessageEvent
    /** 增量块（助手流式输出） */
    data class Chunk(val delta: ChunkDelta) : MessageEvent
}
