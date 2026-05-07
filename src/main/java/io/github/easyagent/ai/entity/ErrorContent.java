package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * 错误事件内容。
 * <p>
 * 当 AI 交互过程中发生错误时携带的错误信息。
 * </p>
 *
 * @param message 错误消息
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ErrorContent(
        String message
) {}
