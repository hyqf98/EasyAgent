package io.github.easyagent.ai.claude;

import io.github.easyagent.ai.AIProvider;
import io.github.easyagent.ai.provider.RetryConfig;

/**
 * Claude 提供者工厂。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class ClaudeProviderFactory {

    public static AIProvider create() {
        return new ClaudeCLIProvider();
    }

    public static AIProvider create(String commandPath) {
        return new ClaudeCLIProvider(commandPath);
    }

    public static AIProvider create(String commandPath, RetryConfig retryConfig) {
        return new ClaudeCLIProvider(commandPath, retryConfig);
    }
}
