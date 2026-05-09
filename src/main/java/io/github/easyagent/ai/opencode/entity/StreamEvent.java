package io.github.easyagent.ai.opencode.entity;

import com.google.gson.annotations.SerializedName;
import io.github.easyagent.ai.opencode.enums.OpenCodeEventType;
import lombok.Builder;

/**
 * OpenCode 流式事件。
 *
 * @param <T>       Part 数据的具体类型
 * @param type      事件类型
 * @param timestamp 时间戳
 * @param sessionId 会话 ID
 * @param part      事件数据
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record StreamEvent<T>(
        OpenCodeEventType type,
        long timestamp,
        @SerializedName("sessionID") String sessionId,
        T part
) {}
