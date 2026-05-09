package io.github.easyagent.ai.opencode.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * OpenCode StreamPart 类型枚举。
 * <p>
 * 对应 StreamPart 中 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum OpenCodePartType implements ValueEnum<String> {

    /** 步骤开始。 */
    STEP_START("step_start"),
    /** 文本输出。 */
    TEXT("text"),
    /** AI 思考/推理。 */
    REASONING("reasoning"),
    /** 工具调用。 */
    TOOL("tool"),
    /** 步骤结束。 */
    STEP_FINISH("step_finish");

    /** 类型标识值。 */
    private final String value;

    OpenCodePartType(String value) {
        this.value = value;
    }
}
