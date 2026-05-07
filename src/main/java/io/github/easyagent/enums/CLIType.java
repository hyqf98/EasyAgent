package io.github.easyagent.enums;

import lombok.Getter;

/**
 * CLI 类型枚举。
 * <p>
 * 定义系统支持的 CLI 工具类型，每种类型包含名称和默认可执行命令路径。
 * 统一供 AI 模块和 Session 模块共用。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum CLIType {

    /** OpenCode CLI 工具。 */
    OPENCODE("opencode-cli", "opencode"),

    /** Claude Code CLI 工具。 */
    CLAUDE("claude-cli", "claude"),

    /** Codex CLI 工具。 */
    CODEX("codex-cli", "codex");

    /** CLI 类型名称。 */
    private final String name;

    /** CLI 默认可执行命令路径。 */
    private final String commandPath;

    CLIType(String name, String commandPath) {
        this.name = name;
        this.commandPath = commandPath;
    }
}
