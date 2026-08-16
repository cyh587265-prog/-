package com.dsh.mobile.net
/**
 * 流式增量数据
 * @param turn 对话轮次
 * @param step 步骤序号
 * @param kind 增量类型："text" | "reasoning"
 * @param text 本次增量文本
 */
data class ChunkDelta(
    val turn: Int,
    val step: Int,
    val kind: String,      // "text" | "reasoning"
    val text: String       // 本次增量文本
)
