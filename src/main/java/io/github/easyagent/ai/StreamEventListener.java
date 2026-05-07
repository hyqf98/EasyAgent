package io.github.easyagent.ai;

import io.github.easyagent.ai.entity.AIResponse;

/**
 * AI 流式响应事件监听器。
 * <p>
 * 定义 AI 服务响应过程中的事件回调，基于统一的 {@link AIResponse} 对象。
 * 不同 CLI 实现的原始事件在传递前已转换为统一格式。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public interface StreamEventListener {

    /**
     * 当收到统一 AI 响应事件时回调。
     *
     * @param response 统一响应对象
     * @since 1.0.0
     */
    void onResponse(AIResponse response);

    /**
     * 当整个对话流程正常完成时回调。
     *
     * @since 1.0.0
     */
    void onComplete();

    /**
     * 当对话过程中发生错误时回调。
     *
     * @param e 发生的异常
     * @since 1.0.0
     */
    void onError(Exception e);
}
