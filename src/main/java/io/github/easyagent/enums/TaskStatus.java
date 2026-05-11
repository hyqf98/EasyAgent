package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 计划任务状态枚举。
 *
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Getter
public enum TaskStatus implements ValueEnum<String> {

    /** 待执行。 */
    PENDING("PENDING"),

    /** 执行中。 */
    RUNNING("RUNNING"),

    /** 已完成。 */
    COMPLETED("COMPLETED"),

    /** 执行失败。 */
    FAILED("FAILED"),

    /** 已停止（可继续执行）。 */
    STOPPED("STOPPED");

    /** 状态标识值。 */
    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }
}
