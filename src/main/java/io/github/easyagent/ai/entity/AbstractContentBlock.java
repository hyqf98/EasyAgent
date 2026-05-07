package io.github.easyagent.ai.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AI 内容块抽象基类。
 *
 * @param <T> 内容类型枚举
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public abstract class AbstractContentBlock<T> {

    private T type;
    private String text;
    private String name;
}
