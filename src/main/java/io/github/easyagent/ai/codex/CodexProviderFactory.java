package io.github.easyagent.ai.codex;

import io.github.easyagent.ai.AIProvider;
import io.github.easyagent.ai.provider.RetryConfig;

/**
 * Codex 提供者工厂。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class CodexProviderFactory {

    public static AIProvider create() {
        return new CodexCLIProvider();
    }

    public static AIProvider create(String commandPath) {
        return new CodexCLIProvider(commandPath);
    }

    public static AIProvider create(String commandPath, RetryConfig retryConfig) {
        return new CodexCLIProvider(commandPath, retryConfig);
    }
}
