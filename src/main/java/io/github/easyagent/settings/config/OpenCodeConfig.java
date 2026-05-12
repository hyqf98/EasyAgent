package io.github.easyagent.settings.config;

import lombok.Builder;

/**
 * OpenCode CLI 配置。
 * <p>
 * OpenCode 使用 {@code ~/.config/opencode/opencode.json} 作为配置文件。
 * Provider 和 Model 的配置格式如下：
 * <pre>
 * {
 *   "provider": {
 *     "anthropic": {
 *       "options": { "apiKey": "..." }
 *     }
 *   },
 *   "model": "anthropic/claude-sonnet-4-5"
 * }
 * </pre>
 * Model ID 格式为 {@code provider_id/model_id}，如 {@code anthropic/claude-sonnet-4-5}。
 * </p>
 *
 * @param providerId  Provider 标识，如 anthropic、openai，或用户自定义的任意值
 * @param apiKey      API 密钥，写入 provider.{id}.options.apiKey
 * @param baseUrl     API 基础 URL（可选），写入 provider.{id}.options.baseURL
 * @param model       模型 ID，格式为 provider/model
 * @param commandPath CLI 可执行文件路径（可选），为空时使用自动检测或默认路径
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Builder
public record OpenCodeConfig(String providerId, String apiKey,
                              String baseUrl, String model,
                              String commandPath) {

    /**
     * 创建空配置。
     *
     * @return 所有字段为空的配置
     */
    public static OpenCodeConfig empty() {
        return new OpenCodeConfig("", "", "", "", "");
    }
}
