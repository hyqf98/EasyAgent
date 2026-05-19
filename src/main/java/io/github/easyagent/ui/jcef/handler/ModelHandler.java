package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.models.ModelInfo;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.jcef.dto.CommonRequests;
import io.github.easyagent.ui.jcef.dto.ModelRequests;

import java.util.List;
import java.util.Map;

/**
 * 模型配置 handler，负责模型列表推送、同步、保存和查询。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
public class ModelHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_MODELS, CommonRequests.ActionRequest.class,
                request -> this.pushModels(ctx));
        ctx.registerHandler(handlers, JsAction.SYNC_MODELS, CommonRequests.ActionRequest.class,
                request -> this.handleSyncModels(ctx));
        ctx.registerHandler(handlers, JsAction.SAVE_MODELS, ModelRequests.SaveModelsRequest.class,
                request -> this.handleSaveModels(ctx, request));
        ctx.registerHandler(handlers, JsAction.QUERY_CLI_MODELS, ModelRequests.QueryCliModelsRequest.class,
                request -> this.handleQueryCliModels(ctx, request));
        ctx.registerHandler(handlers, JsAction.QUERY_OPENCODE_MODELS, CommonRequests.ActionRequest.class,
                request -> this.handleQueryOpenCodeModels(ctx));
        ctx.registerHandler(handlers, JsAction.QUERY_PROVIDER_MODELS, ModelRequests.QueryProviderModelsRequest.class,
                request -> this.handleQueryProviderModels(ctx, request));
        ctx.registerHandler(handlers, JsAction.QUERY_ALL_PROVIDERS, CommonRequests.ActionRequest.class,
                request -> this.handleQueryAllProviders(ctx));
    }

    /**
     * 推送当前模型配置到前端。
     *
     * @param ctx 共享上下文
     */
    public void pushModels(BridgeContext ctx) {
        String json = ctx.modelConfigService().toJsonWithDefaults();
        ctx.invokeJSCallback(JsCallback.MODELS, json);
    }

    /**
     * 从各 CLI 配置的 API 地址同步模型列表。
     *
     * @param ctx 共享上下文
     */
    private void handleSyncModels(BridgeContext ctx) {
        ctx.asyncExecutor().submit(() -> {
            List<ModelInfo> claudeModels = ctx.modelConfigService().fetchClaudeModels();
            if (!claudeModels.isEmpty()) {
                ctx.modelConfigService().saveCliModels(CLIType.CLAUDE, claudeModels);
            }

            List<ModelInfo> codexModels = ctx.modelConfigService().fetchCodexModels();
            if (!codexModels.isEmpty()) {
                ctx.modelConfigService().saveCliModels(CLIType.CODEX, codexModels);
            }

            List<ModelInfo> openCodeModels = ctx.modelConfigService().queryOpenCodeModels();
            if (!openCodeModels.isEmpty()) {
                ctx.modelConfigService().saveCliModels(CLIType.OPENCODE, openCodeModels);
            }

            ctx.modelConfigService().redetectDefaultModels();
            ctx.getAppState().setModelsJson(ctx.modelConfigService().toJson());
            this.pushModels(ctx);
        });
    }

    /**
     * 保存前端编辑后的模型配置。
     *
     * @param ctx     共享上下文
     * @param request 保存请求
     */
    private void handleSaveModels(BridgeContext ctx, ModelRequests.SaveModelsRequest request) {
        String modelsJson = request.models();
        if (modelsJson == null || modelsJson.isBlank()) {
            return;
        }
        ctx.modelConfigService().loadFromJson(modelsJson);
        ctx.getAppState().setModelsJson(ctx.modelConfigService().toJson());
    }

    /**
     * 查询指定 CLI 的可用模型列表。
     *
     * @param ctx     共享上下文
     * @param request 查询请求
     */
    private void handleQueryCliModels(BridgeContext ctx, ModelRequests.QueryCliModelsRequest request) {
        String cliTypeStr = request.cliType();
        ctx.asyncExecutor().submit(() -> {
            if (CLIType.OPENCODE.name().equals(cliTypeStr)) {
                List<ModelInfo> models = ctx.modelConfigService().queryOpenCodeModels();
                ctx.invokeJSCallback(JsCallback.OPENCODE_MODELS, models);
            } else {
                ctx.invokeJSCallback(JsCallback.CLI_MODELS, "[]");
            }
        });
    }

    /**
     * 从 OpenCode CLI 本地查询所有可用模型。
     *
     * @param ctx 共享上下文
     */
    private void handleQueryOpenCodeModels(BridgeContext ctx) {
        ctx.asyncExecutor().submit(() -> {
            List<ModelInfo> models = ctx.modelConfigService().queryOpenCodeModels();
            ctx.invokeJSCallback(JsCallback.OPENCODE_MODELS, models);
        });
    }

    /**
     * 从 OpenCode CLI 查询指定 Provider 的模型列表。
     *
     * @param ctx     共享上下文
     * @param request 查询请求
     */
    private void handleQueryProviderModels(BridgeContext ctx, ModelRequests.QueryProviderModelsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            List<ModelInfo> models = ctx.modelConfigService().queryProviderModels(request.providerId());
            ctx.invokeJSCallback(JsCallback.PROVIDER_MODELS, models);
        });
    }

    /**
     * 从 models.dev API 查询所有 Provider 列表。
     *
     * @param ctx 共享上下文
     */
    private void handleQueryAllProviders(BridgeContext ctx) {
        ctx.asyncExecutor().submit(() -> {
            List<CliConfigService.ProviderInfo> providers = ctx.modelConfigService().queryAllProviders();
            ctx.invokeJSCallback(JsCallback.ALL_PROVIDERS, providers);
        });
    }

    /**
     * 持久化模型配置到应用状态。
     *
     * @param ctx 共享上下文
     */
    public void persistModelConfigs(BridgeContext ctx) {
        ctx.getAppState().setModelsJson(ctx.modelConfigService().toJson());
    }
}
