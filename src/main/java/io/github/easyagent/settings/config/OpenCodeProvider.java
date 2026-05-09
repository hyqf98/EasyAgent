package io.github.easyagent.settings.config;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OpenCode 内置 Provider 定义。
 * <p>
 * 提供 OpenCode 支持的常用 LLM Provider 列表，
 * 用于前端配置管理页面的下拉选择。
 * </p>
 *
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Getter
@AllArgsConstructor
public enum OpenCodeProvider {

    ANTHROPIC("anthropic", "Anthropic"),
    OPENAI("openai", "OpenAI"),
    GOOGLE("google", "Google"),
    AZURE("azure", "Azure OpenAI"),
    AMAZON_BEDROCK("amazon-bedrock", "Amazon Bedrock"),
    DEEPSEEK("deepseek", "DeepSeek"),
    GROQ("groq", "Groq"),
    MISTRAL("mistral", "Mistral"),
    TOGETHER("together", "Together AI"),
    FIREWORKS("fireworks", "Fireworks AI"),
    OPENROUTER("openrouter", "OpenRouter"),
    OLLAMA("ollama", "Ollama"),
    LMSTUDIO("lmstudio", "LM Studio"),
    CUSTOM("custom", "Custom");

    /** Provider 标识 ID。 */
    private final String id;

    /** Provider 显示名称。 */
    private final String displayName;

    /**
     * 根据 ID 解析 Provider 枚举。
     *
     * @param id Provider ID
     * @return 对应的枚举值，未找到返回 {@code null}
     */
    public static OpenCodeProvider fromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (OpenCodeProvider p : values()) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
