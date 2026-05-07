package io.github.easyagent.ai.opencode;

import io.github.easyagent.ai.AIProvider;
import io.github.easyagent.ai.provider.RetryConfig;

/**
 * OpenCode 提供者工厂。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class OpenCodeProviderFactory {

    public static AIProvider create() {
        return new OpenCodeCLIProvider();
    }

    public static AIProvider create(String commandPath) {
        return new OpenCodeCLIProvider(commandPath);
    }

    public static AIProvider create(String commandPath, RetryConfig retryConfig) {
        return new OpenCodeCLIProvider(commandPath, retryConfig);
    }
}
