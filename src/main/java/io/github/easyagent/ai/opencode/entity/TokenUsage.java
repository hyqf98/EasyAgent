package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

/**
 * 令牌使用统计信息。
 *
 * @param total     总令牌数
 * @param input     输入令牌数
 * @param output    输出令牌数
 * @param reasoning 推理令牌数
 * @param cache     缓存命中信息
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
