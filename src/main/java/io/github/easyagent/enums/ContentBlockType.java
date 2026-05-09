package io.github.easyagent.enums;

import lombok.Getter;

/**
 * 会话内容块类型枚举。
 * <p>
 * 定义会话消息中内容块的统一类型，覆盖三种 CLI 的所有内容格式。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum ContentBlockType implements ValueEnum<String> {

    /** 普通文本内容。 */
    TEXT("text"),

    /** 思考/推理内容。 */
    THINKING("thinking"),

    /** 工具调用请求。 */
    TOOL_USE("tool_use"),

    /** 工具执行结果。 */
    TOOL_RESULT("tool_result"),

    /** 推理步骤开始。 */
    STEP_START("step_start"),

    /** 推理步骤结束。 */
    STEP_FINISH("step_finish"),

    /** 中间评论/更新（Codex commentary channel）。 */
    COMMENTARY("commentary"),

    /** 最终回答（Codex final channel）。 */
    FINAL("final");

    /** 类型标识值。 */
    private final String value;

    ContentBlockType(String value) {
        this.value = value;
    }
}
