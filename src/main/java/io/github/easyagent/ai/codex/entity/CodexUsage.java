package io.github.easyagent.ai.codex.entity;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;

/**
 * Codex 令牌使用统计。
 *
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
