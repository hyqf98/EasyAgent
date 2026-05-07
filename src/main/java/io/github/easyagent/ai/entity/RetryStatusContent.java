package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * CLI 调用重试状态内容。
 * <p>
 * 当 CLI 进程执行失败并触发重试时，通过此对象通知前端当前重试进度。
 * </p>
 *
 * @param currentAttempt 当前尝试次数（从 1 开始）
 * @param maxAttempts    最大尝试次数
 * @param reason         重试原因（上次失败的错误消息）
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Builder
public record RetryStatusContent(
        int currentAttempt,
        int maxAttempts,
        String reason
) {}
