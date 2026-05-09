package io.github.easyagent.ai.claude.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * Claude 消息内容块类型枚举。
 * <p>
 * 对应 ClaudeContentBlock 中 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ClaudeContentType implements ValueEnum<String> {

    /** 思考/推理内容。 */
    THINKING("thinking"),
    /** 文本内容。 */
    TEXT("text"),
    /** 工具调用。 */
    TOOL_USE("tool_use"),
    /** 工具结果。 */
    TOOL_RESULT("tool_result");

    /** 类型标识值。 */
    private final String value;

    ClaudeContentType(String value) {
        this.value = value;
    }
}
