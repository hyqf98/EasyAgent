package io.github.easyagent.ai.claude.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * Claude 消息类型枚举。
 * <p>
 * 对应 ClaudeMessage 中 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ClaudeMessageType implements ValueEnum<String> {

    /** 消息。 */
    MESSAGE("message");

    /** 类型标识值。 */
    private final String value;

    ClaudeMessageType(String value) {
        this.value = value;
    }
}
