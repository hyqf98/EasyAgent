package io.github.easyagent.settings.config;

/**
 * CLI 配置数据集合。
 * <p>
 * 包含 Claude Code、OpenCode、Codex 三个 CLI 工具的配置信息，
 * 用于前后端通信传递配置数据。
 * </p>
 *
 * @param claude   Claude Code 配置
 * @param opencode OpenCode 配置
 * @param codex    Codex 配置
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
public record CliConfigs(ClaudeConfig claude, OpenCodeConfig opencode, CodexConfig codex) {

    /**
     * 创建空配置。
     *
     * @return 所有字段为空的配置集合
     */
    public static CliConfigs empty() {
        return new CliConfigs(ClaudeConfig.empty(), OpenCodeConfig.empty(), CodexConfig.empty());
    }
}
