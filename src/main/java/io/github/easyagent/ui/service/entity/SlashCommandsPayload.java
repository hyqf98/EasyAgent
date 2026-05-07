package io.github.easyagent.ui.service.entity;

import lombok.Builder;

import java.util.List;

/**
 * 斜杠命令列表回调载荷。
 *
 * @param requestId 请求 ID
 * @param cliType   CLI 类型
 * @param commands  命令列表
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record SlashCommandsPayload(
        String requestId,
        String cliType,
        List<SlashCommandPayload> commands
) {}
