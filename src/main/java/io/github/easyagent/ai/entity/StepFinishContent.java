package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * 步骤结束事件内容。
 * <p>
 * 当一个推理步骤完成时携带的结束原因和令牌消耗统计。
 * </p>
 *
 * @param reason     完成原因，如 "stop"、"tool-calls"
 * @param tokenUsage 令牌使用上下文
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record StepFinishContent(
        String reason,
        TokenUsageContext tokenUsage
) {}
