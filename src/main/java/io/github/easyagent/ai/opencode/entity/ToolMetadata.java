package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

/**
 * 工具执行元数据。
 *
 * @param output      工具输出内容
 * @param exit        退出码
 * @param description 描述信息
 * @param truncated   输出是否被截断
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
