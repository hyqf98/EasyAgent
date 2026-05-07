package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

/**
 * 工具执行元数据。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ToolMetadata(
        String output,
        int exit,
        String description,
        boolean truncated
) {}
