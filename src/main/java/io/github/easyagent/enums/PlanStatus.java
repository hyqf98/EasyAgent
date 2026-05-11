package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 计划状态枚举。
 * <p>
 * 表示计划在其生命周期中的当前阶段。
 * </p>
 *
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Getter
public enum PlanStatus implements ValueEnum<String> {

    /** 草稿（需求收集中）。 */
    DRAFT("DRAFT"),

    /** 任务拆分中。 */
    TASK_SPLITTING("TASK_SPLITTING"),

    /** 看板模式（任务执行中）。 */
    KANBAN("KANBAN"),

    /** 计划已完成。 */
    COMPLETED("COMPLETED");

    /** 状态标识值。 */
    private final String value;

    PlanStatus(String value) {
        this.value = value;
    }
}
