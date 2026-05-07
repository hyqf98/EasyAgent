package io.github.easyagent.session.entity;

import io.github.easyagent.enums.ContentBlockType;
import lombok.Builder;

/**
 * 会话消息内容块。
 * <p>
 * 表示消息中的单个内容单元，可以是文本、思考推理、工具调用、工具结果等。
 * 不同 CLI 的原始内容格式被统一映射到此结构。
 * </p>
 *
 * @param type       内容块类型
 * @param text       文本内容（TEXT、STEP_FINISH 等类型使用）
 * @param toolUseId  工具调用 ID（TOOL_USE、TOOL_RESULT 类型使用）
 * @param toolName   工具名称（TOOL_USE 类型使用）
 * @param toolInput  工具调用输入参数的 JSON 字符串（TOOL_USE 类型使用）
 * @param toolOutput 工具执行输出结果（TOOL_RESULT 类型使用）
 * @param isError    工具执行是否出错（TOOL_RESULT 类型使用）
 * @param thinking   思考/推理内容（THINKING 类型使用）
 * @param durationMs 执行耗时（毫秒）
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record ContentBlock(
        ContentBlockType type,
        String text,
        String toolUseId,
        String toolName,
        String toolInput,
        String toolOutput,
        Boolean isError,
        String thinking,
        Long durationMs
) {}
