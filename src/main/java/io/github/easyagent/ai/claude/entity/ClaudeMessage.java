package io.github.easyagent.ai.claude.entity;

import io.github.easyagent.ai.claude.enums.ClaudeMessageType;
import lombok.Builder;

import java.util.List;

/**
 * Claude 响应消息对象。
 *
 * @param type    消息类型
 * @param model   模型名称
 * @param usage   令牌使用统计
 * @param role    角色
 * @param id      消息 ID
 * @param content 内容块列表
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
