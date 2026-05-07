package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 工具调用状态枚举。
 * <p>
 * 表示 AI 调用外部工具（如 bash、文件读写等）时的调用进度状态。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ToolCallStatus implements ValueEnum<String> {

    /** 调用中。 */
    CALLING("calling"),

    /** 调用完成（成功）。 */
    COMPLETED("completed"),

    /** 调用失败。 */
    FAILED("failed");

    /** 状态标识值。 */
    private final String value;

    ToolCallStatus(String value) {
        this.value = value;
    }

    /**
     * 根据字符串值解析工具调用状态枚举。
     *
     * @param value 状态字符串
     * @return 对应的工具调用状态枚举，无法匹配时返回 {@code null}
     */
    public static ToolCallStatus fromValue(String value) {
        return ValueEnum.fromValue(ToolCallStatus.class, value);
    }
}
