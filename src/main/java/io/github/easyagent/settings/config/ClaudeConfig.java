package io.github.easyagent.settings.config;

import lombok.Builder;

/**
 * Claude Code CLI 配置。
 * <p>
 * Claude Code 通过环境变量配置 API 连接信息：
 * <ul>
 *   <li>{@code ANTHROPIC_BASE_URL} - API 基础 URL</li>
 *   <li>{@code ANTHROPIC_API_KEY} - API 密钥</li>
 *   <li>{@code ANTHROPIC_AUTH_TOKEN} - 自定义授权令牌（可选）</li>
 *   <li>{@code ANTHROPIC_MODEL} - 默认模型 ID</li>
 * </ul>
 * 这些环境变量写入用户的 shell profile 文件（{@code ~/.zshrc} 或 {@code ~/.bashrc}）。
 * </p>
 *
 * @param baseUrl   API 基础 URL，对应 {@code ANTHROPIC_BASE_URL}
 * @param apiKey    API 密钥，对应 {@code ANTHROPIC_API_KEY}
 * @param authToken 自定义授权令牌，对应 {@code ANTHROPIC_AUTH_TOKEN}
 * @param model     默认模型 ID，对应 {@code ANTHROPIC_MODEL}
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Builder
public record ClaudeConfig(String baseUrl, String apiKey, String authToken, String model) {

    /**
     * 创建空配置。
     *
     * @return 所有字段为空的配置
     */
    public static ClaudeConfig empty() {
        return new ClaudeConfig("", "", "", "");
    }
}
