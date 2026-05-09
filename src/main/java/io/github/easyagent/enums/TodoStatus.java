package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 待办任务状态枚举。
 * <p>
 * 表示 AI 在交互过程中创建或管理的待办任务状态。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum TodoStatus implements ValueEnum<String> {

    /** 待处理。 */
    PENDING("pending"),

    /** 进行中。 */
    IN_PROGRESS("in_progress"),

    /** 已完成。 */
    COMPLETED("completed"),

    /** 已取消。 */
    CANCELLED("cancelled");

    /** 状态标识值。 */
    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }
}
