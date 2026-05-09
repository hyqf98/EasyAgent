package io.github.easyagent.settings.config;

import lombok.Builder;

/**
 * OpenAI Codex CLI 配置。
 * <p>
 * Codex 使用 {@code ~/.codex/config.toml} 作为配置文件，API Key 通过环境变量 {@code OPENAI_API_KEY} 配置。
 * 配置文件示例：
 * <pre>
 * model = "gpt-5"
 * [model_providers.custom]
 * name = "custom"
 * base_url = "https://api.example.com/v1"
 * </pre>
 * </p>
 *
 * @param apiKey  API 密钥，对应 {@code OPENAI_API_KEY} 环境变量
 * @param baseUrl API 基础 URL（可选），写入 config.toml 的 model_providers
 * @param model   默认模型 ID，写入 config.toml 的 model 字段
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Builder
public record CodexConfig(String apiKey, String baseUrl, String model) {

    /**
     * 创建空配置。
     *
     * @return 所有字段为空的配置
     */
    public static CodexConfig empty() {
        return new CodexConfig("", "", "");
    }
}
