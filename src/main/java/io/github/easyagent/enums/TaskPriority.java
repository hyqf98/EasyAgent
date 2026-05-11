package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 计划任务优先级枚举。
 *
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Getter
public enum TaskPriority implements ValueEnum<String> {

    /** 高优先级。 */
    HIGH("HIGH"),

    /** 中优先级。 */
    MEDIUM("MEDIUM"),

    /** 低优先级。 */
    LOW("LOW");

    /** 优先级标识值。 */
    private final String value;

    TaskPriority(String value) {
        this.value = value;
    }
}
