package io.github.easyagent.ai.codex.enums;

import lombok.Getter;

/**
 * Codex 消息项类型枚举。
 * <p>
 * 对应 CodexItem 中 type 字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum CodexItemType {

    /** Agent 消息。 */
    AGENT_MESSAGE,
    /** 工具调用。 */
    TOOL_CALL
}
