package io.github.easyagent.ui.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * 斜杠命令来源类型枚举。
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Getter
public enum SlashCommandSourceType implements ValueEnum<String> {

    /** CLI 内建命令。 */
    BUILTIN("BUILTIN"),

    /** 用户或插件定义的 command。 */
    COMMAND("COMMAND"),

    /** 用户或插件定义的 skill。 */
    SKILL("SKILL"),

    /** 插件提供的命令。 */
    PLUGIN("PLUGIN"),

    /** MCP 暴露的 prompt。 */
    MCP("MCP"),

    /** 其他自定义命令。 */
    CUSTOM("CUSTOM");

    /** 来源类型值。 */
    private final String value;

    SlashCommandSourceType(String value) {
        this.value = value;
    }
}
