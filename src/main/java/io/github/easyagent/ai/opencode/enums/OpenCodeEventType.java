package io.github.easyagent.ai.opencode.enums;

import lombok.Getter;

/**
 * OpenCode 流式事件类型枚举。
 * <p>
 * 对应 StreamEvent 顶层 type 字段。
 * </p>
 *
 * @author haijun
 * @email "mailto:haijun@email.com"
 * @date 2026/4/30
 * @version 1.0.0
 * @since 1.0.0
 */
@Getter
public enum OpenCodeEventType {

    /** 推理步骤开始。 */
    STEP_START,
    /** AI 文本输出。 */
    TEXT,
    /** AI 思考/推理。 */
    REASONING,
    /** 工具调用。 */
    TOOL_USE,
    /** 推理步骤结束。 */
    STEP_FINISH
}
