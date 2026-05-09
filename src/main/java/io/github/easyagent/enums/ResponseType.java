package io.github.easyagent.enums;

import lombok.Getter;

/**
 * AI 响应类型枚举。
 * <p>
 * 定义所有 CLI 实现产生的统一响应事件类型，
 * 根据 {@link ResponseType} 不同，AIResponse 中对应的 content 字段会被填充。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ResponseType implements ValueEnum<String> {

    /** 推理步骤开始。 */
    STEP_START("step_start"),

    /** AI 消息（思考或文本）。 */
    MESSAGE("message"),

    /** 工具调用。 */
    TOOL_USE("tool_use"),

    /** 推理步骤结束。 */
    STEP_FINISH("step_finish"),

    /** 上下文压缩/摘要。 */
    COMPACT("compact"),

    /** 错误。 */
    ERROR("error"),

    /** 重试状态通知。 */
    RETRY_STATUS("retry_status");

    /** 类型标识值。 */
    private final String value;

    ResponseType(String value) {
        this.value = value;
    }
}
