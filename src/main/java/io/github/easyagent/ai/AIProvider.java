package io.github.easyagent.ai;

/**
 * AI 服务提供者接口。
 * <p>
 * 定义与 AI 服务交互的统一抽象，支持多种底层实现方式。
 * 当前支持 OpenCode CLI、Claude CLI、Codex CLI，后续可扩展 API 调用方式。
 * </p>
 *
 * @author haijun
 * @email "mailto:haijun@email.com"
 * @date 2026/4/30
 * @version 1.0.0
 * @since 1.0.0
 */
public interface AIProvider {

    /**
     * 获取提供者名称。
     *
     * @return 提供者唯一标识名称
     * @since 1.0.0
     */
    String name();

    /**
     * 发送提示消息并流式监听响应。
     *
     * @param prompt   用户输入的提示内容
     * @param listener 流式事件监听器
     * @since 1.0.0
     */
    void chat(String prompt, StreamEventListener listener);

    /**
     * 发送提示消息（带可选会话 ID）并流式监听响应。
     *
     * @param prompt    用户输入的提示内容
     * @param sessionId 会话 ID，为 null 时创建新会话
     * @param listener  流式事件监听器
     * @since 1.0.0
     */
    void chat(String prompt, String sessionId, StreamEventListener listener);

    /**
     * 发送提示消息（带可选会话 ID 和模型 ID）并流式监听响应。
     *
     * @param prompt    用户输入的提示内容
     * @param sessionId 会话 ID，为 null 时创建新会话
     * @param modelId   模型 ID，为 null 时使用 CLI 默认模型
     * @param listener  流式事件监听器
     * @since 1.0.0
     */
    void chat(String prompt, String sessionId, String modelId, StreamEventListener listener);

    /**
     * 停止当前正在运行的 CLI 进程。
     *
     * @since 1.0.0
     */
    void stop();

    /**
     * 停止指定会话的 CLI 进程。
     *
     * @param sessionId 会话 ID，为 null 时停止所有
     * @since 1.0.0
     */
    void stop(String sessionId);

    /**
     * 关闭提供者，释放相关资源。
     *
     * @since 1.0.0
     */
    void shutdown();
}
