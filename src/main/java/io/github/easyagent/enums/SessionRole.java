package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 会话消息角色枚举。
 * <p>
 * 定义会话消息中可能的发送者角色类型，覆盖三种 CLI 的消息角色。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum SessionRole implements ValueEnum<String> {

    /** 用户消息。 */
    USER("user"),

    /** 助手消息。 */
    ASSISTANT("assistant"),

    /** 系统消息。 */
    SYSTEM("system"),

    /** 开发者指令消息（Codex）。 */
    DEVELOPER("developer"),

    /** 工具执行结果消息。 */
    TOOL_RESULT("tool_result");

    /** 角色标识值。 */
    private final String value;

    SessionRole(String value) {
        this.value = value;
    }
}
