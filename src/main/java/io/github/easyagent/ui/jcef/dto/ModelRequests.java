package io.github.easyagent.ui.jcef.dto;

/**
 * 模型配置相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class ModelRequests {

    private ModelRequests() {
    }

    /**
     * 保存模型配置请求。
     *
     * @param action 动作名称
     * @param models 模型配置 JSON
     */
    public record SaveModelsRequest(String action, String models) implements JsRequest {
    }

    /**
     * 查询 CLI 可用模型请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record QueryCliModelsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 查询 Provider 模型列表请求。
     *
     * @param action     动作名称
     * @param providerId Provider ID
     */
    public record QueryProviderModelsRequest(String action, String providerId) implements JsRequest {
    }

    /**
     * 保存 OpenCode 模型请求。
     *
     * @param action     动作名称
     * @param providerId Provider ID
     * @param modelId    模型 ID
     * @param name       模型名称
     * @param npmPackage NPM 包名
     */
    public record SaveOpenCodeModelRequest(String action, String providerId, String modelId,
                                            String name, String npmPackage) implements JsRequest {
    }

    /**
     * 删除 OpenCode 模型请求。
     *
     * @param action     动作名称
     * @param providerId Provider ID
     * @param modelId    模型 ID
     */
    public record DeleteOpenCodeModelRequest(String action, String providerId,
                                              String modelId) implements JsRequest {
    }
}
