package io.github.easyagent.ui.service.entity;

import lombok.Builder;

/**
 * 斜杠命令执行结果载荷。
 *
 * @param requestId         请求 ID
 * @param cliType           CLI 类型
 * @param commandName       命令名
 * @param executionType     执行方式
 * @param prompt            展开后的提示词
 * @param openFreshSession  是否需要打开新会话
 * @param refreshHistory    是否需要刷新历史
 * @param toastMessage      需要提示给用户的消息
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record SlashCommandExecutionPayload(
        String requestId,
        String cliType,
        String commandName,
        String executionType,
        String prompt,
        Boolean openFreshSession,
        Boolean refreshHistory,
        String toastMessage,
        Boolean planMode
) {}
