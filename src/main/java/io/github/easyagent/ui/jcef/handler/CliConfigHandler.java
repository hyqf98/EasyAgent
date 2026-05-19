package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.config.CliConfigs;
import io.github.easyagent.settings.config.CliProfile;
import io.github.easyagent.settings.config.ClaudeConfig;
import io.github.easyagent.settings.config.CodexConfig;
import io.github.easyagent.settings.config.OpenCodeConfig;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.CliConfigsPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.CliConfigsSavedPayload;
import io.github.easyagent.ui.jcef.dto.CliConfigRequests.ApplyCliProfileRequest;
import io.github.easyagent.ui.jcef.dto.CliConfigRequests.DeleteCliProfileRequest;
import io.github.easyagent.ui.jcef.dto.CliConfigRequests.SaveCliConfigsRequest;
import io.github.easyagent.ui.jcef.dto.CliConfigRequests.SaveCliProfileRequest;
import io.github.easyagent.ui.jcef.dto.CommonRequests;
import io.github.easyagent.ui.jcef.dto.ModelRequests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CLI 配置与配置档案管理 handler，负责 CLI 配置的推送、保存以及档案的增删改。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
public class CliConfigHandler implements MessageHandler {

    private final ModelHandler modelHandler;

    public CliConfigHandler(ModelHandler modelHandler) {
        this.modelHandler = modelHandler;
    }

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_CLI_CONFIGS, CommonRequests.ActionRequest.class,
                request -> this.pushCliConfigs(ctx));
        ctx.registerHandler(handlers, JsAction.SAVE_CLI_CONFIGS, SaveCliConfigsRequest.class,
                request -> this.handleSaveCliConfigs(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_CLI_PROFILE, SaveCliProfileRequest.class,
                request -> this.handleSaveCliProfile(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_CLI_PROFILE, DeleteCliProfileRequest.class,
                request -> this.handleDeleteCliProfile(ctx, request));
        ctx.registerHandler(handlers, JsAction.APPLY_CLI_PROFILE, ApplyCliProfileRequest.class,
                request -> this.handleApplyCliProfile(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_OPENCODE_MODEL, ModelRequests.SaveOpenCodeModelRequest.class,
                request -> this.handleSaveOpenCodeModel(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_OPENCODE_MODEL, ModelRequests.DeleteOpenCodeModelRequest.class,
                request -> this.handleDeleteOpenCodeModel(ctx, request));
    }

    /**
     * 推送当前 CLI 配置、配置档案和解析后的命令路径到前端。
     *
     * @param ctx 共享上下文
     */
    public void pushCliConfigs(BridgeContext ctx) {
        ctx.asyncExecutor().submit(() -> {
            try {
                CliConfigs configs = ctx.cliConfigService().readConfigs();
                Map<String, List<CliProfile>> profilesMap = this.loadAllProfiles(ctx);
                var resolvedPaths = new LinkedHashMap<String, String>();
                for (CLIType ct : CLIType.values()) {
                    String detected = ct.detectCommandPath();
                    resolvedPaths.put(ct.name(), detected != null ? detected : "");
                }
                CliConfigsPayload payload = new CliConfigsPayload(
                        configs,
                        ctx.cliConfigService().getOpenCodeProviders(ctx.modelConfigService().getDynamicProviders()),
                        profilesMap,
                        resolvedPaths
                );
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS, payload);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS,
                        new CliConfigsPayload(CliConfigs.empty(), List.of(), Map.of(), Map.of()));
            }
        });
    }

    /**
     * 保存指定 CLI 类型的配置并联动更新模型。
     *
     * @param ctx     共享上下文
     * @param request 保存请求
     */
    private void handleSaveCliConfigs(BridgeContext ctx, SaveCliConfigsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            String cliType = request.cliType();
            try {
                switch (cliType) {
                    case "CLAUDE" -> {
                        ClaudeConfig config = request.claude();
                        if (config == null) {
                            ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        ctx.cliConfigService().saveClaudeConfig(config);
                        ctx.cliConfigService().saveCommandPath(cliType, config.commandPath());
                    }
                    case "OPENCODE" -> {
                        OpenCodeConfig config = request.opencode();
                        if (config == null) {
                            ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        ctx.cliConfigService().saveOpenCodeConfig(config);
                        ctx.cliConfigService().saveCommandPath(cliType, config.commandPath());
                    }
                    case "CODEX" -> {
                        CodexConfig config = request.codex();
                        if (config == null) {
                            ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        ctx.cliConfigService().saveCodexConfig(config);
                        ctx.cliConfigService().saveCommandPath(cliType, config.commandPath());
                    }
                    default -> {
                        ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                new CliConfigsSavedPayload(false, cliType, "Unknown CLI type"));
                        return;
                    }
                }

                this.modelHandler.persistModelConfigs(ctx);
                this.modelHandler.pushModels(ctx);
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(true, cliType, ""));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, e.getMessage()));
            }
        });
    }

    /**
     * 从持久化状态加载所有 CLI 类型的配置档案。
     *
     * @param ctx 共享上下文
     * @return cliType -> List&lt;CliProfile&gt;
     */
    private Map<String, List<CliProfile>> loadAllProfiles(BridgeContext ctx) {
        EasyAgentAppState appState = ctx.getAppState();
        var result = new LinkedHashMap<String, List<CliProfile>>();
        for (CLIType cliType : CLIType.values()) {
            String json = appState.getCliProfiles().get(cliType.name());
            result.put(cliType.name(), ctx.cliConfigService().loadProfiles(json));
        }
        return result;
    }

    /**
     * 持久化指定 CLI 类型的配置档案列表。
     *
     * @param ctx      共享上下文
     * @param cliType  CLI 类型
     * @param profiles 档案列表
     */
    private void persistProfiles(BridgeContext ctx, String cliType, List<CliProfile> profiles) {
        ctx.getAppState().getCliProfiles().put(cliType,
                ctx.cliConfigService().serializeProfiles(profiles));
    }

    /**
     * 保存 CLI 配置档案（新增或更新）。
     *
     * @param ctx     共享上下文
     * @param request 保存档案请求
     */
    private void handleSaveCliProfile(BridgeContext ctx, SaveCliProfileRequest request) {
        ctx.asyncExecutor().submit(() -> {
            String cliType = request.cliType();
            CliProfile profile = request.profile();
            if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, "Profile name is required"));
                return;
            }
            if (profile.getId() == null || profile.getId().isBlank()) {
                profile.setId(UUID.randomUUID().toString().substring(0, 8));
            }
            profile.setCliType(cliType);

            Map<String, List<CliProfile>> allProfiles = this.loadAllProfiles(ctx);
            List<CliProfile> profiles = new ArrayList<>(allProfiles.getOrDefault(cliType, List.of()));
            boolean updated = false;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(profile.getId())) {
                    profiles.set(i, profile);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                profiles.add(profile);
            }
            this.persistProfiles(ctx, cliType, profiles);
            this.pushCliConfigs(ctx);
            ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                    new CliConfigsSavedPayload(true, cliType, ""));
        });
    }

    /**
     * 删除指定 CLI 配置档案。
     *
     * @param ctx     共享上下文
     * @param request 删除档案请求
     */
    private void handleDeleteCliProfile(BridgeContext ctx, DeleteCliProfileRequest request) {
        ctx.asyncExecutor().submit(() -> {
            String cliType = request.cliType();
            String profileId = request.profileId();

            Map<String, List<CliProfile>> allProfiles = this.loadAllProfiles(ctx);
            List<CliProfile> profiles = new ArrayList<>(allProfiles.getOrDefault(cliType, List.of()));
            profiles.removeIf(p -> p.getId().equals(profileId));
            this.persistProfiles(ctx, cliType, profiles);
            this.pushCliConfigs(ctx);
        });
    }

    /**
     * 应用 CLI 配置档案（切换到指定档案的配置）。
     *
     * @param ctx     共享上下文
     * @param request 应用档案请求
     */
    private void handleApplyCliProfile(BridgeContext ctx, ApplyCliProfileRequest request) {
        ctx.asyncExecutor().submit(() -> {
            String cliType = request.cliType();
            String profileId = request.profileId();

            Map<String, List<CliProfile>> allProfiles = this.loadAllProfiles(ctx);
            List<CliProfile> profiles = allProfiles.getOrDefault(cliType, List.of());
            CliProfile target = null;
            for (CliProfile p : profiles) {
                if (p.getId().equals(profileId)) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, "Profile not found"));
                return;
            }
            try {
                ctx.cliConfigService().applyProfile(target);
                this.pushCliConfigs(ctx);
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(true, cliType, ""));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, e.getMessage()));
            }
        });
    }

    /**
     * 保存 OpenCode 自定义模型。
     *
     * @param ctx     共享上下文
     * @param request 保存模型请求
     */
    private void handleSaveOpenCodeModel(BridgeContext ctx, ModelRequests.SaveOpenCodeModelRequest request) {
        ctx.asyncExecutor().submit(() -> {
            ctx.cliConfigService().saveOpenCodeModel(
                    request.providerId(), request.modelId(), request.name(), request.npmPackage());
        });
    }

    /**
     * 删除 OpenCode 自定义模型。
     *
     * @param ctx     共享上下文
     * @param request 删除模型请求
     */
    private void handleDeleteOpenCodeModel(BridgeContext ctx, ModelRequests.DeleteOpenCodeModelRequest request) {
        ctx.asyncExecutor().submit(() -> {
            ctx.cliConfigService().deleteOpenCodeModel(request.providerId(), request.modelId());
        });
    }
}
