package io.github.easyagent.session.entity;

import io.github.easyagent.enums.CLIType;
import lombok.Builder;

/**
 * CLI 会话摘要信息。
 * <p>
 * 表示一个 CLI 会话的基本元信息，包括会话 ID、所属 CLI 类型、
 * 关联的项目路径、标题、模型、Git 分支以及时间统计。
 * </p>
 *
 * @param sessionId   会话唯一标识
 * @param cliType     CLI 类型
 * @param projectPath 会话关联的项目路径
 * @param title       会话标题，通常取自用户第一条消息
 * @param model       使用的模型名称
 * @param gitBranch   Git 分支名称
 * @param createdAt   会话创建时间（毫秒时间戳）
 * @param updatedAt   会话最后更新时间（毫秒时间戳）
 * @param messageCount 会话中的消息数量
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record SessionInfo(
        String sessionId,
        CLIType cliType,
        String projectPath,
        String title,
        String model,
        String gitBranch,
        Long createdAt,
        Long updatedAt,
        Integer messageCount
) {}
