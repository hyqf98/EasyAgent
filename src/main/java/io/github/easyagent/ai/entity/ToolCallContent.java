package io.github.easyagent.ai.entity;

import io.github.easyagent.enums.ToolCallStatus;
import lombok.Builder;

/**
 * 工具调用事件内容。
 * <p>
 * 当 AI 调用外部工具（如 bash、文件读写等）时携带的调用详情和执行结果。
 * 调用状态通过 {@link ToolCallStatus} 枚举表示，底层 CLI 各自适配转换。
 * </p>
 *
 * @param toolCallId 工具调用唯一标识
 * @param toolName 工具名称，如 "bash"、"read"
 * @param title   工具调用描述标题
 * @param status  工具调用状态
 * @param input   工具调用输入参数
 * @param output  工具执行输出结果
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ToolCallContent(
        String toolCallId,
        String toolName,
        String title,
        ToolCallStatus status,
        String input,
        String output
) {}
