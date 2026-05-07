package io.github.easyagent.session.entity;

import io.github.easyagent.enums.SessionRole;
import lombok.Builder;

import java.util.List;

/**
 * CLI 会话消息。
 * <p>
 * 表示会话中的一条消息，包含角色、内容块列表、模型信息和令牌使用统计。
 * 适用于 Claude（JSONL）、Codex（JSONL）和 OpenCode（SQLite）三种数据源。
 * </p>
 *
 * @param uuid       消息唯一标识
 * @param parentUuid 父消息 UUID，用于构建消息树
 * @param sessionId  所属会话 ID
 * @param role       消息角色
 * @param model      使用的模型名称
 * @param cwd        会话执行的工作目录
 * @param timestamp  消息时间戳（毫秒）
 * @param contents   消息内容块列表
 * @param tokenUsage 令牌使用统计
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record SessionMessage(
        String uuid,
        String parentUuid,
        String sessionId,
        SessionRole role,
        String model,
        String cwd,
        Long timestamp,
        List<ContentBlock> contents,
        TokenUsage tokenUsage
) {}
