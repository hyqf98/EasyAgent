package io.github.easyagent.ai.opencode.entity;

import com.google.gson.annotations.JsonAdapter;
import io.github.easyagent.util.RawJsonStringAdapter;
import lombok.Builder;

/**
 * 工具执行状态。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ToolState(
        String status,
        @JsonAdapter(RawJsonStringAdapter.class) String input,
        String output,
        ToolMetadata metadata,
        String title,
        TimeInfo time
) {}
