package io.github.easyagent.ai.entity;

import lombok.Builder;

/**
 * 上下文压缩/摘要事件内容。
 * <p>
 * 当 AI 交互过程中上下文窗口接近上限时触发压缩，
 * 将历史对话摘要为精简内容以释放空间。
 * </p>
 *
 * @param reason 压缩原因描述
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record CompactContent(
        String reason
) {}
