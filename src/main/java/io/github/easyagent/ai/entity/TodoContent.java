package io.github.easyagent.ai.entity;

import io.github.easyagent.enums.TodoStatus;
import lombok.Builder;

/**
 * 待办任务内容。
 * <p>
 * 表示 AI 在交互过程中创建或管理的待办任务。
 * 各 CLI 实现将底层任务实体统一转换为此结构。
 * </p>
 *
 * @param id          待办 ID
 * @param title       待办标题
 * @param description 待办描述
 * @param status      待办状态
 * @param priority    优先级（可选）
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record TodoContent(
        String id,
        String title,
        String description,
        TodoStatus status,
        Integer priority
) {}
