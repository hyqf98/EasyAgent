package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

import java.util.Map;

/**
 * 工具执行状态。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ToolState(
        String status,
        Map<String, Object> input,
        String output,
        ToolMetadata metadata,
        String title,
        TimeInfo time
) {}
