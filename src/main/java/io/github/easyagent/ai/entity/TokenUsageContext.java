package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * 令牌使用上下文。
 * <p>
 * 表示一次 AI 交互中的令牌消耗统计，包括输入、输出、推理令牌数量。
 * </p>
 *
 * @param total      令牌总消耗数量
 * @param input      输入令牌数量
 * @param output     输出令牌数量
 * @param reasoning  推理令牌数量
 * @param cacheWrite 缓存写入的令牌数量
 * @param cacheRead  缓存读取的令牌数量
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record TokenUsageContext(
        long total,
        long input,
        long output,
        long reasoning,
        long cacheWrite,
        long cacheRead
) {}
