package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

/**
 * 令牌使用统计信息。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record TokenUsage(
        long total,
        long input,
        long output,
        long reasoning,
        CacheInfo cache
) {}
