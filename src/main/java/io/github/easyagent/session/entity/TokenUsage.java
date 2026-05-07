package io.github.easyagent.session.entity;

import lombok.Builder;

/**
 * 令牌使用统计。
 * <p>
 * 记录一次 AI 交互中的令牌消耗明细，包括输入、输出、缓存和推理令牌数量。
 * 不同 CLI 的令牌统计字段会被统一映射到此结构。
 * </p>
 *
 * @param inputTokens              输入令牌数量
 * @param outputTokens             输出令牌数量
 * @param cacheCreationInputTokens 缓存创建的输入令牌数量
 * @param cacheReadInputTokens     缓存读取的输入令牌数量
 * @param reasoningTokens          推理令牌数量
 * @param totalTokens              令牌总消耗数量
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record TokenUsage(
        Integer inputTokens,
        Integer outputTokens,
        Integer cacheCreationInputTokens,
        Integer cacheReadInputTokens,
        Integer reasoningTokens,
        Integer totalTokens
) {}
