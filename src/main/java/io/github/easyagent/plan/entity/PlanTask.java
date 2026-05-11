package io.github.easyagent.plan.entity;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.TaskPriority;
import io.github.easyagent.enums.TaskStatus;
import lombok.Builder;

/**
 * 计划任务实体。
 * <p>
 * 表示计划中的一个可执行任务单元，支持独立的 CLI 类型和模型配置。
 * 任务在看板中以卡片形式展示，支持拖拽切换状态。
 * </p>
 *
 * @param taskId           任务唯一标识
 * @param planId           关联的计划 ID
 * @param title            任务标题
 * @param description      任务描述
 * @param priority         任务优先级
 * @param status           任务状态
 * @param cliType          执行 CLI 类型（可覆盖计划级别）
 * @param modelId          执行模型 ID（可覆盖计划级别）
 * @param executeSessionId 执行时创建的会话 ID
 * @param executePrompt    执行提示词
 * @param sortOrder        排序序号
 * @param startedAt        开始执行时间戳（毫秒），RUNNING 时设置
 * @param completedAt      完成时间戳（毫秒），COMPLETED/FAILED 时设置
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Builder
public record PlanTask(
        String taskId,
        String planId,
        String title,
        String description,
        TaskPriority priority,
        TaskStatus status,
        CLIType cliType,
        String modelId,
        String executeSessionId,
        String executePrompt,
        int sortOrder,
        Long startedAt,
        Long completedAt
) {}
