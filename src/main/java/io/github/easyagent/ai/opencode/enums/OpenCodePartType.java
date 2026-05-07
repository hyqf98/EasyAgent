package io.github.easyagent.ai.opencode.enums;

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
public enum OpenCodePartType {

    /** 步骤开始。 */
    STEP_START,
    /** 文本输出。 */
    TEXT,
    /** AI 思考/推理。 */
    REASONING,
    /** 工具调用。 */
    TOOL,
    /** 步骤结束。 */
    STEP_FINISH
}
