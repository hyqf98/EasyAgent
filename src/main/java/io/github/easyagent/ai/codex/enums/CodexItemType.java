package io.github.easyagent.ai.codex.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * Codex 消息项类型枚举。
 * <p>
 * 对应 CodexItem 中 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum CodexItemType implements ValueEnum<String> {

    /** Agent 消息。 */
    AGENT_MESSAGE("agent_message"),
    /** 工具调用。 */
    TOOL_CALL("tool_call");

    /** 类型标识值。 */
    private final String value;

    CodexItemType(String value) {
        this.value = value;
    }
}
