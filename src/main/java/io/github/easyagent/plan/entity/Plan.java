package io.github.easyagent.plan.entity;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.PlanStatus;
import lombok.Builder;

/**
 * 计划实体。
 * <p>
 * 表示一个项目级别的开发计划，包含需求描述、绑定的 CLI 类型和会话信息。
 * 计划按项目路径绑定，持久化到项目级配置文件中。
 * </p>
 *
 * @param planId       计划唯一标识
 * @param projectId    关联项目路径（项目根目录）
 * @param planName     计划名称
 * @param description  计划描述
 * @param cliType      绑定的 CLI 类型
 * @param sessionId    需求收集阶段的原始会话 ID
 * @param minTaskCount 最小任务拆分数量
 * @param status       计划状态
 * @param createdAt    创建时间（毫秒时间戳）
 * @param updatedAt    更新时间（毫秒时间戳）
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Builder
public record Plan(
        String planId,
        String projectId,
        String planName,
        String description,
        CLIType cliType,
        String sessionId,
        int minTaskCount,
        PlanStatus status,
        Long createdAt,
        Long updatedAt
) {}
