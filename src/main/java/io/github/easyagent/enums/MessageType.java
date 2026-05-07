package io.github.easyagent.enums;

import lombok.Getter;

/**
 * AI 消息类型枚举。
 * <p>
 * 区分 AI 输出的消息是思考推理内容还是正常文本输出。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
public enum MessageType implements ValueEnum<String> {

    /** AI 思考/推理内容。 */
    THINKING("thinking"),

    /** AI 正常文本输出。 */
    TEXT("text");

    /** 类型标识值。 */
    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    /**
     * 根据字符串值解析消息类型枚举。
     *
     * @param value 类型字符串
     * @return 对应的消息类型枚举，无法匹配时返回 {@code null}
     */
    public static MessageType fromValue(String value) {
        return ValueEnum.fromValue(MessageType.class, value);
    }
}
