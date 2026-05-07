package io.github.easyagent.ai.opencode.entity;

import com.google.gson.annotations.SerializedName;
import io.github.easyagent.ai.opencode.enums.OpenCodePartType;
import lombok.Builder;

/**
 * OpenCode 流式事件的 Part 数据。
 *
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
