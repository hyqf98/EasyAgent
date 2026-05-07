package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * 步骤开始事件内容。
 * <p>
 * 当 AI 开始一个新的推理步骤时携带的内容。
 * </p>
 *
 * @param messageId 消息 ID
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record StepStartContent(
        String messageId
) {}
