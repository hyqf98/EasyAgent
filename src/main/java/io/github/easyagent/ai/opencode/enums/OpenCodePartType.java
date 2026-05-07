package io.github.easyagent.ai.opencode.enums;

import lombok.Getter;

/**
 * OpenCode StreamPart 类型枚举。
 * <p>
 * 对应 StreamPart 中 type 字段。
 * </p>
 *
 * @author haijun
 * @email "mailto:haijun@email.com"
 * @date 2026/4/30
 * @version 1.0.0
 * @since 1.0.0
 */
@Getter
public enum OpenCodePartType {

    /** 步骤开始。 */
    STEP_START,
    /** 文本输出。 */
    TEXT,
    /** 工具调用。 */
    TOOL,
    /** 步骤结束。 */
    STEP_FINISH
}
