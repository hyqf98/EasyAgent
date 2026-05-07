package io.github.easyagent.ai.provider;

import lombok.Builder;

/**
 * CLI 调用重试配置。
 * <p>
 * 配置 CLI 进程执行的重试策略，包括最大重试次数和超时时间。
 * 默认不重试，仅在显式配置后生效。
 * </p>
 *
 * @param maxRetries 最大重试次数（不含首次执行）
 * @param timeoutMs  单次执行超时时间（毫秒），0 表示不超时
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record RetryConfig(
        int maxRetries,
        long timeoutMs
) {

    /** 默认不重试的配置实例。 */
    public static final RetryConfig NO_RETRY = new RetryConfig(0, 0);

    /**
     * 判断重试是否启用。
     *
     * @return 最大重试次数大于 0 时返回 true
     */
    public boolean isEnabled() {
        return maxRetries > 0;
    }
}
