package io.github.easyagent.ai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 内容块抽象基类。
 *
 * @param <T> 内容类型枚举
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class AbstractContentBlock<T> {

    /** 内容类型。 */
    private T type;

    /** 文本内容。 */
    private String text;

    /** 名称标识。 */
    private String name;
}
