package io.github.easyagent.ai.claude.entity;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Claude 结果事件令牌使用统计。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ClaudeResultUsage extends ClaudeUsage {

    @SerializedName("service_tier")
    private String serviceTier;
}
