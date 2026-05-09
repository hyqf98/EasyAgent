package io.github.easyagent.ai.opencode.entity;

import com.google.gson.annotations.JsonAdapter;
import io.github.easyagent.util.RawJsonStringAdapter;
import lombok.Builder;

/**
 * 工具执行状态。
 *
 * @param status    执行状态
 * @param input     工具输入参数 JSON
 * @param output    工具输出内容
 * @param metadata  工具执行元数据
 * @param title     工具标题
 * @param time      时间范围信息
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
