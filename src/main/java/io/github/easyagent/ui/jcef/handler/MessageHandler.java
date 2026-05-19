package io.github.easyagent.ui.jcef.handler;

import java.util.Map;

/**
 * JS 请求处理器接口。
 * <p>
 * 每个域实现此接口，在 {@link #register(Map)} 中注册自己负责的 {@link io.github.easyagent.ui.enums.JsAction}。
 * Bridge 通过 {@link BridgeContext#registerHandler} 将 action 映射到具体处理方法。
 * </p>
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public interface MessageHandler {

    /**
     * 注册此 handler 负责的所有 JS 请求处理器。
     *
     * @param ctx     共享上下文，提供服务和工具方法
     * @param handlers 处理器映射，handler 应将自身注册到此处
     */
    void register(BridgeContext ctx, Map<io.github.easyagent.ui.enums.JsAction, QueryHandlerRecord<?>> handlers);
}
