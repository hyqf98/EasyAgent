package io.github.easyagent.ai.codex.entity;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;

/**
 * Codex 令牌使用统计。
 *
 * @param inputTokens           输入令牌数
 * @param cachedInputTokens     缓存输入令牌数
 * @param outputTokens          输出令牌数
 * @param reasoningOutputTokens 推理输出令牌数
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record CodexUsage(
        @SerializedName("input_tokens") long inputTokens,
        @SerializedName("cached_input_tokens") long cachedInputTokens,
        @SerializedName("output_tokens") long outputTokens,
        @SerializedName("reasoning_output_tokens") long reasoningOutputTokens
) {}
