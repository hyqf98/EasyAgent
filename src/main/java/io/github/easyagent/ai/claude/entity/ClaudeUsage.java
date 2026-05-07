package io.github.easyagent.ai.claude.entity;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Claude 令牌使用统计基类。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ClaudeUsage {

    @SerializedName("input_tokens")
    private long inputTokens;

    @SerializedName("cache_creation_input_tokens")
    private long cacheCreationInputTokens;

    @SerializedName("cache_read_input_tokens")
    private long cacheReadInputTokens;

    @SerializedName("output_tokens")
    private long outputTokens;
}
