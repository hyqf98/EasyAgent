package io.github.easyagent.ui.jcef;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import javax.swing.UIManager;
import com.intellij.ui.jcef.JBCefBrowser;
import io.github.easyagent.enums.ValueEnum;
import io.github.easyagent.plan.PlanService;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.mcp.McpConfigService;
import io.github.easyagent.settings.mcp.McpTestService;
import io.github.easyagent.settings.models.ModelConfigService;
import io.github.easyagent.settings.plugins.PluginsConfigService;
import io.github.easyagent.settings.skills.SkillsConfigService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.enums.ThemeType;
import io.github.easyagent.ui.jcef.handler.BridgeContext;
import io.github.easyagent.ui.jcef.handler.ChatHandler;
import io.github.easyagent.ui.jcef.handler.CliConfigHandler;
import io.github.easyagent.ui.jcef.handler.FileReferenceHandler;
import io.github.easyagent.ui.jcef.handler.MessageHandler;
import io.github.easyagent.ui.jcef.handler.McpHandler;
import io.github.easyagent.ui.jcef.handler.ModelHandler;
import io.github.easyagent.ui.jcef.handler.PlanHandler;
import io.github.easyagent.ui.jcef.handler.PluginHandler;
import io.github.easyagent.ui.jcef.handler.QueryHandlerRecord;
import io.github.easyagent.ui.jcef.handler.SessionHandler;
import io.github.easyagent.ui.jcef.handler.SkillHandler;
import io.github.easyagent.ui.jcef.handler.ThemeHandler;
import io.github.easyagent.ui.service.ChatManager;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.FileEditService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.command.SlashCommandService;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.awt.Color;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JCEF Java-JavaScript 双向通信桥。
 * <p>
 * 所有 UI 交互由前端 Vue3 应用驱动，Java 端只提供数据接口。
 * 前端通过 {@code cefQuery} 发送 JSON 消息，Java 端通过
 * {@code window.__ea_onXxx} 全局回调函数推送事件到前端。
 * </p>
 * <p>
 * 业务逻辑委托给各 {@link MessageHandler} 实现，
 * 本类仅负责创建上下文、实例化 handler、分发请求和暴露公共 API。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class JCEFMessageBridge {

    private static final String JS_CALLBACK_PREFIX = "window.__ea_on";

    private final JBCefBrowser browser;
    private final ChatManager chatManager;
    private final SessionService sessionService;
    private final ModelConfigService modelConfigService;
    private final CliConfigService cliConfigService;
    private final McpConfigService mcpConfigService;
    private final McpTestService mcpTestService;
    private final SkillsConfigService skillsConfigService;
    private final PluginsConfigService pluginsConfigService;
    private final Project project;
    private final FileReferenceService fileReferenceService;
    private final ChatUiBridgeService chatUiBridgeService;
    private final FileEditService fileEditService;
    private final SlashCommandService slashCommandService;
    private final PlanService planService;
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, String> historyCache = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentSessionId = new AtomicReference<>();
    private final AtomicReference<String> currentProjectPath = new AtomicReference<>();

    private final Map<JsAction, QueryHandlerRecord<?>> queryHandlers = new EnumMap<>(JsAction.class);

    private final ThemeHandler themeHandler = new ThemeHandler();
    private final SessionHandler sessionHandler = new SessionHandler();
    private final ChatHandler chatHandler = new ChatHandler();
    private final ModelHandler modelHandler = new ModelHandler();
    private final CliConfigHandler cliConfigHandler = new CliConfigHandler(this.modelHandler);
    private final McpHandler mcpHandler = new McpHandler();
    private final SkillHandler skillHandler = new SkillHandler();
    private final PluginHandler pluginHandler = new PluginHandler();
    private final FileReferenceHandler fileReferenceHandler = new FileReferenceHandler();
    private final PlanHandler planHandler = new PlanHandler();

    private BridgeContext ctx;

    public JCEFMessageBridge(JBCefBrowser browser, ChatManager chatManager, Project project) {
        this.browser = browser;
        this.chatManager = chatManager;
        this.sessionService = new SessionService();
        this.modelConfigService = ModelConfigService.getInstance();
        this.cliConfigService = new CliConfigService();
        this.mcpConfigService = new McpConfigService();
        this.mcpTestService = new McpTestService();
        String basePath = project != null ? project.getBasePath() : null;
        this.skillsConfigService = new SkillsConfigService(basePath);
        this.pluginsConfigService = new PluginsConfigService(basePath);
        this.project = project;
        this.fileReferenceService = project != null ? project.getService(FileReferenceService.class) : null;
        this.chatUiBridgeService = project != null ? project.getService(ChatUiBridgeService.class) : null;
        this.fileEditService = new FileEditService(project, basePath, this.sessionService);
        this.slashCommandService = new SlashCommandService();
        this.planService = project != null ? new PlanService(project) : null;
        if (this.chatUiBridgeService != null) {
            this.chatUiBridgeService.registerBridge(this);
        }
        this.initContext();
        this.modelConfigService.initialize();
    }

    private void initContext() {
        this.ctx = new BridgeContext(
                this.browser, this.chatManager, this.sessionService,
                this.modelConfigService, this.cliConfigService,
                this.mcpConfigService, this.mcpTestService,
                this.skillsConfigService, this.pluginsConfigService,
                this.project, this.fileReferenceService,
                this.chatUiBridgeService, this.fileEditService,
                this.slashCommandService, this.planService,
                this.asyncExecutor, this.historyCache,
                this.currentSessionId, this.currentProjectPath
        );
        this.themeHandler.register(this.ctx, this.queryHandlers);
        this.sessionHandler.register(this.ctx, this.queryHandlers);
        this.chatHandler.register(this.ctx, this.queryHandlers);
        this.modelHandler.register(this.ctx, this.queryHandlers);
        this.cliConfigHandler.register(this.ctx, this.queryHandlers);
        this.mcpHandler.register(this.ctx, this.queryHandlers);
        this.skillHandler.register(this.ctx, this.queryHandlers);
        this.pluginHandler.register(this.ctx, this.queryHandlers);
        this.fileReferenceHandler.register(this.ctx, this.queryHandlers);
        this.planHandler.register(this.ctx, this.queryHandlers);
    }

    public void installJSBridge() {
        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new BridgeHandler(), true);
        this.browser.getCefBrowser().getClient().addMessageRouter(router);
    }

    public void sendThemeUpdate() {
        this.themeHandler.sendThemeUpdate(this.ctx);
    }

    public void pushAvailableCLIs() {
        this.sessionHandler.pushAvailableCLIs(this.ctx);
    }

    public void pushInitialData() {
        this.sessionHandler.pushInitialData(this.ctx);
    }

    public void setProjectPath(String projectPath) {
        this.ctx.setProjectPath(projectPath);
    }

    public void dispose() {
        if (this.chatUiBridgeService != null) {
            this.chatUiBridgeService.unregisterBridge(this);
        }
        this.mcpTestService.closeAll();
        this.chatManager.shutdown();
        this.asyncExecutor.shutdownNow();
    }

    public void pushFileReferences(List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        this.invokeJSCallback(JsCallback.INSERT_REFERENCES, references);
    }

    private void invokeJSCallback(JsCallback callback, String data) {
        String name = callback.getValue();
        String js = JS_CALLBACK_PREFIX + name + "&&" + JS_CALLBACK_PREFIX + name + "(" + data + ")";
        this.executeJS(js);
    }

    private void invokeJSCallback(JsCallback callback, Object data) {
        this.invokeJSCallback(callback, GsonUtils.toJson(data));
    }

    private void executeJS(String js) {
        try {
            this.browser.getCefBrowser().executeJavaScript(js, "", 0);
        } catch (Exception e) {
            log.warn("Failed to execute JS: {}", e.getMessage());
        }
    }

    private class BridgeHandler extends CefMessageRouterHandlerAdapter {

        @Override
        public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId,
                                String request, boolean persistent,
                                CefQueryCallback callback) {
            try {
                this.handleRequest(request);
                callback.success("");
            } catch (Exception e) {
                log.warn("Failed to handle JS query: {}", e.getMessage());
                callback.failure(0, e.getMessage());
            }
            return true;
        }

        private void handleRequest(String request) {
            if (StringUtil.isEmpty(request)) {
                return;
            }
            io.github.easyagent.ui.jcef.dto.CommonRequests.ActionRequest envelope =
                    GsonUtils.fromJson(request, io.github.easyagent.ui.jcef.dto.CommonRequests.ActionRequest.class);
            if (envelope == null || envelope.action() == null) {
                return;
            }
            JsAction jsAction = ValueEnum.fromValue(JsAction.class, envelope.action());
            if (jsAction == null) {
                log.debug("Unknown JS action: {}", envelope.action());
                return;
            }
            QueryHandlerRecord<?> handler = JCEFMessageBridge.this.queryHandlers.get(jsAction);
            if (handler == null) {
                log.debug("No JS handler registered for action: {}", envelope.action());
                return;
            }
            this.dispatch(request, handler);
        }

        @SuppressWarnings("unchecked")
        private <T extends io.github.easyagent.ui.jcef.dto.JsRequest> void dispatch(
                String request, QueryHandlerRecord<T> handler) {
            T typedRequest = GsonUtils.fromJson(request, handler.requestType());
            if (typedRequest == null) {
                return;
            }
            handler.consumer().accept(typedRequest);
        }
    }
}
