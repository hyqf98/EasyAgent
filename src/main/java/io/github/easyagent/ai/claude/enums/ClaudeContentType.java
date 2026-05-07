package io.github.easyagent.ai.claude.enums;

import lombok.Getter;

/**
 * Claude 消息内容块类型枚举。
 * <p>
 * 对应 ClaudeContentBlock 中 type 字段。
 * </p>
 *
 * @author haijun
 * @email "mailto:haijun@email.com"
 * @date 2026/4/30
 * @version 1.0.0
 * @since 1.0.0
 */
@Getter
public enum ClaudeContentType {

    /** 思考/推理内容。 */
    THINKING,
    /** 文本内容。 */
    TEXT,
    /** 工具调用。 */
    TOOL_USE,
    /** 工具结果。 */
    TOOL_RESULT
}
