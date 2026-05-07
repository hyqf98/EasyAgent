package io.github.easyagent.ai.entity;

import io.github.easyagent.enums.ResponseType;
import lombok.Builder;

/**
 * 统一 AI 响应事件。
 * <p>
 * 所有 CLI 实现的原始事件最终转换为此统一对象，供上层监听器消费。
 * 根据 {@link #type} 不同，对应的 content 字段会被填充。
 * </p>
 *
 * @param type       响应类型
 * @param sessionId  所属会话 ID
 * @param stepStart  步骤开始内容，仅 {@link ResponseType#STEP_START} 时非空
 * @param message    消息内容（思考或文本），仅 {@link ResponseType#MESSAGE} 时非空
 * @param toolCall   工具调用内容，仅 {@link ResponseType#TOOL_USE} 时非空
 * @param stepFinish 步骤结束内容，仅 {@link ResponseType#STEP_FINISH} 时非空
 * @param compact    压缩内容，仅 {@link ResponseType#COMPACT} 时非空
 * @param error      错误内容，仅 {@link ResponseType#ERROR} 时非空
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record AIResponse(
        ResponseType type,
        String sessionId,
        StepStartContent stepStart,
        MessageContent message,
        ToolCallContent toolCall,
        StepFinishContent stepFinish,
        CompactContent compact,
        ErrorContent error,
        RetryStatusContent retryStatus
) {}
