package io.github.easyagent.ai.entity;

import io.github.easyagent.enums.MessageType;
import lombok.Builder;

/**
 * 统一消息内容。
 * <p>
 * 包含 AI 的思考推理和正常文本输出，通过 {@link MessageType} 区分消息类型。
 * </p>
 *
 * @param messageType 消息类型
 * @param text        消息文本内容
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record MessageContent(
        MessageType messageType,
        String text
) {}
