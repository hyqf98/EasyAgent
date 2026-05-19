package io.github.easyagent.ui.jcef.dto;

/**
 * 通用与会话相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class CommonRequests {

    private CommonRequests() {
    }

    /**
     * 原始动作包装。
     *
     * @param action 动作名称
     */
    public record ActionRequest(String action) implements JsRequest {
    }

    /**
     * 空请求。
     *
     * @param action 动作名称
     */
    public record EmptyRequest(String action) implements JsRequest {
    }

    /**
     * 请求指定 CLI 类型的会话列表。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record ListSessionsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 请求历史消息。
     *
     * @param action      动作名称
     * @param sessionId   会话 ID
     * @param cliType     CLI 类型
     * @param forceReload 是否强制重新加载
     */
    public record LoadHistoryRequest(String action, String sessionId, String cliType,
                                      boolean forceReload) implements JsRequest {
    }

    /**
     * 停止生成请求。
     *
     * @param action    动作名称
     * @param sessionId 会话 ID
     */
    public record StopGenerationRequest(String action, String sessionId) implements JsRequest {
    }

    /**
     * 批量删除会话请求。
     *
     * @param action     动作名称
     * @param sessionIds 逗号分隔的会话 ID 列表
     */
    public record DeleteSessionsRequest(String action, String sessionIds) implements JsRequest {
    }

    /**
     * 保存待发送队列请求。
     *
     * @param action       动作名称
     * @param sessionId    会话 ID
     * @param pendingQueue 待发送队列 JSON
     */
    public record SavePendingQueueRequest(String action, String sessionId,
                                           String pendingQueue) implements JsRequest {
    }

    /**
     * 保存面板布局请求。
     *
     * @param action         动作名称
     * @param paneLayoutJson 面板布局 JSON 字符串
     */
    public record SavePaneLayoutRequest(String action, String paneLayoutJson) implements JsRequest {
    }

    /**
     * 保存重试配置请求。
     *
     * @param action         动作名称
     * @param retryMaxCount  最大重试次数
     * @param retryTimeoutMs 超时时间
     */
    public record SaveRetryConfigRequest(String action, int retryMaxCount,
                                          long retryTimeoutMs) implements JsRequest {
    }

    /**
     * 获取斜杠命令列表请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param requestId 请求 ID
     */
    public record GetSlashCommandsRequest(String action, String cliType,
                                           String requestId) implements JsRequest {
    }

    /**
     * 执行斜杠命令请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param rawText   原始命令文本
     * @param requestId 请求 ID
     */
    public record ExecuteSlashCommandRequest(String action, String cliType,
                                              String rawText, String requestId) implements JsRequest {
    }
}
