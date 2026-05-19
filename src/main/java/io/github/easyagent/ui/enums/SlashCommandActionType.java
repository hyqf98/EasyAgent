package io.github.easyagent.ui.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * 斜杠命令执行方式枚举。
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Getter
public enum SlashCommandActionType implements ValueEnum<String> {

    /** 打开当前 CLI 的新会话。 */
    OPEN_NEW_SESSION("OPEN_NEW_SESSION"),

    /** 将命令展开为提示词并发送给 CLI。 */
    SEND_PROMPT("SEND_PROMPT"),

    /** 直接透传给 CLI。 */
    PASS_THROUGH("PASS_THROUGH"),

    /** 切换计划模式（只读）。 */
    TOGGLE_PLAN_MODE("TOGGLE_PLAN_MODE");

    /** 执行方式值。 */
    private final String value;

    SlashCommandActionType(String value) {
        this.value = value;
    }
}
