package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.settings.plugins.PluginEntry;
import io.github.easyagent.settings.plugins.PluginsConfigService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.PluginActionPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.PluginCommandsPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.PluginContentPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.SaveContentResultPayload;
import io.github.easyagent.ui.jcef.dto.PluginRequests;
import io.github.easyagent.ui.jcef.dto.SkillRequests;

import java.util.List;
import java.util.Map;

/**
 * Plugins 管理 handler，负责插件的 CRUD、远程列表和内容读写。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
public class PluginHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_PLUGINS, PluginRequests.GetPluginsRequest.class,
                request -> this.handleGetPlugins(ctx, request));
        ctx.registerHandler(handlers, JsAction.INSTALL_PLUGIN, PluginRequests.InstallPluginRequest.class,
                request -> this.handleInstallPlugin(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_PLUGIN, PluginRequests.DeletePluginRequest.class,
                request -> this.handleDeletePlugin(ctx, request));
        ctx.registerHandler(handlers, JsAction.READ_PLUGIN_CONTENT, PluginRequests.ReadPluginContentRequest.class,
                request -> this.handleReadPluginContent(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_PLUGIN_CONTENT, PluginRequests.SavePluginContentRequest.class,
                request -> this.handleSavePluginContent(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_KNOWN_PLUGIN_REPOS, SkillRequests.ListKnownReposRequest.class,
                request -> this.handleListKnownPluginRepos(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_REMOTE_PLUGINS, PluginRequests.ListRemotePluginsRequest.class,
                request -> this.handleListRemotePlugins(ctx, request));
        ctx.registerHandler(handlers, JsAction.READ_PLUGIN_COMMANDS, PluginRequests.ReadPluginContentRequest.class,
                request -> this.handleReadPluginCommands(ctx, request));
    }

    private void handleGetPlugins(BridgeContext ctx, PluginRequests.GetPluginsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                List<PluginEntry> plugins = ctx.pluginsConfigService().loadInstalledPlugins(request.cliType());
                ctx.invokeJSCallback(JsCallback.PLUGINS, plugins);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGINS, List.of());
            }
        });
    }

    private void handleInstallPlugin(BridgeContext ctx, PluginRequests.InstallPluginRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                PluginsConfigService.InstallResult result = ctx.pluginsConfigService().installPlugin(
                        cliType, request.githubUrl(), request.pluginName(), request.scope());
                ctx.invokeJSCallback(JsCallback.PLUGIN_INSTALLED,
                        new PluginActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<PluginEntry> plugins = ctx.pluginsConfigService().loadInstalledPlugins(cliType);
                    ctx.invokeJSCallback(JsCallback.PLUGINS, plugins);
                }
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGIN_INSTALLED,
                        new PluginActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleDeletePlugin(BridgeContext ctx, PluginRequests.DeletePluginRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                PluginsConfigService.DeleteResult result = ctx.pluginsConfigService().deletePlugin(
                        cliType, request.pluginName(), request.installPath());
                ctx.invokeJSCallback(JsCallback.PLUGIN_DELETED,
                        new PluginActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<PluginEntry> plugins = ctx.pluginsConfigService().loadInstalledPlugins(cliType);
                    ctx.invokeJSCallback(JsCallback.PLUGINS, plugins);
                }
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGIN_DELETED,
                        new PluginActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleReadPluginContent(BridgeContext ctx, PluginRequests.ReadPluginContentRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String content = ctx.pluginsConfigService().readPluginContent(request.installPath());
                ctx.invokeJSCallback(JsCallback.PLUGIN_CONTENT,
                        new PluginContentPayload(request.installPath(), content != null ? content : ""));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGIN_CONTENT,
                        new PluginContentPayload(request.installPath(), ""));
            }
        });
    }

    private void handleReadPluginCommands(BridgeContext ctx, PluginRequests.ReadPluginContentRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                var commands = ctx.pluginsConfigService().readPluginCommands(request.installPath());
                ctx.invokeJSCallback(JsCallback.PLUGIN_COMMANDS,
                        new PluginCommandsPayload(request.installPath(), commands));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGIN_COMMANDS,
                        new PluginCommandsPayload(request.installPath(), List.of()));
            }
        });
    }

    private void handleSavePluginContent(BridgeContext ctx, PluginRequests.SavePluginContentRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                boolean success = ctx.pluginsConfigService().savePluginContent(
                        request.installPath(), request.content());
                ctx.invokeJSCallback(JsCallback.PLUGIN_CONTENT_SAVED,
                        new SaveContentResultPayload(success, request.installPath()));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.PLUGIN_CONTENT_SAVED,
                        new SaveContentResultPayload(false, request.installPath()));
            }
        });
    }

    private void handleListKnownPluginRepos(BridgeContext ctx, SkillRequests.ListKnownReposRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                var repos = ctx.pluginsConfigService().listKnownRepos(request.cliType());
                ctx.invokeJSCallback(JsCallback.KNOWN_PLUGIN_REPOS, repos);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.KNOWN_PLUGIN_REPOS, List.of());
            }
        });
    }

    private void handleListRemotePlugins(BridgeContext ctx, PluginRequests.ListRemotePluginsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                var plugins = ctx.pluginsConfigService().listRemotePlugins(request.ownerRepo());
                ctx.invokeJSCallback(JsCallback.REMOTE_PLUGINS, plugins);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.REMOTE_PLUGINS, List.of());
            }
        });
    }
}
