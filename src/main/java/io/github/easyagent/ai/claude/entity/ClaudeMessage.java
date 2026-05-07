package io.github.easyagent.ai.claude.entity;

import io.github.easyagent.ai.claude.enums.ClaudeMessageType;
import lombok.Builder;

import java.util.List;

/**
 * Claude 响应消息对象。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ClaudeMessage(
        ClaudeMessageType type,
        String model,
        ClaudeUsage usage,
        String role,
        String id,
        List<ClaudeContentBlock> content
) {}
