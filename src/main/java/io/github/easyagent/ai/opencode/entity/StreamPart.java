package io.github.easyagent.ai.opencode.entity;

import com.google.gson.annotations.SerializedName;
import io.github.easyagent.ai.opencode.enums.OpenCodePartType;
import lombok.Builder;

/**
 * OpenCode 流式事件的 Part 数据。
 *
 * @param id        消息 ID
 * @param messageId 消息唯一标识
 * @param sessionId 会话 ID
 * @param type      Part 类型
 * @param text      文本内容
 * @param time      时间范围信息
 * @param reason    原因描述
 * @param tokens    令牌使用统计
 * @param cost      消耗成本
 * @param tool      工具名称
 * @param callId    工具调用 ID
 * @param state     工具执行状态
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record StreamPart(
        String id,
        @SerializedName("messageID") String messageId,
        @SerializedName("sessionID") String sessionId,
        OpenCodePartType type,
        String text,
        TimeInfo time,
        String reason,
        TokenUsage tokens,
        Number cost,
        String tool,
        @SerializedName("callID") String callId,
        ToolState state
) {}
