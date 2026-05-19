package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.ui.jcef.dto.JsRequest;

import java.util.function.Consumer;

/**
 * 处理器定义 record，封装请求类型和处理逻辑。
 *
 * @param requestType 请求实体类型
 * @param consumer    处理逻辑
 * @param <T>         请求实体类型
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public record QueryHandlerRecord<T extends JsRequest>(Class<T> requestType, Consumer<T> consumer) {
}
