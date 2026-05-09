package io.github.easyagent.ai.claude.entity;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Claude 令牌使用统计基类。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ClaudeUsage {

    /** 输入令牌数。 */
    @SerializedName("input_tokens")
    private long inputTokens;

    /** 缓存创建输入令牌数。 */
    @SerializedName("cache_creation_input_tokens")
    private long cacheCreationInputTokens;

    /** 缓存读取输入令牌数。 */
    @SerializedName("cache_read_input_tokens")
    private long cacheReadInputTokens;

    /** 输出令牌数。 */
    @SerializedName("output_tokens")
    private long outputTokens;
}
