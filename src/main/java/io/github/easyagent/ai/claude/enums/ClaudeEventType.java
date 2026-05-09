package io.github.easyagent.ai.claude.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * Claude 流式事件类型枚举。
 * <p>
 * 对应 Claude stream-json 顶层 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ClaudeEventType implements ValueEnum<String> {

    /** 系统初始化。 */
    SYSTEM("system"),
    /** 助手响应。 */
    ASSISTANT("assistant"),
    /** 用户消息（包含工具执行结果）。 */
    USER("user"),
    /** 最终结果。 */
    RESULT("result");

    /** 类型标识值。 */
    private final String value;

    ClaudeEventType(String value) {
        this.value = value;
    }
}
