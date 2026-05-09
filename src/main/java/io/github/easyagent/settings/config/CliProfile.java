package io.github.easyagent.settings.config;

import lombok.Builder;
import lombok.Data;

/**
 * CLI 命名配置档案。
 * <p>
 * 每个档案包含一个名称和对应 CLI 类型的配置数据。
 * 用于支持同一 CLI 类型的多套配置快速切换。
 * </p>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Data
@Builder
public class CliProfile {

    /** 档案唯一标识（UUID）。 */
    private String id;

    /** 档案显示名称。 */
    private String name;

    /** CLI 类型名称（CLAUDE / OPENCODE / CODEX）。 */
    private String cliType;

    /** Claude 配置（cliType 为 CLAUDE 时使用）。 */
    private ClaudeConfig claude;

    /** OpenCode 配置（cliType 为 OPENCODE 时使用）。 */
    private OpenCodeConfig opencode;

    /** Codex 配置（cliType 为 CODEX 时使用）。 */
    private CodexConfig codex;
}
