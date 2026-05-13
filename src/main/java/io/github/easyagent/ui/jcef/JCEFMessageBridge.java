package io.github.easyagent.ui.jcef;

import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.JBColor;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.PlanStatus;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.enums.ValueEnum;
import io.github.easyagent.plan.PlanService;
import io.github.easyagent.plan.entity.Plan;
import io.github.easyagent.plan.entity.PlanTask;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.EasyAgentState;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.config.ClaudeConfig;
import io.github.easyagent.settings.config.CodexConfig;
import io.github.easyagent.settings.config.OpenCodeConfig;
import io.github.easyagent.settings.config.CliConfigs;
import io.github.easyagent.settings.config.CliProfile;
import io.github.easyagent.settings.mcp.McpConfigService;
import io.github.easyagent.settings.mcp.McpServerEntry;
import io.github.easyagent.settings.mcp.McpTestService;
import io.github.easyagent.settings.models.DefaultModelInfo;
import io.github.easyagent.settings.models.ModelConfigService;
import io.github.easyagent.settings.models.ModelInfo;
import io.github.easyagent.settings.plugins.PluginEntry;
import io.github.easyagent.settings.plugins.PluginsConfigService;
import io.github.easyagent.settings.skills.SkillEntry;
import io.github.easyagent.settings.skills.SkillsConfigService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.enums.ThemeType;
import io.github.easyagent.ui.service.ChatManager;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.FileEditService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.MessageConverter;
import io.github.easyagent.ui.service.command.SlashCommandService;
import io.github.easyagent.ui.service.entity.FileReferenceCandidatePayload;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import io.github.easyagent.ui.service.entity.SlashCommandExecutionPayload;
import io.github.easyagent.ui.service.entity.SlashCommandsPayload;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JCEF Java-JavaScript 双向通信桥。
 * <p>
 * 所有 UI 交互由前端 Vue3 应用驱动，Java 端只提供数据接口。
 * 前端通过 {@code cefQuery} 发送 JSON 消息，Java 端通过
 * {@code window.__ea_onXxx} 全局回调函数推送事件到前端。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class JCEFMessageBridge {

    /** 前端 JS 全局回调函数名前缀。 */
    private static final String JS_CALLBACK_PREFIX = "window.__ea_on";

    /** 前端 JS 空数组字面量。 */
    private static final String JS_EMPTY_ARRAY = "[]";

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

    /** JS 请求处理器映射。 */
    private final Map<JsAction, QueryHandler<? extends JsRequest>> queryHandlers = new EnumMap<>(JsAction.class);

    private volatile String currentSessionId;

    private volatile String currentProjectPath;

    /** 异步任务线程池。 */
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 历史消息 JSON 缓存，sessionId -> JSON。 */
    private final ConcurrentHashMap<String, String> historyCache = new ConcurrentHashMap<>();

    /**
     * 构造通信桥。
     *
     * @param browser     JCEF 浏览器实例
     * @param chatManager 对话管理器
     * @param project     当前 IDEA 项目，用于持久化状态
     */
    public JCEFMessageBridge(JBCefBrowser browser, ChatManager chatManager, Project project) {
        this.browser = browser;
        this.chatManager = chatManager;
        this.sessionService = new SessionService();
        this.modelConfigService = new ModelConfigService();
        this.cliConfigService = new CliConfigService();
        this.mcpConfigService = new McpConfigService();
        this.mcpTestService = new McpTestService();
        String basePath = project != null ? project.getBasePath() : null;
        this.skillsConfigService = new SkillsConfigService(basePath);
        this.pluginsConfigService = new PluginsConfigService(basePath);
        this.project = project;
        this.fileReferenceService = project != null ? project.getService(FileReferenceService.class) : null;
        this.chatUiBridgeService = project != null ? project.getService(ChatUiBridgeService.class) : null;
        this.fileEditService = new FileEditService(project, project != null ? project.getBasePath() : null,
                this.sessionService);
        this.slashCommandService = new SlashCommandService();
        this.planService = project != null ? new PlanService(project) : null;
        if (this.chatUiBridgeService != null) {
            this.chatUiBridgeService.registerBridge(this);
        }
        this.registerHandlers();
        this.initModelConfigs();
    }

    /**
     * 初始化模型配置，优先从持久化恢复，其次从本地文件加载。
     */
    private void initModelConfigs() {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        String saved = appState.getModelsJson();
        if (saved != null && !saved.isBlank()) {
            this.modelConfigService.loadFromJson(saved);
            return;
        }
        this.modelConfigService.loadFromLocal();
    }

    /**
     * 注册 JS 请求处理器。
     */
    private void registerHandlers() {
        this.registerHandler(JsAction.LIST_ALL_SESSIONS, ActionRequest.class, request -> this.pushAllSessions());
        this.registerHandler(JsAction.LIST_SESSIONS, ListSessionsRequest.class,
                request -> this.pushSessionList(request.cliType()));
        this.registerHandler(JsAction.LOAD_HISTORY, LoadHistoryRequest.class,
                request -> this.loadHistory(request.sessionId(), request.cliType(), request.forceReload()));
        this.registerHandler(JsAction.SEND_MESSAGE, SendMessageRequest.class, request -> this.sendUserMessage(
                request.text(), request.cliType(), request.sessionId(), request.modelId(),
                request.reasoningLevel(), request.fileReferences()));
        this.registerHandler(JsAction.STOP_GENERATION, StopGenerationRequest.class,
                request -> this.chatManager.stopGeneration(request.sessionId()));
        this.registerHandler(JsAction.GET_THEME, ActionRequest.class, request -> this.sendThemeUpdate());
        this.registerHandler(JsAction.GET_AVAILABLE_CLIS, ActionRequest.class, request -> this.pushAvailableCLIs());
        this.registerHandler(JsAction.PAGE_READY, ActionRequest.class, request -> this.pushInitialData());
        this.registerHandler(JsAction.DELETE_SESSIONS, DeleteSessionsRequest.class,
                this::handleDeleteSessions);
        this.registerHandler(JsAction.SAVE_PENDING_QUEUE, SavePendingQueueRequest.class,
                this::handleSavePendingQueue);
        this.registerHandler(JsAction.GET_RETRY_CONFIG, ActionRequest.class, request -> this.pushRetryConfig());
        this.registerHandler(JsAction.SAVE_RETRY_CONFIG, SaveRetryConfigRequest.class,
                this::handleSaveRetryConfig);
        this.registerHandler(JsAction.GET_MODELS, ActionRequest.class, request -> this.pushModels());
        this.registerHandler(JsAction.SYNC_MODELS, ActionRequest.class, request -> this.handleSyncModels());
        this.registerHandler(JsAction.SAVE_MODELS, SaveModelsRequest.class, this::handleSaveModels);
        this.registerHandler(JsAction.QUERY_CLI_MODELS, QueryCliModelsRequest.class,
                this::handleQueryCliModels);
        this.registerHandler(JsAction.SEARCH_FILE_REFERENCES, SearchFileReferencesRequest.class,
                this::handleSearchFileReferences);
        this.registerHandler(JsAction.RESOLVE_FILE_REFERENCE, ResolveFileReferenceRequest.class,
                this::handleResolveFileReference);
        this.registerHandler(JsAction.SAVE_CLIPBOARD_IMAGE, SaveClipboardImageRequest.class,
                this::handleSaveClipboardImage);
        this.registerHandler(JsAction.OPEN_FILE_EDIT_DIFF, OpenFileEditDiffRequest.class,
                this::handleOpenFileEditDiff);
        this.registerHandler(JsAction.REVERT_FILE_EDIT, RevertFileEditRequest.class,
                this::handleRevertFileEdit);
        this.registerHandler(JsAction.GET_SLASH_COMMANDS, GetSlashCommandsRequest.class,
                this::handleGetSlashCommands);
        this.registerHandler(JsAction.EXECUTE_SLASH_COMMAND, ExecuteSlashCommandRequest.class,
                this::handleExecuteSlashCommand);
        this.registerHandler(JsAction.GET_CLI_CONFIGS, ActionRequest.class, request -> this.pushCliConfigs());
        this.registerHandler(JsAction.SAVE_CLI_CONFIGS, SaveCliConfigsRequest.class,
                this::handleSaveCliConfigs);
        this.registerHandler(JsAction.SAVE_CLI_PROFILE, SaveCliProfileRequest.class,
                this::handleSaveCliProfile);
        this.registerHandler(JsAction.DELETE_CLI_PROFILE, DeleteCliProfileRequest.class,
                this::handleDeleteCliProfile);
        this.registerHandler(JsAction.APPLY_CLI_PROFILE, ApplyCliProfileRequest.class,
                this::handleApplyCliProfile);
        this.registerHandler(JsAction.GET_MCP_CONFIGS, McpConfigsRequest.class,
                this::handleGetMcpConfigs);
        this.registerHandler(JsAction.SAVE_MCP_SERVER, SaveMcpServerRequest.class,
                this::handleSaveMcpServer);
        this.registerHandler(JsAction.DELETE_MCP_SERVER, DeleteMcpServerRequest.class,
                this::handleDeleteMcpServer);
        this.registerHandler(JsAction.TEST_MCP_CONNECT, TestMcpConnectRequest.class,
                this::handleTestMcpConnect);
        this.registerHandler(JsAction.LIST_MCP_TOOLS, ListMcpToolsRequest.class,
                this::handleListMcpTools);
        this.registerHandler(JsAction.CALL_MCP_TOOL, CallMcpToolRequest.class,
                this::handleCallMcpTool);
        this.registerHandler(JsAction.GET_SKILLS, GetSkillsRequest.class,
                this::handleGetSkills);
        this.registerHandler(JsAction.INSTALL_SKILL, InstallSkillRequest.class,
                this::handleInstallSkill);
        this.registerHandler(JsAction.DELETE_SKILL, DeleteSkillRequest.class,
                this::handleDeleteSkill);
        this.registerHandler(JsAction.READ_SKILL_CONTENT, ReadSkillContentRequest.class,
                this::handleReadSkillContent);
        this.registerHandler(JsAction.LIST_KNOWN_REPOS, ListKnownReposRequest.class,
                this::handleListKnownRepos);
        this.registerHandler(JsAction.LIST_REMOTE_SKILLS, ListRemoteSkillsRequest.class,
                this::handleListRemoteSkills);

        this.registerHandler(JsAction.GET_PLUGINS, GetPluginsRequest.class,
                this::handleGetPlugins);
        this.registerHandler(JsAction.INSTALL_PLUGIN, InstallPluginRequest.class,
                this::handleInstallPlugin);
        this.registerHandler(JsAction.DELETE_PLUGIN, DeletePluginRequest.class,
                this::handleDeletePlugin);
        this.registerHandler(JsAction.READ_PLUGIN_CONTENT, ReadPluginContentRequest.class,
                this::handleReadPluginContent);
        this.registerHandler(JsAction.LIST_KNOWN_PLUGIN_REPOS, ListKnownReposRequest.class,
                this::handleListKnownPluginRepos);
        this.registerHandler(JsAction.LIST_REMOTE_PLUGINS, ListRemotePluginsRequest.class,
                this::handleListRemotePlugins);
        this.registerHandler(JsAction.READ_PLUGIN_COMMANDS, ReadPluginContentRequest.class,
                this::handleReadPluginCommands);
        this.registerHandler(JsAction.SAVE_SKILL_CONTENT, SaveSkillContentRequest.class,
                this::handleSaveSkillContent);
        this.registerHandler(JsAction.SAVE_PLUGIN_CONTENT, SavePluginContentRequest.class,
                this::handleSavePluginContent);

        // 计划模式
        this.registerHandler(JsAction.CREATE_PLAN, CreatePlanRequest.class,
                this::handleCreatePlan);
        this.registerHandler(JsAction.LIST_PLANS, ActionRequest.class,
                request -> this.handleListPlans());
        this.registerHandler(JsAction.GET_PLAN_DETAIL, PlanIdRequest.class,
                this::handleGetPlanDetail);
        this.registerHandler(JsAction.UPDATE_PLAN, UpdatePlanRequest.class,
                this::handleUpdatePlan);
        this.registerHandler(JsAction.DELETE_PLAN, PlanIdRequest.class,
                this::handleDeletePlan);
        this.registerHandler(JsAction.UPDATE_PLAN_TASK, UpdatePlanTaskRequest.class,
                this::handleUpdatePlanTask);
        this.registerHandler(JsAction.EXECUTE_PLAN_TASK, ExecutePlanTaskRequest.class,
                this::handleExecutePlanTask);
        this.registerHandler(JsAction.STOP_PLAN_TASK, StopPlanTaskRequest.class,
                this::handleStopPlanTask);
        this.registerHandler(JsAction.AI_EDIT_TASKS, AiEditTasksRequest.class,
                this::handleAiEditTasks);
        this.registerHandler(JsAction.SAVE_PLAN_TASKS, SavePlanTasksRequest.class,
                this::handleSavePlanTasks);
        this.registerHandler(JsAction.GET_PLAN_CONFIG, ActionRequest.class,
                request -> this.handleGetPlanConfig());
        this.registerHandler(JsAction.SAVE_PLAN_CONFIG, SavePlanConfigRequest.class,
                this::handleSavePlanConfig);
        this.registerHandler(JsAction.START_PLAN_SPLIT, PlanIdRequest.class,
                this::handleStartPlanSplit);
    }

    /**
     * 注册单个 JS 请求处理器。
     *
     * @param action      JS 动作
     * @param requestType 请求实体类型
     * @param consumer    处理逻辑
     * @param <T>         请求实体类型
     */
    private <T extends JsRequest> void registerHandler(JsAction action, Class<T> requestType, Consumer<T> consumer) {
        this.queryHandlers.put(action, new QueryHandler<>(requestType, consumer));
    }

    /**
     * 安装 JS 桥，注册 CefMessageRouter 处理器。
     */
    public void installJSBridge() {
        CefMessageRouter router = CefMessageRouter.create();
        router.addHandler(new BridgeHandler(), true);
        this.browser.getCefBrowser().getClient().addMessageRouter(router);
    }

    /**
     * 同步当前 IDE 主题到前端。
     */
    public void sendThemeUpdate() {
        ThemeType theme = ThemeType.fromDark(!JBColor.isBright());
        this.invokeJSCallback(JsCallback.THEME_CHANGED, new ThemePayload(theme.isDark()));
    }

    /**
     * 推送所有可用 CLI 工具列表到前端。
     */
    public void pushAvailableCLIs() {
        List<SessionService.CLIDescriptor> clis = this.sessionService.listAvailableCLIs();
        this.invokeJSCallback(JsCallback.AVAILABLE_CLIS, GsonUtils.toJson(clis));
    }

    /**
     * 页面加载完成后推送初始数据：主题 + 所有会话列表 + 可用 CLI 列表 + 持久化状态。
     */
    public void pushInitialData() {
        this.sendThemeUpdate();
        this.pushAllSessions();
        this.pushAvailableCLIs();
        this.pushRestoredState();
        this.pushModels();
    }

    /**
     * 设置当前项目路径，用于按项目筛选会话。
     *
     * @param projectPath 项目路径
     */
    public void setProjectPath(String projectPath) {
        this.currentProjectPath = projectPath;
    }

    /**
     * 释放通信桥资源。
     */
    public void dispose() {
        if (this.chatUiBridgeService != null) {
            this.chatUiBridgeService.unregisterBridge(this);
        }
        this.mcpTestService.closeAll();
        this.chatManager.shutdown();
        this.asyncExecutor.shutdownNow();
    }

    /**
     * 异步推送所有 CLI 类型的会话列表到前端。
     * <p>
     * 优先按当前项目路径筛选，无项目路径时返回全部会话。
     * 在虚拟线程中执行以避免阻塞 JCEF 线程。
     * </p>
     */
    private void pushAllSessions() {
        this.asyncExecutor.submit(() -> {
            try {
                List<SessionInfo> sessions = this.currentProjectPath != null
                        ? this.sessionService.listAllSessions(this.currentProjectPath)
                        : this.sessionService.listAllSessions();
                this.invokeJSCallback(JsCallback.SESSION_LIST, GsonUtils.toJson(sessions));
            } catch (Exception e) {
                log.warn("Failed to list all sessions", e);
                this.invokeJSCallback(JsCallback.SESSION_LIST, JS_EMPTY_ARRAY);
            }
        });
    }

    /**
     * 异步推送指定 CLI 类型的会话列表到前端。
     *
     * @param cliType CLI 类型名称
     */
    private void pushSessionList(String cliType) {
        this.asyncExecutor.submit(() -> {
            this.sendSessionList(cliType);
        });
    }

    /**
     * 直接推送指定 CLI 类型的会话列表到前端。
     *
     * @param cliType CLI 类型名称
     */
    private void sendSessionList(String cliType) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            List<SessionInfo> sessions = this.currentProjectPath != null
                    ? this.sessionService.listSessions(type, this.currentProjectPath)
                    : this.sessionService.listSessions(type);
            this.invokeJSCallback(JsCallback.SESSION_LIST, GsonUtils.toJson(sessions));
        } catch (Exception e) {
            log.warn("Failed to list sessions for {}", cliType, e);
            this.invokeJSCallback(JsCallback.SESSION_LIST, JS_EMPTY_ARRAY);
        }
    }

    /**
     * 异步加载历史会话消息并推送到前端。
     * <p>
     * 优先从内存缓存读取，缓存未命中时在虚拟线程中读取并缓存结果。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型名称
     */
    private void loadHistory(String sessionId, String cliType) {
        this.loadHistory(sessionId, cliType, false);
    }

    /**
     * 异步加载历史会话消息并推送到前端。
     * <p>
     * 优先从内存缓存读取，缓存未命中时在虚拟线程中读取并缓存结果。
     * forceReload 为 true 时会绕过缓存重新读取磁盘数据。
     * </p>
     *
     * @param sessionId   会话 ID
     * @param cliType     CLI 类型名称
     * @param forceReload  是否强制重新读取
     */
    private void loadHistory(String sessionId, String cliType, boolean forceReload) {
        log.info("[PLAN-DEBUG] loadHistory: sessionId={}, cliType={}, forceReload={}", sessionId, cliType, forceReload);
        this.currentSessionId = sessionId;
        if (!forceReload) {
            String cached = this.historyCache.get(sessionId);
            if (cached != null) {
                log.info("[PLAN-DEBUG] loadHistory cache HIT: sessionId={}", sessionId);
                this.invokeJSCallback(JsCallback.HISTORY_LOADED, cached);
                this.persistCurrentSession(sessionId, cliType);
                this.asyncExecutor.submit(() -> this.rehydrateHistoricalFileEdits(sessionId, cliType));
                return;
            }
        }
        this.asyncExecutor.submit(() -> {
            try {
                CLIType type = CLIType.valueOf(cliType);
                String json = this.chatManager.loadHistory(sessionId, type, this.currentProjectPath);
                log.info("[PLAN-DEBUG] loadHistory from file: sessionId={}, msgCount={}", sessionId, json != null ? json.length() : -1);
                this.rehydrateHistoricalFileEdits(sessionId, cliType);
                this.historyCache.put(sessionId, json);
                this.invokeJSCallback(JsCallback.HISTORY_LOADED, json);
                this.persistCurrentSession(sessionId, cliType);
            } catch (Exception e) {
                log.warn("[PLAN-DEBUG] loadHistory failed: sessionId={}", sessionId, e);
            }
        });
    }

    /**
     * 根据历史会话重新恢复文件编辑快照。
     *
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型
     */
    private void rehydrateHistoricalFileEdits(String sessionId, String cliType) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            List<SessionMessage> messages = this.sessionService.readMessages(type, sessionId);
            this.fileEditService.trackHistoricalMessages(messages);
        } catch (Exception e) {
            log.debug("Failed to rehydrate file edits for session {}", sessionId, e);
        }
    }

    /**
     * 持久化当前会话 ID 和 CLI 类型。
     *
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型名称
     */
    private void persistCurrentSession(String sessionId,
                                       String cliType) {
        if (this.project != null) {
            EasyAgentState state = EasyAgentState.getInstance(this.project);
            state.setCurrentSessionId(sessionId);
        }
        EasyAgentAppState.getInstance().setCurrentCliType(cliType);
    }

    /**
     * 发送用户消息到 AI Provider 并通过流式监听器将响应事件推送到前端。
     *
     * @param text      用户输入文本
     * @param cliType   CLI 类型名称
     * @param sessionId 会话 ID
     * @param modelId   模型 ID，可为 null
     */
    private void sendUserMessage(String text, String cliType, String sessionId, String modelId,
                                 String reasoningLevel,
                                 List<FileReferencePayload> fileReferences) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            String effectiveSessionId = resolveEffectiveSessionId(sessionId);
            String prompt = this.fileReferenceService != null
                    ? this.fileReferenceService.enrichPrompt(text, fileReferences)
                    : text;

            this.currentSessionId = effectiveSessionId;

            Plan splittingPlan = this.findPlanBySessionId(sessionId, effectiveSessionId);
            StringBuilder planResponseBuilder = splittingPlan != null ? new StringBuilder() : null;

            this.chatManager.sendMessage(prompt, effectiveSessionId, type,
                    this.currentProjectPath, modelId, reasoningLevel, new StreamEventListener() {
                @Override
                public void onResponse(AIResponse response) {
                    JCEFMessageBridge.this.logAIResponse(response);
                    String resolvedSessionId = response.sessionId() != null
                            ? response.sessionId()
                            : (JCEFMessageBridge.this.currentSessionId != null
                            ? JCEFMessageBridge.this.currentSessionId
                            : effectiveSessionId);
                    if (response.toolCall() != null) {
                        JCEFMessageBridge.this.fileEditService.trackToolCall(resolvedSessionId, response.toolCall());
                    }
                    if (planResponseBuilder != null && response.message() != null && response.message().text() != null) {
                        planResponseBuilder.append(response.message().text());
                    }
                    String eventJson = MessageConverter.toStreamEventJson(response, resolvedSessionId,
                            JCEFMessageBridge.this.currentProjectPath);
                    JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_EVENT, eventJson);
                    JCEFMessageBridge.this.currentSessionId = resolvedSessionId;
                    JCEFMessageBridge.this.persistCurrentSession(resolvedSessionId, cliType);
                }

                @Override
                public void onComplete() {
                    String resolvedSessionId = JCEFMessageBridge.this.currentSessionId != null
                            ? JCEFMessageBridge.this.currentSessionId
                            : effectiveSessionId;

                    if (splittingPlan != null && planResponseBuilder != null) {
                        JCEFMessageBridge.this.tryExtractAndCreateTasks(splittingPlan, planResponseBuilder.toString(), resolvedSessionId);
                    }

                    JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_COMPLETE,
                            new StreamCompletePayload(resolvedSessionId));
                }

                @Override
                public void onError(Exception e) {
                    String errJson = MessageConverter.toErrorJson(e.getMessage(), effectiveSessionId);
                    JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_EVENT, errJson);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to send message", e);
        }
    }

    /**
     * 解析有效的会话 ID。对于以 "plan-" 前缀开头的会话 ID，从计划数据中查找真实的 CLI 会话 ID。
     *
     * @param sessionId 前端传入的会话 ID
     * @return 可直接传递给 CLI 的会话 ID
     */
    private String resolveEffectiveSessionId(String sessionId) {
        if (StringUtil.isEmpty(sessionId)) {
            return "new-" + System.currentTimeMillis();
        }
        if (sessionId.startsWith("plan-")) {
            String planId = sessionId.substring("plan-".length());
            Plan plan = this.planService != null ? this.planService.getPlan(planId) : null;
            if (plan != null && plan.sessionId() != null) {
                return plan.sessionId();
            }
            return "new-" + System.currentTimeMillis();
        }
        return sessionId;
    }

    private Plan findPlanBySessionId(String rawSessionId, String effectiveSessionId) {
        if (this.planService == null) {
            return null;
        }
        if (rawSessionId != null && rawSessionId.startsWith("plan-")) {
            String planId = rawSessionId.substring("plan-".length());
            Plan plan = this.planService.getPlan(planId);
            if (plan != null && plan.status() == PlanStatus.TASK_SPLITTING) {
                return plan;
            }
        }
        if (effectiveSessionId != null) {
            List<Plan> plans = this.planService.listPlans();
            for (Plan p : plans) {
                if (p.status() == PlanStatus.TASK_SPLITTING && effectiveSessionId.equals(p.sessionId())) {
                    return p;
                }
            }
        }
        return null;
    }

    private void tryExtractAndCreateTasks(Plan plan, String fullResponse, String resolvedSessionId) {
        String tasksJson = this.extractTaskListJson(fullResponse);
        if (tasksJson == null) {
            return;
        }
        log.info("[PLAN] extracted tasks from response, planId={}", plan.planId());
        List<PlanTask> parsed = this.planService.parseAndCreateTasks(plan.planId(), tasksJson);
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        this.invokeJSCallback(JsCallback.PLAN_SPLIT_RESULT, GsonUtils.toJson(Map.of(
                "planId", plan.planId(),
                "tasks", parsed
        )));
    }

    /**
     * 打印 AI 响应事件的日志摘要。
     *
     * @param response AI 响应事件
     */
    private void logAIResponse(AIResponse response) {
        if (!log.isDebugEnabled()) {
            return;
        }
        ResponseType type = response.type();
        String sid = response.sessionId();
        switch (type) {
            case STEP_START -> log.debug("[AI] STEP_START  | session: {}", this.shortSid(sid));
            case MESSAGE -> {
                MessageContent msg = response.message();
                if (msg != null) {
                    log.debug("[AI] MESSAGE      | {} | {}", msg.messageType(), this.truncate(msg.text(), 80));
                }
            }
            case TOOL_USE -> {
                ToolCallContent tool = response.toolCall();
                if (tool != null) {
                    log.debug("[AI] TOOL_USE     | {} | {} | {}", tool.toolName(), tool.status(), this.truncate(tool.input(), 60));
                }
            }
            case STEP_FINISH -> log.debug("[AI] STEP_FINISH  | session: {}", this.shortSid(sid));
            case ERROR -> log.debug("[AI] ERROR        | {}", this.truncate(response.error() != null ? response.error().message() : "unknown", 120));
            case RETRY_STATUS -> log.debug("[AI] RETRY_STATUS | attempt: {}/{}", response.retryStatus().currentAttempt(), response.retryStatus().maxAttempts());
            case COMPACT -> log.debug("[AI] COMPACT      | session: {}", this.shortSid(sid));
        }
    }

    /**
     * 截断过长的文本用于日志输出。
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String flattened = text.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() > maxLength ? flattened.substring(0, maxLength) + "..." : flattened;
    }

    /**
     * 截断会话 ID 用于日志输出。
     *
     * @param sessionId 完整会话 ID
     * @return 截断后的会话 ID
     */
    private String shortSid(String sessionId) {
        if (sessionId == null) {
            return "N/A";
        }
        return sessionId.length() > 12 ? sessionId.substring(0, 12) + "..." : sessionId;
    }

    /**
     * 处理批量删除会话请求，删除后刷新会话列表。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleDeleteSessions(DeleteSessionsRequest request) {
        String idsStr = request.sessionIds();
        if (idsStr == null || idsStr.isBlank()) {
            return;
        }
        List<String> sessionIds = List.of(idsStr.split(","));
        this.asyncExecutor.submit(() -> {
            sessionIds.forEach(this.historyCache::remove);
            int deleted = this.sessionService.deleteSessions(sessionIds);
            this.sendSessionList(this.chatManager.getCurrentCLIType().name());
            this.invokeJSCallback(JsCallback.SESSIONS_DELETED,
                    new SessionsDeletedPayload(deleted, sessionIds));
        });
    }

    /**
     * 保存指定会话的待发送队列到项目级持久化。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleSavePendingQueue(SavePendingQueueRequest request) {
        if (this.project == null) {
            return;
        }
        String sessionId = request.sessionId();
        String pendingJson = request.pendingQueue();
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        state.savePendingQueue(sessionId, pendingJson);
        state.setCurrentSessionId(sessionId);
    }

    /**
     * 推送恢复的持久化状态到前端。
     * <p>
     * 包括上次活跃的会话 ID、CLI 类型和所有待发送队列。
     * </p>
     */
    private void pushRestoredState() {
        if (this.project == null) {
            return;
        }
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        this.invokeJSCallback(JsCallback.STATE_RESTORED, new RestoredStatePayload(
                state.getCurrentSessionId() != null ? state.getCurrentSessionId() : "",
                appState.getCurrentCliType() != null ? appState.getCurrentCliType() : "",
                this.pendingQueueStates(state.getPendingQueues()),
                appState.getRetryMaxCount(),
                appState.getRetryTimeoutMs()
        ));
    }

    /**
     * 推送当前 AI 重试策略配置到前端。
     */
    private void pushRetryConfig() {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        this.invokeJSCallback(JsCallback.RETRY_CONFIG,
                new RetryConfigPayload(appState.getRetryMaxCount(), appState.getRetryTimeoutMs()));
    }

    /**
     * 保存 AI 重试策略配置。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleSaveRetryConfig(SaveRetryConfigRequest request) {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        int maxCount = request.retryMaxCount();
        long timeoutMs = request.retryTimeoutMs();
        appState.setRetryMaxCount(maxCount);
        appState.setRetryTimeoutMs(timeoutMs);
        this.chatManager.updateRetryConfig(maxCount, timeoutMs);
    }

    /**
     * 推送模型配置列表到前端。
     * <p>
     * 包含模型列表和每种 CLI 类型的默认模型配置。
     * </p>
     */
    private void pushModels() {
        String json = this.modelConfigService.toJsonWithDefaults();
        this.invokeJSCallback(JsCallback.MODELS, json);
    }

    /**
     * 从远程同步最新的模型配置，并从 models.dev API 查询 OpenCode 支持的模型。
     */
    private void handleSyncModels() {
        this.asyncExecutor.submit(() -> {
            String json = this.modelConfigService.syncFromRemote();
            if (json != null) {
                EasyAgentAppState.getInstance().setModelsJson(json);
            }

            List<ModelInfo> openCodeModels = this.modelConfigService.queryModelsDev();
            if (!openCodeModels.isEmpty()) {
                this.modelConfigService.mergeModels(openCodeModels);
            }

            EasyAgentAppState.getInstance().setModelsJson(this.modelConfigService.toJson());
            this.pushModels();
        });
    }

    /**
     * 保存前端编辑后的模型配置。
     *
     * @param obj 请求 JSON 对象，包含 models 数组
     */
    private void handleSaveModels(SaveModelsRequest request) {
        String modelsJson = request.models();
        if (modelsJson == null || modelsJson.isBlank()) {
            return;
        }
        this.modelConfigService.loadFromJson(modelsJson);
        EasyAgentAppState.getInstance().setModelsJson(this.modelConfigService.toJson());
    }

    /**
     * 查询指定 CLI 的可用模型列表（OpenCode 使用 models.dev API）。
     *
     * @param obj 请求 JSON 对象，包含 cliType 字段
     */
    private void handleQueryCliModels(QueryCliModelsRequest request) {
        String cliTypeStr = request.cliType();
        this.asyncExecutor.submit(() -> {
            if (CLIType.OPENCODE.name().equals(cliTypeStr)) {
                List<ModelInfo> models = this.modelConfigService.queryModelsDev();
                this.invokeJSCallback(JsCallback.CLI_MODELS, models);
            } else {
                this.invokeJSCallback(JsCallback.CLI_MODELS, "[]");
            }
        });
    }

    /**
     * 推送 CLI 配置数据到前端。
     */
    private void pushCliConfigs() {
        this.asyncExecutor.submit(() -> {
            try {
                CliConfigs configs = this.cliConfigService.readConfigs();
                var profilesMap = this.loadAllProfiles();
                var resolvedPaths = new java.util.LinkedHashMap<String, String>();
                for (CLIType ct : CLIType.values()) {
                    String detected = ct.detectCommandPath();
                    resolvedPaths.put(ct.name(), detected != null ? detected : "");
                }
                CliConfigsPayload payload = new CliConfigsPayload(
                        configs,
                        this.cliConfigService.getOpenCodeProviders(this.modelConfigService.getDynamicProviders()),
                        profilesMap,
                        resolvedPaths
                );
                this.invokeJSCallback(JsCallback.CLI_CONFIGS, payload);
            } catch (Exception e) {
                log.warn("Failed to read CLI configs", e);
                this.invokeJSCallback(JsCallback.CLI_CONFIGS,
                        new CliConfigsPayload(CliConfigs.empty(), List.of(), java.util.Map.of(), java.util.Map.of()));
            }
        });
    }

    /**
     * 保存 CLI 配置并联动更新默认模型。
     *
     * @param request 保存请求
     */
    private void handleSaveCliConfigs(SaveCliConfigsRequest request) {
        this.asyncExecutor.submit(() -> {
            String cliType = request.cliType();
            try {
                switch (cliType) {
                    case "CLAUDE" -> {
                        ClaudeConfig config = request.claude();
                        if (config == null) {
                            this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        this.cliConfigService.saveClaudeConfig(config);
                        this.cliConfigService.saveCommandPath(cliType, config.commandPath());
                    }
                    case "OPENCODE" -> {
                        OpenCodeConfig config = request.opencode();
                        if (config == null) {
                            this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        this.cliConfigService.saveOpenCodeConfig(config);
                        this.cliConfigService.saveCommandPath(cliType, config.commandPath());
                    }
                    case "CODEX" -> {
                        CodexConfig config = request.codex();
                        if (config == null) {
                            this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                    new CliConfigsSavedPayload(false, cliType, "No config data"));
                            return;
                        }
                        this.cliConfigService.saveCodexConfig(config);
                        this.cliConfigService.saveCommandPath(cliType, config.commandPath());
                    }
                    default -> {
                        this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                                new CliConfigsSavedPayload(false, cliType, "Unknown CLI type"));
                        return;
                    }
                }

                this.persistModelConfigs();
                this.pushModels();
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(true, cliType, ""));
            } catch (Exception e) {
                log.warn("Failed to save CLI configs for {}", cliType, e);
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, e.getMessage()));
            }
        });
    }

    /**
     * 持久化当前模型配置到项目状态。
     */
    private void persistModelConfigs() {
        EasyAgentAppState.getInstance().setModelsJson(this.modelConfigService.toJson());
    }

    // ==================== CLI Profile Management ====================

    /**
     * 从持久化状态加载所有 CLI 配置档案。
     *
     * @return cliType -> List<CliProfile>
     */
    private java.util.Map<String, List<CliProfile>> loadAllProfiles() {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        var result = new java.util.LinkedHashMap<String, List<CliProfile>>();
        for (CLIType cliType : CLIType.values()) {
            String json = appState.getCliProfiles().get(cliType.name());
            result.put(cliType.name(), this.cliConfigService.loadProfiles(json));
        }
        return result;
    }

    /**
     * 持久化指定 CLI 类型的配置档案列表。
     *
     * @param cliType  CLI 类型
     * @param profiles 档案列表
     */
    private void persistProfiles(String cliType,
                                 List<CliProfile> profiles) {
        EasyAgentAppState.getInstance().getCliProfiles().put(cliType,
                this.cliConfigService.serializeProfiles(profiles));
    }

    /**
     * 保存 CLI 配置档案（新增或更新）。
     */
    private void handleSaveCliProfile(SaveCliProfileRequest request) {
        this.asyncExecutor.submit(() -> {
            String cliType = request.cliType();
            CliProfile profile = request.profile();
            if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, "Profile name is required"));
                return;
            }
            if (profile.getId() == null || profile.getId().isBlank()) {
                profile.setId(UUID.randomUUID().toString().substring(0, 8));
            }
            profile.setCliType(cliType);

            var allProfiles = this.loadAllProfiles();
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
            this.persistProfiles(cliType, profiles);
            this.pushCliConfigs();
            this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                    new CliConfigsSavedPayload(true, cliType, ""));
        });
    }

    /**
     * 删除 CLI 配置档案。
     */
    private void handleDeleteCliProfile(DeleteCliProfileRequest request) {
        this.asyncExecutor.submit(() -> {
            String cliType = request.cliType();
            String profileId = request.profileId();

            var allProfiles = this.loadAllProfiles();
            List<CliProfile> profiles = new ArrayList<>(allProfiles.getOrDefault(cliType, List.of()));
            profiles.removeIf(p -> p.getId().equals(profileId));
            this.persistProfiles(cliType, profiles);
            this.pushCliConfigs();
        });
    }

    /**
     * 应用 CLI 配置档案（切换到指定档案的配置）。
     */
    private void handleApplyCliProfile(ApplyCliProfileRequest request) {
        this.asyncExecutor.submit(() -> {
            String cliType = request.cliType();
            String profileId = request.profileId();

            var allProfiles = this.loadAllProfiles();
            List<CliProfile> profiles = allProfiles.getOrDefault(cliType, List.of());
            CliProfile target = null;
            for (CliProfile p : profiles) {
                if (p.getId().equals(profileId)) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, "Profile not found"));
                return;
            }
            try {
                this.cliConfigService.applyProfile(target);
                this.pushCliConfigs();
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(true, cliType, ""));
            } catch (Exception e) {
                this.invokeJSCallback(JsCallback.CLI_CONFIGS_SAVED,
                        new CliConfigsSavedPayload(false, cliType, e.getMessage()));
            }
        });
    }

    /**
     * 处理获取 MCP 配置列表请求。
     *
     * @param request MCP 配置请求
     */
    private void handleGetMcpConfigs(McpConfigsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                String projectPath = this.currentProjectPath;
                List<McpServerEntry> entries = this.mcpConfigService.loadMcpConfigs(cliType, projectPath);
                this.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
            } catch (Exception e) {
                log.error("加载 MCP 配置失败", e);
                this.invokeJSCallback(JsCallback.MCP_CONFIGS, List.of());
            }
        });
    }

    /**
     * 处理保存 MCP 服务器配置请求。
     *
     * @param request 保存 MCP 请求
     */
    private void handleSaveMcpServer(SaveMcpServerRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String projectPath = "project".equals(scope) ? this.currentProjectPath : null;
                McpServerEntry entry = request.toEntry();
                boolean success = this.mcpConfigService.saveMcpServer(cliType, scope, projectPath, entry);
                this.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(success, cliType, success ? "" : "Save failed"));
                if (success) {
                    List<McpServerEntry> entries = this.mcpConfigService.loadMcpConfigs(cliType, this.currentProjectPath);
                    this.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
                }
            } catch (Exception e) {
                log.error("保存 MCP 配置失败", e);
                this.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 处理删除 MCP 服务器配置请求。
     *
     * @param request 删除 MCP 请求
     */
    private void handleDeleteMcpServer(DeleteMcpServerRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String projectPath = "project".equals(scope) ? this.currentProjectPath : null;
                boolean success = this.mcpConfigService.deleteMcpServer(cliType, scope, projectPath, request.serverName());
                this.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(success, cliType, success ? "" : "Delete failed"));
                if (success) {
                    List<McpServerEntry> entries = this.mcpConfigService.loadMcpConfigs(cliType, this.currentProjectPath);
                    this.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
                }
            } catch (Exception e) {
                log.error("删除 MCP 配置失败", e);
                this.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 处理测试连接 MCP 服务器请求。
     * <p>
     * 根据 MCP 配置启动子进程，完成 initialize 握手后返回连接结果和工具列表。
     * </p>
     *
     * @param request 测试连接请求
     */
    private void handleTestMcpConnect(TestMcpConnectRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String serverName = request.serverName();
                String projectPath = "project".equals(scope) ? this.currentProjectPath : null;
                List<McpServerEntry> entries = this.mcpConfigService.loadMcpConfigs(cliType, projectPath);
                McpServerEntry target = null;
                for (McpServerEntry e : entries) {
                    if (e.name().equals(serverName) && e.scope().equals(scope)) {
                        target = e;
                        break;
                    }
                }
                if (target == null) {
                    this.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                            new McpTestConnectedPayload(false, null, serverName, "Server config not found", List.of(), "", Map.of()));
                    return;
                }
                McpTestService.ConnectResult result = this.mcpTestService.connect(target);
                List<McpTestService.ToolInfo> tools = List.of();
                if (result.success()) {
                    tools = this.mcpTestService.listTools(result.connectionId());
                }
                this.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                        new McpTestConnectedPayload(result.success(), result.connectionId(),
                                serverName, result.serverNameOrError(), tools,
                                result.transportType(), result.env()));
            } catch (Exception e) {
                log.error("测试 MCP 连接失败", e);
                this.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                        new McpTestConnectedPayload(false, null, "", e.getMessage(), List.of(), "", Map.of()));
            }
        });
    }

    /**
     * 处理列出 MCP 服务器工具请求。
     *
     * @param request 列出工具请求
     */
    private void handleListMcpTools(ListMcpToolsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                List<McpTestService.ToolInfo> tools = this.mcpTestService.listTools(request.connectionId());
                this.invokeJSCallback(JsCallback.MCP_TOOLS,
                        new McpToolsPayload(request.connectionId(), tools));
            } catch (Exception e) {
                log.error("列出 MCP 工具失败", e);
                this.invokeJSCallback(JsCallback.MCP_TOOLS,
                        new McpToolsPayload(request.connectionId(), List.of()));
            }
        });
    }

    /**
     * 处理调用 MCP 工具请求。
     *
     * @param request 工具调用请求
     */
    private void handleCallMcpTool(CallMcpToolRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
                McpTestService.ToolCallResult result = this.mcpTestService.callTool(
                        request.connectionId(), request.toolName(), args);
                this.invokeJSCallback(JsCallback.MCP_TOOL_RESULT,
                        new McpToolResultPayload(request.connectionId(), request.toolName(), result));
            } catch (Exception e) {
                log.error("调用 MCP 工具失败", e);
                this.invokeJSCallback(JsCallback.MCP_TOOL_RESULT,
                        new McpToolResultPayload(request.connectionId(), request.toolName(),
                                new McpTestService.ToolCallResult(false, e.getMessage(), false, List.of())));
            }
        });
    }

    /**
     * 处理获取 Skills 列表请求。
     *
     * @param request Skills 列表请求
     */
    private void handleGetSkills(GetSkillsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                List<SkillEntry> skills = this.skillsConfigService.loadSkills(request.cliType());
                this.invokeJSCallback(JsCallback.SKILLS, skills);
            } catch (Exception e) {
                log.error("加载 Skills 列表失败", e);
                this.invokeJSCallback(JsCallback.SKILLS, List.of());
            }
        });
    }

    /**
     * 处理从 GitHub 安装 Skill 请求。
     *
     * @param request 安装请求
     */
    private void handleInstallSkill(InstallSkillRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                SkillsConfigService.InstallResult result = this.skillsConfigService.installSkill(
                        cliType, request.githubUrl(), request.skillName(), request.scope());
                this.invokeJSCallback(JsCallback.SKILL_INSTALLED,
                        new SkillActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<SkillEntry> skills = this.skillsConfigService.loadSkills(cliType);
                    this.invokeJSCallback(JsCallback.SKILLS, skills);
                }
            } catch (Exception e) {
                log.error("安装 Skill 失败", e);
                this.invokeJSCallback(JsCallback.SKILL_INSTALLED,
                        new SkillActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 处理删除 Skill 请求。
     *
     * @param request 删除请求
     */
    private void handleDeleteSkill(DeleteSkillRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                SkillsConfigService.DeleteResult result = this.skillsConfigService.deleteSkill(
                        cliType, request.skillName(), request.skillPath());
                this.invokeJSCallback(JsCallback.SKILL_DELETED,
                        new SkillActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<SkillEntry> skills = this.skillsConfigService.loadSkills(cliType);
                    this.invokeJSCallback(JsCallback.SKILLS, skills);
                }
            } catch (Exception e) {
                log.error("删除 Skill 失败", e);
                this.invokeJSCallback(JsCallback.SKILL_DELETED,
                        new SkillActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 处理读取 Skill 内容请求。
     *
     * @param request 内容读取请求
     */
    private void handleReadSkillContent(ReadSkillContentRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String content = this.skillsConfigService.readSkillContent(request.skillPath());
                this.invokeJSCallback(JsCallback.SKILL_CONTENT,
                        new SkillContentPayload(request.skillPath(), content != null ? content : ""));
            } catch (Exception e) {
                log.error("读取 Skill 内容失败", e);
                this.invokeJSCallback(JsCallback.SKILL_CONTENT,
                        new SkillContentPayload(request.skillPath(), ""));
            }
        });
    }

    private void handleListKnownRepos(ListKnownReposRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                var repos = this.skillsConfigService.listKnownRepos(request.cliType());
                this.invokeJSCallback(JsCallback.KNOWN_REPOS, repos);
            } catch (Exception e) {
                log.error("获取已知仓库列表失败", e);
                this.invokeJSCallback(JsCallback.KNOWN_REPOS, List.of());
            }
        });
    }

    private void handleListRemoteSkills(ListRemoteSkillsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                var skills = this.skillsConfigService.listRemoteSkills(request.ownerRepo());
                this.invokeJSCallback(JsCallback.REMOTE_SKILLS, skills);
            } catch (Exception e) {
                log.error("获取远程 Skills 列表失败", e);
                this.invokeJSCallback(JsCallback.REMOTE_SKILLS, List.of());
            }
        });
    }

    private void handleGetPlugins(GetPluginsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                List<PluginEntry> plugins = this.pluginsConfigService.loadInstalledPlugins(request.cliType());
                this.invokeJSCallback(JsCallback.PLUGINS, plugins);
            } catch (Exception e) {
                log.error("加载 Plugins 列表失败", e);
                this.invokeJSCallback(JsCallback.PLUGINS, List.of());
            }
        });
    }

    private void handleInstallPlugin(InstallPluginRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                PluginsConfigService.InstallResult result = this.pluginsConfigService.installPlugin(
                        cliType, request.githubUrl(), request.pluginName(), request.scope());
                this.invokeJSCallback(JsCallback.PLUGIN_INSTALLED,
                        new PluginActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<PluginEntry> plugins = this.pluginsConfigService.loadInstalledPlugins(cliType);
                    this.invokeJSCallback(JsCallback.PLUGINS, plugins);
                }
            } catch (Exception e) {
                log.error("安装 Plugin 失败", e);
                this.invokeJSCallback(JsCallback.PLUGIN_INSTALLED,
                        new PluginActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleDeletePlugin(DeletePluginRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String cliType = request.cliType();
                PluginsConfigService.DeleteResult result = this.pluginsConfigService.deletePlugin(
                        cliType, request.pluginName(), request.installPath());
                this.invokeJSCallback(JsCallback.PLUGIN_DELETED,
                        new PluginActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<PluginEntry> plugins = this.pluginsConfigService.loadInstalledPlugins(cliType);
                    this.invokeJSCallback(JsCallback.PLUGINS, plugins);
                }
            } catch (Exception e) {
                log.error("删除 Plugin 失败", e);
                this.invokeJSCallback(JsCallback.PLUGIN_DELETED,
                        new PluginActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleReadPluginContent(ReadPluginContentRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                String content = this.pluginsConfigService.readPluginContent(request.installPath());
                this.invokeJSCallback(JsCallback.PLUGIN_CONTENT,
                        new PluginContentPayload(request.installPath(), content != null ? content : ""));
            } catch (Exception e) {
                log.error("读取 Plugin 内容失败", e);
                this.invokeJSCallback(JsCallback.PLUGIN_CONTENT,
                        new PluginContentPayload(request.installPath(), ""));
            }
        });
    }

    private void handleReadPluginCommands(ReadPluginContentRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                var commands = this.pluginsConfigService.readPluginCommands(request.installPath());
                this.invokeJSCallback(JsCallback.PLUGIN_COMMANDS,
                        new PluginCommandsPayload(request.installPath(), commands));
            } catch (Exception e) {
                log.error("读取 Plugin 命令列表失败", e);
                this.invokeJSCallback(JsCallback.PLUGIN_COMMANDS,
                        new PluginCommandsPayload(request.installPath(), List.of()));
            }
        });
    }

    private void handleSaveSkillContent(SaveSkillContentRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                boolean success = this.skillsConfigService.saveSkillContent(request.skillPath(), request.content());
                this.invokeJSCallback(JsCallback.SKILL_CONTENT_SAVED,
                        new SaveContentResultPayload(success, request.skillPath()));
            } catch (Exception e) {
                log.error("保存 Skill 内容失败", e);
                this.invokeJSCallback(JsCallback.SKILL_CONTENT_SAVED,
                        new SaveContentResultPayload(false, request.skillPath()));
            }
        });
    }

    private void handleSavePluginContent(SavePluginContentRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                boolean success = this.pluginsConfigService.savePluginContent(request.installPath(), request.content());
                this.invokeJSCallback(JsCallback.PLUGIN_CONTENT_SAVED,
                        new SaveContentResultPayload(success, request.installPath()));
            } catch (Exception e) {
                log.error("保存 Plugin 内容失败", e);
                this.invokeJSCallback(JsCallback.PLUGIN_CONTENT_SAVED,
                        new SaveContentResultPayload(false, request.installPath()));
            }
        });
    }

    private void handleListKnownPluginRepos(ListKnownReposRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                var repos = this.pluginsConfigService.listKnownRepos(request.cliType());
                this.invokeJSCallback(JsCallback.KNOWN_PLUGIN_REPOS, repos);
            } catch (Exception e) {
                log.error("获取已知插件仓库列表失败", e);
                this.invokeJSCallback(JsCallback.KNOWN_PLUGIN_REPOS, List.of());
            }
        });
    }

    private void handleListRemotePlugins(ListRemotePluginsRequest request) {
        this.asyncExecutor.submit(() -> {
            try {
                var plugins = this.pluginsConfigService.listRemotePlugins(request.ownerRepo());
                this.invokeJSCallback(JsCallback.REMOTE_PLUGINS, plugins);
            } catch (Exception e) {
                log.error("获取远程 Plugins 列表失败", e);
                this.invokeJSCallback(JsCallback.REMOTE_PLUGINS, List.of());
            }
        });
    }

    /**
     * 搜索项目内的文件引用候选。
     *
     * @param obj 请求 JSON
     */
    private void handleSearchFileReferences(SearchFileReferencesRequest request) {
        String query = request.query();
        int limit = request.limit() > 0 ? request.limit() : 12;
        String requestId = request.requestId();
        this.asyncExecutor.submit(() -> {
            JCEFMessageBridge.this.invokeJSCallback(JsCallback.FILE_REFERENCE_CANDIDATES,
                    new FileReferenceCandidatesPayload(requestId != null ? requestId : "",
                            this.fileReferenceService != null
                                    ? JCEFMessageBridge.this.fileReferenceService.searchCandidates(query, limit)
                                    : List.of()));
        });
    }

    /**
     * 根据路径生成文件引用并推送到前端输入框。
     *
     * @param filePath 文件绝对路径
     */
    private void handleResolveFileReference(ResolveFileReferenceRequest request) {
        String filePath = request.path();
        if (this.fileReferenceService == null || filePath == null || filePath.isBlank()) {
            return;
        }
        FileReferencePayload reference = this.fileReferenceService.createReference(filePath);
        if (reference != null) {
            this.pushFileReferences(List.of(reference));
        }
    }

    /**
     * 保存前端剪贴板中的图片，并生成输入框引用。
     *
     * @param dataUrl  图片 Data URL
     * @param fileName 建议文件名
     */
    private void handleSaveClipboardImage(SaveClipboardImageRequest request) {
        String dataUrl = request.dataUrl();
        if (this.fileReferenceService == null || dataUrl == null || dataUrl.isBlank()) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            FileReferencePayload reference = this.fileReferenceService.createImageReference(dataUrl, request.fileName());
            if (reference != null) {
                JCEFMessageBridge.this.pushFileReferences(List.of(reference));
            }
        });
    }

    /**
     * 推送文件引用到前端输入框。
     *
     * @param references 文件引用列表
     */
    public void pushFileReferences(List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        this.invokeJSCallback(JsCallback.INSERT_REFERENCES, references);
    }

    /**
     * 打开文件编辑 diff。
     *
     * @param request 请求
     */
    private void handleOpenFileEditDiff(OpenFileEditDiffRequest request) {
        if (request == null || (request.editId() == null || request.editId().isBlank())
                && (request.toolCallId() == null || request.toolCallId().isBlank())
                && (request.path() == null || request.path().isBlank())) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> JCEFMessageBridge.this.fileEditService.openDiff(
                request.editId(), request.toolCallId(), request.path()));
    }

    /**
     * 回撤文件编辑。
     *
     * @param request 请求
     */
    private void handleRevertFileEdit(RevertFileEditRequest request) {
        if (request == null || (request.editId() == null || request.editId().isBlank())
                && (request.toolCallId() == null || request.toolCallId().isBlank())
                && (request.path() == null || request.path().isBlank())) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> JCEFMessageBridge.this.fileEditService.revertEdit(
                request.editId(), request.toolCallId(), request.path()));
    }

    /**
     * 查询指定 CLI 的可用斜杠命令列表。
     *
     * @param request 查询请求
     */
    private void handleGetSlashCommands(GetSlashCommandsRequest request) {
        String cliTypeValue = request.cliType();
        if (cliTypeValue == null || cliTypeValue.isBlank()) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                CLIType cliType = CLIType.valueOf(cliTypeValue);
                this.invokeJSCallback(JsCallback.SLASH_COMMANDS, SlashCommandsPayload.builder()
                        .requestId(request.requestId())
                        .cliType(cliType.name())
                        .commands(this.slashCommandService.listCommands(cliType, this.currentProjectPath))
                        .build());
            } catch (Exception e) {
                log.warn("Failed to load slash commands for {}", cliTypeValue, e);
                this.invokeJSCallback(JsCallback.SLASH_COMMANDS, SlashCommandsPayload.builder()
                        .requestId(request.requestId())
                        .cliType(cliTypeValue)
                        .commands(List.of())
                        .build());
            }
        });
    }

    /**
     * 执行一个斜杠命令并将执行计划回推给前端。
     *
     * @param request 执行请求
     */
    private void handleExecuteSlashCommand(ExecuteSlashCommandRequest request) {
        String cliTypeValue = request.cliType();
        String rawText = request.rawText();
        if (cliTypeValue == null || cliTypeValue.isBlank() || rawText == null || rawText.isBlank()) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                CLIType cliType = CLIType.valueOf(cliTypeValue);
                SlashCommandExecutionPayload payload = this.slashCommandService.executeCommand(
                        cliType, rawText, this.currentProjectPath, request.requestId());
                this.invokeJSCallback(JsCallback.SLASH_COMMAND_EXECUTED, payload);
            } catch (Exception e) {
                log.warn("Failed to execute slash command: {}", rawText, e);
                this.invokeJSCallback(JsCallback.SLASH_COMMAND_EXECUTED, SlashCommandExecutionPayload.builder()
                        .requestId(request.requestId())
                        .cliType(cliTypeValue)
                        .commandName("")
                        .executionType("PASS_THROUGH")
                        .prompt(rawText)
                        .openFreshSession(false)
                        .refreshHistory(false)
                        .toastMessage(null)
                        .build());
            }
        });
    }

    /**
     * 调用前端 JS 全局回调函数。
     * <p>
     * 生成格式：{@code window.__ea_on{callbackName} && window.__ea_on{callbackName}({data})}
     * </p>
     *
     * @param callback 回调枚举
     * @param data     已序列化的 JSON 数据
     */
    private void invokeJSCallback(JsCallback callback, String data) {
        String name = callback.getValue();
        String js = JS_CALLBACK_PREFIX + name + "&&" + JS_CALLBACK_PREFIX + name + "(" + data + ")";
        this.executeJS(js);
    }

    /**
     * 调用前端 JS 全局回调函数。
     *
     * @param callback 回调枚举
     * @param data     待序列化的对象
     */
    private void invokeJSCallback(JsCallback callback, Object data) {
        this.invokeJSCallback(callback, GsonUtils.toJson(data));
    }

    /**
     * 在浏览器中执行 JavaScript 代码。
     *
     * @param js 要执行的 JavaScript 代码
     */
    private void executeJS(String js) {
        try {
            this.browser.getCefBrowser().executeJavaScript(js, "", 0);
        } catch (Exception e) {
            log.warn("Failed to execute JS: {}", e.getMessage());
        }
    }

    /**
     * 将持久化的待发送队列映射转换为前端可消费的列表。
     *
     * @param pendingQueues sessionId -> pendingQueue JSON
     * @return 待发送队列状态列表
     */
    private List<PendingQueueStatePayload> pendingQueueStates(Map<String, String> pendingQueues) {
        if (pendingQueues == null || pendingQueues.isEmpty()) {
            return List.of();
        }
        return pendingQueues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PendingQueueStatePayload(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * CEF 消息路由处理器。
     * <p>
     * 解析前端发送的 JSON 请求，根据 {@code action} 字段分发到对应的处理方法。
     * </p>
     *
     * @author haijun
     * @date 2026/4/30
     * @since 1.0.0
     */
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

        /**
         * 解析并分发前端请求。
         *
         * @param request JSON 格式的请求字符串
         */
        private void handleRequest(String request) {
            if (StringUtil.isEmpty(request)) {
                return;
            }

            ActionRequest envelope = GsonUtils.fromJson(request, ActionRequest.class);
            if (envelope == null || envelope.action() == null) {
                return;
            }

            JsAction jsAction = ValueEnum.fromValue(JsAction.class, envelope.action());
            if (jsAction == null) {
                log.debug("Unknown JS action: {}", envelope.action());
                return;
            }

            QueryHandler<? extends JsRequest> handler = JCEFMessageBridge.this.queryHandlers.get(jsAction);
            if (handler == null) {
                log.debug("No JS handler registered for action: {}", envelope.action());
                return;
            }
            JCEFMessageBridge.this.dispatchRequest(request, handler);
        }
    }

    /**
     * 解析并执行指定请求处理器。
     *
     * @param request  原始 JSON 请求
     * @param handler  请求处理器
     * @param <T>      请求实体类型
     */
    private <T extends JsRequest> void dispatchRequest(String request, QueryHandler<T> handler) {
        T typedRequest = GsonUtils.fromJson(request, handler.requestType());
        if (typedRequest == null) {
            return;
        }
        handler.consumer().accept(typedRequest);
    }

    /**
     * JS 请求基础接口。
     */
    private interface JsRequest {
        /**
         * 获取动作名称。
         *
         * @return 动作名称
         */
        String action();
    }

    /**
     * 请求处理器定义。
     *
     * @param requestType 请求实体类型
     * @param consumer    处理逻辑
     * @param <T>         请求实体类型
     */
    private record QueryHandler<T extends JsRequest>(Class<T> requestType, Consumer<T> consumer) {
    }

    /**
     * 原始动作包装。
     *
     * @param action 动作名称
     */
    private record ActionRequest(String action) implements JsRequest {
    }

    /**
     * 无额外参数的会话列表请求。
     *
     * @param action 动作名称
     */
    private record EmptyRequest(String action) implements JsRequest {
    }

    /**
     * 请求指定 CLI 类型的会话列表。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    private record ListSessionsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 请求历史消息。
     *
     * @param action     动作名称
     * @param sessionId  会话 ID
     * @param cliType    CLI 类型
     */
    private record LoadHistoryRequest(String action, String sessionId, String cliType, boolean forceReload) implements JsRequest {
    }

    /**
     * 发送消息请求。
     *
     * @param action         动作名称
     * @param text           用户文本
     * @param cliType        CLI 类型
     * @param sessionId      会话 ID
     * @param modelId        模型 ID
     * @param fileReferences 文件引用列表
     */
    private record SendMessageRequest(String action, String text, String cliType, String sessionId,
                                      String modelId, String reasoningLevel,
                                      List<FileReferencePayload> fileReferences) implements JsRequest {
    }

    /**
     * 停止生成请求。
     *
     * @param action    动作名称
     * @param sessionId 会话 ID
     */
    private record StopGenerationRequest(String action, String sessionId) implements JsRequest {
    }

    /**
     * 批量删除会话请求。
     *
     * @param action      动作名称
     * @param sessionIds  逗号分隔的会话 ID 列表
     */
    private record DeleteSessionsRequest(String action, String sessionIds) implements JsRequest {
    }

    /**
     * 保存待发送队列请求。
     *
     * @param action       动作名称
     * @param sessionId    会话 ID
     * @param pendingQueue 待发送队列 JSON
     */
    private record SavePendingQueueRequest(String action, String sessionId, String pendingQueue) implements JsRequest {
    }

    /**
     * 保存重试配置请求。
     *
     * @param action         动作名称
     * @param retryMaxCount  最大重试次数
     * @param retryTimeoutMs 超时时间
     */
    private record SaveRetryConfigRequest(String action, int retryMaxCount, long retryTimeoutMs) implements JsRequest {
    }

    /**
     * 保存模型配置请求。
     *
     * @param action 动作名称
     * @param models 模型配置 JSON
     */
    private record SaveModelsRequest(String action, String models) implements JsRequest {
    }

    /**
     * 查询 CLI 可用模型请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    private record QueryCliModelsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 搜索文件引用请求。
     *
     * @param action    动作名称
     * @param query     查询关键字
     * @param limit     最大结果数
     * @param requestId 请求 ID
     */
    private record SearchFileReferencesRequest(String action, String query, int limit, String requestId)
            implements JsRequest {
    }

    /**
     * 解析文件引用请求。
     *
     * @param action 动作名称
     * @param path   文件路径
     */
    private record ResolveFileReferenceRequest(String action, String path) implements JsRequest {
    }

    /**
     * 保存剪贴板图片请求。
     *
     * @param action   动作名称
     * @param dataUrl  图片 data URL
     * @param fileName 文件名
     */
    private record SaveClipboardImageRequest(String action, String dataUrl, String fileName) implements JsRequest {
    }

    /**
     * 打开文件 diff 请求。
     *
     * @param action     动作名称
     * @param editId     编辑 ID
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     */
    private record OpenFileEditDiffRequest(String action, String editId, String toolCallId, String path) implements JsRequest {
    }

    /**
     * 回撤文件编辑请求。
     *
     * @param action     动作名称
     * @param editId     编辑 ID
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     */
    private record RevertFileEditRequest(String action, String editId, String toolCallId, String path) implements JsRequest {
    }

    /**
     * 获取斜杠命令列表请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param requestId 请求 ID
     */
    private record GetSlashCommandsRequest(String action, String cliType, String requestId) implements JsRequest {
    }

    /**
     * 执行斜杠命令请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param rawText   原始命令文本
     * @param requestId 请求 ID
     */
    private record ExecuteSlashCommandRequest(String action, String cliType,
                                              String rawText, String requestId) implements JsRequest {
    }

    /**
     * 主题回调载荷。
     *
     * @param isDark 是否深色主题
     */
    private record ThemePayload(boolean isDark) {
    }

    /**
     * 流结束回调载荷。
     *
     * @param sessionId 会话 ID
     */
    private record StreamCompletePayload(String sessionId) {
    }

    /**
     * 重试配置回调载荷。
     *
     * @param retryMaxCount  最大重试次数
     * @param retryTimeoutMs 超时时间
     */
    private record RetryConfigPayload(int retryMaxCount, long retryTimeoutMs) {
    }

    /**
     * 持久化待发送队列载荷。
     *
     * @param sessionId    会话 ID
     * @param pendingQueue 待发送队列 JSON
     */
    private record PendingQueueStatePayload(String sessionId, String pendingQueue) {
    }

    /**
     * 持久化状态恢复载荷。
     *
     * @param currentSessionId 当前会话 ID
     * @param currentCliType   当前 CLI 类型
     * @param pendingQueues    待发送队列列表
     * @param retryMaxCount    最大重试次数
     * @param retryTimeoutMs   超时时间
     */
    private record RestoredStatePayload(String currentSessionId, String currentCliType,
                                        List<PendingQueueStatePayload> pendingQueues,
                                        int retryMaxCount, long retryTimeoutMs) {
    }

    /**
     * 文件引用候选回调载荷。
     *
     * @param requestId 请求 ID
     * @param results   文件引用候选列表
     */
    private record FileReferenceCandidatesPayload(String requestId, List<FileReferenceCandidatePayload> results) {
    }

    /**
     * 会话删除完成回调载荷。
     *
     * @param deletedCount 删除成功数量
     * @param sessionIds   请求删除的会话 ID
     */
    private record SessionsDeletedPayload(int deletedCount, List<String> sessionIds) {
    }

    /**
     * 保存 CLI 配置请求。
     *
     * @param action   动作名称
     * @param cliType  CLI 类型
     * @param claude   Claude 配置
     * @param opencode OpenCode 配置
     * @param codex    Codex 配置
     */
    private record SaveCliConfigsRequest(String action, String cliType,
                                         ClaudeConfig claude, OpenCodeConfig opencode,
                                         CodexConfig codex) implements JsRequest {
    }

    /**
     * 保存 CLI 配置档案请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     * @param profile 配置档案数据
     */
    private record SaveCliProfileRequest(String action, String cliType,
                                          CliProfile profile) implements JsRequest {
    }

    /**
     * 删除 CLI 配置档案请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param profileId 档案 ID
     */
    private record DeleteCliProfileRequest(String action, String cliType,
                                            String profileId) implements JsRequest {
    }

    /**
     * 应用 CLI 配置档案请求（切换到指定档案）。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param profileId 档案 ID
     */
    private record ApplyCliProfileRequest(String action, String cliType,
                                           String profileId) implements JsRequest {
    }

    /**
     * CLI 配置数据回调载荷。
     *
     * @param configs             CLI 配置集合
     * @param providers           OpenCode 可用 Provider 列表
     * @param profiles            CLI 类型到配置档案列表的映射
     * @param resolvedCommandPaths CLI 类型到自动检测命令路径的映射
     */
    private record CliConfigsPayload(CliConfigs configs,
                                     List<CliConfigService.ProviderInfo> providers,
                                     java.util.Map<String, List<CliProfile>> profiles,
                                     java.util.Map<String, String> resolvedCommandPaths) {
    }

    /**
     * CLI 配置保存结果回调载荷。
     *
     * @param success 是否成功
     * @param cliType CLI 类型
     * @param message 错误信息
     */
    private record CliConfigsSavedPayload(boolean success, String cliType, String message) {
    }

    /**
     * 获取 MCP 配置列表请求。
     *
     * @param action 动作名称
     * @param cliType CLI 类型
     */
    private record McpConfigsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 保存 MCP 服务器配置请求。
     *
     * @param action   动作名称
     * @param cliType  CLI 类型
     * @param scope    作用域：user 或 project
     * @param name     服务器名称
     * @param type     传输类型
     * @param command  启动命令
     * @param args     命令参数
     * @param env      环境变量
     * @param url      服务器地址
     * @param enabled  是否启用
     */
    private record SaveMcpServerRequest(String action, String cliType, String scope,
                                        String name, String type, String command,
                                        List<String> args, Map<String, String> env,
                                        String url, boolean enabled) implements JsRequest {
        /**
         * 转为 {@link McpServerEntry} 实体。
         *
         * @return MCP 服务器条目
         */
        McpServerEntry toEntry() {
            return McpServerEntry.builder()
                    .name(this.name).type(this.type).command(this.command)
                    .args(this.args != null ? this.args : List.of())
                    .env(this.env != null ? this.env : Map.of())
                    .url(this.url).enabled(this.enabled).scope(this.scope)
                    .configPath(null)
                    .build();
        }
    }

    /**
     * 删除 MCP 服务器配置请求。
     *
     * @param action     动作名称
     * @param cliType    CLI 类型
     * @param scope      作用域
     * @param serverName 服务器名称
     */
    private record DeleteMcpServerRequest(String action, String cliType,
                                           String scope, String serverName) implements JsRequest {
    }

    /**
     * MCP 配置保存结果回调载荷。
     *
     * @param success 是否成功
     * @param cliType CLI 类型
     * @param message 错误信息
     */
    private record McpSavedPayload(boolean success, String cliType, String message) {
    }

    /**
     * 测试连接 MCP 服务器请求。
     *
     * @param action     动作名称
     * @param cliType    CLI 类型
     * @param scope      作用域
     * @param serverName 服务器名称
     */
    private record TestMcpConnectRequest(String action, String cliType,
                                         String scope, String serverName) implements JsRequest {
    }

    /**
     * 列出 MCP 工具请求。
     *
     * @param action       动作名称
     * @param connectionId 连接 ID
     */
    private record ListMcpToolsRequest(String action, String connectionId) implements JsRequest {
    }

    /**
     * 调用 MCP 工具请求。
     *
     * @param action       动作名称
     * @param connectionId 连接 ID
     * @param toolName     工具名称
     * @param arguments    工具参数
     */
    private record CallMcpToolRequest(String action, String connectionId,
                                      String toolName,
                                      Map<String, Object> arguments) implements JsRequest {
    }

    /**
     * MCP 测试连接结果回调载荷。
     *
     * @param success      是否成功
     * @param connectionId 连接 ID
     * @param serverName   MCP 服务器名称
     * @param serverInfo   服务器信息或错误信息
     * @param tools        工具列表
     */
    private record McpTestConnectedPayload(boolean success, String connectionId,
                                           String serverName, String serverInfo,
                                           List<McpTestService.ToolInfo> tools,
                                           String transportType,
                                           Map<String, String> env) {
    }

    /**
     * MCP 工具列表回调载荷。
     *
     * @param connectionId 连接 ID
     * @param tools        工具列表
     */
    private record McpToolsPayload(String connectionId,
                                   List<McpTestService.ToolInfo> tools) {
    }

    /**
     * MCP 工具调用结果回调载荷。
     *
     * @param connectionId 连接 ID
     * @param toolName     工具名称
     * @param result       调用结果
     */
    private record McpToolResultPayload(String connectionId, String toolName,
                                        McpTestService.ToolCallResult result) {
    }

    // ==================== Skills Request DTOs ====================

    /**
     * 获取 Skills 列表请求。
     *
     * @param action 动作名称
     * @param cliType CLI 类型
     */
    private record GetSkillsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 从 GitHub 安装 Skill 请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param githubUrl GitHub 仓库地址
     * @param skillName 技能名称（安装后的目录名）
     * @param scope     安装作用域：user 或 project
     */
    private record InstallSkillRequest(String action, String cliType, String githubUrl,
                                        String skillName, String scope) implements JsRequest {
    }

    /**
     * 删除 Skill 请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param skillName skill 名称
     * @param skillPath skill 目录路径
     */
    private record DeleteSkillRequest(String action, String cliType,
                                       String skillName, String skillPath) implements JsRequest {
    }

    /**
     * 读取 Skill 内容请求。
     *
     * @param action    动作名称
     * @param skillPath skill 目录路径
     */
    private record ReadSkillContentRequest(String action, String skillPath) implements JsRequest {
    }

    /**
     * Skill 操作结果回调载荷。
     *
     * @param success 是否成功
     * @param cliType CLI 类型
     * @param message 结果消息
     */
    private record SkillActionPayload(boolean success, String cliType, String message) {
    }

    /**
     * Skill 内容回调载荷。
     *
     * @param skillPath skill 目录路径
     * @param content   SKILL.md 完整内容
     */
    private record SkillContentPayload(String skillPath, String content) {
    }

    private record ListKnownReposRequest(String action, String cliType) implements JsRequest {
    }

    private record ListRemoteSkillsRequest(String action, String ownerRepo) implements JsRequest {
    }

    private record GetPluginsRequest(String action, String cliType) implements JsRequest {
    }

    private record InstallPluginRequest(String action, String cliType, String githubUrl,
                                         String pluginName, String scope) implements JsRequest {
    }

    private record DeletePluginRequest(String action, String cliType,
                                        String pluginName, String installPath) implements JsRequest {
    }

    private record ReadPluginContentRequest(String action, String installPath) implements JsRequest {
    }

    private record ListRemotePluginsRequest(String action, String ownerRepo) implements JsRequest {
    }

    private record PluginActionPayload(boolean success, String cliType, String message) {
    }

    private record PluginContentPayload(String installPath, String content) {
    }

    private record PluginCommandsPayload(String installPath, List<Map<String, String>> commands) {
    }

    private record SaveSkillContentRequest(String action, String skillPath, String content) implements JsRequest {
    }

    private record SavePluginContentRequest(String action, String installPath, String content) implements JsRequest {
    }

    private record SaveContentResultPayload(boolean success, String path) {
    }

    // ==================== Plan Mode Handlers ====================

    private void handleCreatePlan(CreatePlanRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                CLIType cliType = ValueEnum.fromValue(CLIType.class, request.cliType());
                if (cliType == null) {
                    log.warn("[PLAN] Unknown cliType '{}', falling back to CLAUDE", request.cliType());
                    cliType = CLIType.CLAUDE;
                }
                Plan plan = this.planService.createPlan(
                        request.planName(), request.description(), cliType, request.minTaskCount());
                this.invokeJSCallback(JsCallback.PLAN_CREATED, GsonUtils.toJson(plan));
            } catch (Exception e) {
                log.warn("Failed to create plan", e);
            }
        });
    }

    private void handleListPlans() {
        if (this.planService == null) {
            this.invokeJSCallback(JsCallback.PLAN_LIST, JS_EMPTY_ARRAY);
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                List<Plan> plans = this.planService.listPlans();
                this.invokeJSCallback(JsCallback.PLAN_LIST, GsonUtils.toJson(plans));
            } catch (Exception e) {
                log.warn("Failed to list plans", e);
                this.invokeJSCallback(JsCallback.PLAN_LIST, JS_EMPTY_ARRAY);
            }
        });
    }

    private void handleGetPlanDetail(PlanIdRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                Plan plan = this.planService.getPlan(request.planId());
                log.info("[PLAN-DEBUG] getPlanDetail: planId={}, status={}, sessionId={}",
                        request.planId(),
                        plan != null ? plan.status() : "NULL",
                        plan != null ? plan.sessionId() : "NULL");
                List<PlanTask> tasks = this.planService.getTasks(request.planId());
                Map<String, Integer> stats = this.planService.getTaskStats(request.planId());
                Map<String, Object> detail = Map.of(
                        "plan", plan != null ? GsonUtils.toJsonTree(plan) : "",
                        "tasks", GsonUtils.toJsonTree(tasks),
                        "stats", stats
                );
                this.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(detail));
            } catch (Exception e) {
                log.warn("Failed to get plan detail", e);
            }
        });
    }

    private void handleUpdatePlan(UpdatePlanRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                Plan existing = this.planService.getPlan(request.planId());
                if (existing == null) {
                    return;
                }
                Plan updated = Plan.builder()
                        .planId(existing.planId())
                        .projectId(existing.projectId())
                        .planName(request.planName() != null ? request.planName() : existing.planName())
                        .description(request.description() != null ? request.description() : existing.description())
                        .cliType(existing.cliType())
                        .sessionId(existing.sessionId())
                        .minTaskCount(existing.minTaskCount())
                        .executionOverview(existing.executionOverview())
                        .status(existing.status())
                        .createdAt(existing.createdAt())
                        .updatedAt(System.currentTimeMillis())
                        .build();
                this.planService.updatePlan(updated);
                this.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(updated),
                        "tasks", GsonUtils.toJsonTree(this.planService.getTasks(request.planId())),
                        "stats", this.planService.getTaskStats(request.planId())
                )));
            } catch (Exception e) {
                log.warn("Failed to update plan", e);
            }
        });
    }

    private void handleDeletePlan(PlanIdRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                boolean success = this.planService.deletePlan(request.planId());
                this.invokeJSCallback(JsCallback.PLAN_DELETED, GsonUtils.toJson(Map.of(
                        "planId", request.planId(), "success", success
                )));
            } catch (Exception e) {
                log.warn("Failed to delete plan", e);
            }
        });
    }

    private void handleUpdatePlanTask(UpdatePlanTaskRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                PlanTask existing = null;
                List<PlanTask> tasks = this.planService.getTasks(request.planId());
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        existing = t;
                        break;
                    }
                }
                if (existing == null) {
                    return;
                }
                io.github.easyagent.enums.TaskStatus newStatus = request.status() != null
                        ? ValueEnum.fromValue(io.github.easyagent.enums.TaskStatus.class, request.status())
                        : existing.status();
                boolean statusChanged = newStatus != existing.status();
                long startedAt = existing.startedAt() != null ? existing.startedAt() : 0L;
                long completedAt = existing.completedAt() != null ? existing.completedAt() : 0L;
                if (statusChanged && newStatus == io.github.easyagent.enums.TaskStatus.RUNNING) {
                    startedAt = System.currentTimeMillis();
                }
                if (statusChanged && (newStatus == io.github.easyagent.enums.TaskStatus.COMPLETED
                        || newStatus == io.github.easyagent.enums.TaskStatus.FAILED)) {
                    if (request.completedAt() != null) {
                        completedAt = request.completedAt();
                    } else {
                        completedAt = System.currentTimeMillis();
                    }
                }
                if (statusChanged && (newStatus == io.github.easyagent.enums.TaskStatus.PENDING
                        || newStatus == io.github.easyagent.enums.TaskStatus.STOPPED)) {
                    completedAt = 0L;
                }
                PlanTask updated = PlanTask.builder()
                        .taskId(existing.taskId())
                        .planId(existing.planId())
                        .title(request.title() != null ? request.title() : existing.title())
                        .description(request.description() != null ? request.description() : existing.description())
                        .priority(request.priority() != null ? ValueEnum.fromValue(
                                io.github.easyagent.enums.TaskPriority.class, request.priority()) : existing.priority())
                        .status(newStatus)
                        .cliType(request.cliType() != null ? ValueEnum.fromValue(
                                CLIType.class, request.cliType()) : existing.cliType())
                        .modelId(request.modelId() != null ? request.modelId() : existing.modelId())
                        .executeSessionId(existing.executeSessionId())
                        .executePrompt(existing.executePrompt())
                        .sortOrder(request.sortOrder() != null ? request.sortOrder().intValue() : existing.sortOrder())
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .build();
                this.planService.updateTask(request.planId(), updated);
                this.invokeJSCallback(JsCallback.PLAN_TASK_UPDATED, GsonUtils.toJson(updated));
            } catch (Exception e) {
                log.warn("Failed to update plan task", e);
            }
        });
    }

    private void handleExecutePlanTask(ExecutePlanTaskRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                log.info("[PLAN-DEBUG] executePlanTask: planId={}, taskId={}", request.planId(), request.taskId());
                int maxConcurrent = EasyAgentAppState.getInstance().getPlanConcurrentTasks();

                PlanTask targetTask = null;
                List<PlanTask> tasks = this.planService.getTasks(request.planId());
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        targetTask = t;
                        break;
                    }
                }
                if (targetTask == null) {
                    log.warn("[PLAN-DEBUG] executePlanTask: task not found, taskId={}", request.taskId());
                    return;
                }

                int runningCount = (int) tasks.stream()
                        .filter(t -> t.status() == io.github.easyagent.enums.TaskStatus.RUNNING
                                && !t.taskId().equals(request.taskId()))
                        .count();
                if (runningCount >= maxConcurrent) {
                    this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(Map.of(
                            "taskId", request.taskId(),
                            "status", "REJECTED",
                            "reason", "concurrent_limit"
                    )));
                    return;
                }

                Plan plan = this.planService.getPlan(request.planId());
                CLIType cliType = targetTask.cliType() != null ? targetTask.cliType() :
                        (plan != null ? plan.cliType() : CLIType.CLAUDE);

                boolean hasExistingSession = targetTask.executeSessionId() != null;
                String sessionId = hasExistingSession
                        ? targetTask.executeSessionId()
                        : "new-" + System.currentTimeMillis();
                String executionOverview = plan != null ? plan.executionOverview() : null;
                String prompt;
                if (hasExistingSession) {
                    prompt = "请重新执行以下任务：\n\n"
                            + "## 任务\n- 标题：" + targetTask.title() + "\n"
                            + (targetTask.description() != null && !targetTask.description().isBlank()
                            ? "- 描述：" + targetTask.description() + "\n" : "")
                            + "\n请严格按照任务描述执行，完成后汇报执行结果。";
                } else {
                    prompt = this.planService.buildTaskExecutionPrompt(targetTask, executionOverview);
                }

                PlanTask updated = this.planService.updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.RUNNING);
                if (updated != null) {
                    PlanTask withSession = PlanTask.builder()
                            .taskId(updated.taskId())
                            .planId(updated.planId())
                            .title(updated.title())
                            .description(updated.description())
                            .priority(updated.priority())
                            .status(updated.status())
                            .cliType(cliType)
                            .modelId(updated.modelId())
                            .executeSessionId(sessionId)
                            .executePrompt(prompt)
                            .executionSummary(updated.executionSummary())
                            .sortOrder(updated.sortOrder())
                            .startedAt(updated.startedAt())
                            .completedAt(updated.completedAt())
                            .build();
                    this.planService.updateTask(request.planId(), withSession);
                    this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(withSession));
                } else {
                    log.warn("[PLAN-DEBUG] executePlanTask: updateTaskStatus returned null, taskId={}", request.taskId());
                }

                String planId = request.planId();
                String taskId = request.taskId();
                final PlanTask taskRef = targetTask;
                final StringBuilder taskOutputBuilder = new StringBuilder();

                log.info("[PLAN-DEBUG] executePlanTask: calling sendMessage, planId={}, taskId={}, sessionId={}, cliType={}",
                        planId, taskId, sessionId, cliType);
                this.chatManager.sendMessage(prompt, sessionId, cliType, this.currentProjectPath,
                        targetTask.modelId(), null, new StreamEventListener() {
                    private String resolvedSid = sessionId;

                    @Override
                    public void onResponse(AIResponse response) {
                        if (response.sessionId() != null) {
                            String newSid = response.sessionId();
                            if (!newSid.equals(resolvedSid)) {
                                String oldSid = resolvedSid;
                                resolvedSid = newSid;
                                JCEFMessageBridge.this.chatManager.remapProviderSessionId(cliType, oldSid, newSid);
                                JCEFMessageBridge.this.planService.updateTask(planId, PlanTask.builder()
                                        .taskId(taskRef.taskId()).planId(taskRef.planId())
                                        .title(taskRef.title()).description(taskRef.description())
                                        .priority(taskRef.priority())
                                        .status(io.github.easyagent.enums.TaskStatus.RUNNING)
                                        .cliType(cliType).modelId(taskRef.modelId())
                                        .executeSessionId(resolvedSid)
                                        .executePrompt(taskRef.executePrompt())
                                        .executionSummary(taskRef.executionSummary())
                                        .sortOrder(taskRef.sortOrder())
                                        .startedAt(taskRef.startedAt())
                                        .completedAt(taskRef.completedAt())
                                        .build());
                                log.info("[PLAN-DEBUG] SessionId remapped: {} -> {} for taskId={}", oldSid, newSid, taskId);
                            }
                        }
                        if (response.message() != null && response.message().text() != null) {
                            taskOutputBuilder.append(response.message().text());
                        }
                        Map<String, Object> eventMap = new java.util.HashMap<>();
                        eventMap.put("type", response.type().getValue());
                        eventMap.put("planId", planId);
                        eventMap.put("taskId", taskId);
                        if (response.message() != null && response.message().text() != null) {
                            eventMap.put("text", response.message().text());
                        }
                        if (response.message() != null && response.message().messageType() != null) {
                            eventMap.put("messageType", response.message().messageType().getValue());
                        }
                        JCEFMessageBridge.this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS,
                                GsonUtils.toJson(eventMap));
                    }

                    @Override
                    public void onComplete() {
                        List<PlanTask> currentTasks = JCEFMessageBridge.this.planService.getTasks(planId);
                        for (PlanTask t : currentTasks) {
                            if (t.taskId().equals(taskId)) {
                                if (t.status() == io.github.easyagent.enums.TaskStatus.STOPPED) {
                                    log.info("[PLAN-STOP] Task {} already stopped, skipping onComplete", taskId);
                                    return;
                                }
                                break;
                            }
                        }
                        PlanTask completed = JCEFMessageBridge.this.planService.updateTaskStatus(
                                planId, taskId, io.github.easyagent.enums.TaskStatus.COMPLETED);
                        if (completed != null) {
                            JCEFMessageBridge.this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS,
                                    GsonUtils.toJson(completed));
                        }
                        JCEFMessageBridge.this.generateTaskOverview(planId, taskRef, taskOutputBuilder.toString(), true);
                    }

                    @Override
                    public void onError(Exception e) {
                        List<PlanTask> currentTasks = JCEFMessageBridge.this.planService.getTasks(planId);
                        for (PlanTask t : currentTasks) {
                            if (t.taskId().equals(taskId)) {
                                if (t.status() == io.github.easyagent.enums.TaskStatus.STOPPED) {
                                    return;
                                }
                                break;
                            }
                        }
                        JCEFMessageBridge.this.planService.updateTaskStatus(
                                planId, taskId, io.github.easyagent.enums.TaskStatus.FAILED);
                        JCEFMessageBridge.this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS,
                                GsonUtils.toJson(Map.of("taskId", taskId, "status", "FAILED",
                                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error")));
                        JCEFMessageBridge.this.generateTaskOverview(planId, taskRef, taskOutputBuilder.toString(), false);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to execute plan task", e);
                this.planService.updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.FAILED);
                this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(Map.of(
                        "taskId", request.taskId(), "status", "FAILED",
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                )));
            }
        });
    }

    private void handleStopPlanTask(StopPlanTaskRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                List<PlanTask> tasks = this.planService.getTasks(request.planId());
                String executeSessionId = null;
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        executeSessionId = t.executeSessionId();
                        break;
                    }
                }
                log.info("[PLAN-STOP] stopPlanTask: planId={}, taskId={}, executeSessionId={}",
                        request.planId(), request.taskId(), executeSessionId);

                if (executeSessionId != null) {
                    this.chatManager.stopGeneration(executeSessionId);
                    log.info("[PLAN-STOP] Called stopGeneration for session: {}", executeSessionId);
                }

                PlanTask updated = this.planService.updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.STOPPED);
                if (updated != null) {
                    this.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(updated));
                }
            } catch (Exception e) {
                log.warn("[PLAN-STOP] Failed to stop plan task: planId={}, taskId={}",
                        request.planId(), request.taskId(), e);
            }
         });
    }

    /**
     * 子任务完成后生成执行概要并追加到计划总览。
     * <p>
     * 通过计划绑定的主会话（plan.sessionId）调用 AI 生成精简概要，
     * 追加到 plan.executionOverview 字段中。
     * </p>
     *
     * @param planId      计划 ID
     * @param task        已完成的任务
     * @param taskOutput  任务执行输出文本
     * @param success     是否执行成功
     */
    private void generateTaskOverview(String planId, PlanTask task, String taskOutput, boolean success) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                Plan plan = this.planService.getPlan(planId);
                if (plan == null || plan.sessionId() == null) {
                    String fallbackSummary = buildFallbackSummary(task, taskOutput, success);
                    this.appendOverview(planId, plan, fallbackSummary);
                    return;
                }

                String overviewPrompt = this.planService.buildOverviewGenerationPrompt(task, taskOutput, success);
                String overviewSessionId = "overview-" + planId + "-" + task.taskId();
                CLIType cliType = plan.cliType();
                StringBuilder overviewBuilder = new StringBuilder();

                this.chatManager.sendMessage(overviewPrompt, overviewSessionId, cliType, this.currentProjectPath,
                        null, null, new StreamEventListener() {
                    @Override
                    public void onResponse(AIResponse response) {
                        if (response.message() != null && response.message().text() != null) {
                            overviewBuilder.append(response.message().text());
                        }
                    }

                    @Override
                    public void onComplete() {
                        String summary = overviewBuilder.toString().trim();
                        if (summary.isEmpty()) {
                            summary = buildFallbackSummary(task, taskOutput, success);
                        }
                        JCEFMessageBridge.this.appendOverview(planId, plan, summary);
                    }

                    @Override
                    public void onError(Exception e) {
                        log.warn("Failed to generate task overview, using fallback", e);
                        String fallbackSummary = buildFallbackSummary(task, taskOutput, success);
                        JCEFMessageBridge.this.appendOverview(planId, plan, fallbackSummary);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to generate task overview", e);
            }
        });
    }

    /**
     * 构建降级版概要（AI 生成失败时使用）。
     */
    private String buildFallbackSummary(PlanTask task, String taskOutput, boolean success) {
        long duration = 0;
        if (task.startedAt() != null && task.startedAt() > 0) {
            duration = (System.currentTimeMillis() - task.startedAt()) / 1000;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(success ? "✅" : "❌").append(" ").append(task.title()).append("\n");
        sb.append("- 状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("- 耗时: ").append(duration).append("s\n");
        if (!success && taskOutput != null && !taskOutput.isEmpty()) {
            String snippet = taskOutput.length() > 200 ? taskOutput.substring(taskOutput.length() - 200) : taskOutput;
            sb.append("- 原因: ").append(snippet.replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    /**
     * 追加概要到计划的 executionOverview 字段。
     */
    private void appendOverview(String planId, Plan plan, String summary) {
        String existing = plan != null ? plan.executionOverview() : null;
        String newOverview;
        if (existing != null && !existing.isBlank()) {
            newOverview = existing + "\n" + summary;
        } else {
            newOverview = summary;
        }
        if (newOverview.length() > 10000) {
            newOverview = newOverview.substring(newOverview.length() - 10000);
        }
        Plan updatedPlan = Plan.builder()
                .planId(plan.planId())
                .projectId(plan.projectId())
                .planName(plan.planName())
                .description(plan.description())
                .cliType(plan.cliType())
                .sessionId(plan.sessionId())
                .minTaskCount(plan.minTaskCount())
                .executionOverview(newOverview)
                .status(plan.status())
                .createdAt(plan.createdAt())
                .updatedAt(System.currentTimeMillis())
                .build();
        this.planService.updatePlan(updatedPlan);

        List<PlanTask> currentTasks = this.planService.getTasks(planId);
        for (int i = 0; i < currentTasks.size(); i++) {
            PlanTask t = currentTasks.get(i);
            if (t.taskId().equals(planId) || t.executeSessionId() == null) {
                continue;
            }
        }

        this.invokeJSCallback(JsCallback.PLAN_OVERVIEW_UPDATED, GsonUtils.toJson(Map.of(
                "planId", planId,
                "executionOverview", newOverview
        )));
    }

    private void handleAiEditTasks(AiEditTasksRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                List<PlanTask> currentTasks = this.planService.getTasks(request.planId());
                String currentJson = GsonUtils.toJson(currentTasks);
                String prompt = this.planService.buildTaskEditPrompt(currentJson, request.instruction());

                Plan plan = this.planService.getPlan(request.planId());
                CLIType cliType = plan != null ? plan.cliType() : CLIType.CLAUDE;
                String sessionId = "plan-edit-" + request.planId();
                String planId = request.planId();

                StringBuilder responseBuilder = new StringBuilder();
                this.chatManager.sendMessage(prompt, sessionId, cliType, this.currentProjectPath,
                        null, null, new StreamEventListener() {
                    @Override
                    public void onResponse(AIResponse response) {
                        if (response.message() != null && response.message().text() != null) {
                            responseBuilder.append(response.message().text());
                        }
                    }

                    @Override
                    public void onComplete() {
                        String fullResponse = responseBuilder.toString();
                        int startIdx = fullResponse.indexOf("[");
                        int endIdx = fullResponse.lastIndexOf("]");
                        if (startIdx >= 0 && endIdx > startIdx) {
                            String tasksJson = fullResponse.substring(startIdx, endIdx + 1);
                            List<PlanTask> parsed = JCEFMessageBridge.this.planService.parseAndCreateTasks(planId, tasksJson);
                            JCEFMessageBridge.this.invokeJSCallback(JsCallback.PLAN_TASK_UPDATED, GsonUtils.toJson(Map.of(
                                    "planId", planId, "tasks", parsed
                            )));
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        log.warn("Failed to AI edit tasks", e);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to AI edit tasks", e);
            }
        });
    }

    private void handleSavePlanTasks(SavePlanTasksRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                List<PlanTask> tasks = GsonUtils.fromJson(request.tasksJson(),
                        new TypeToken<List<PlanTask>>() {}.getType());
                boolean success = this.planService.saveTasks(request.planId(), tasks);
                this.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(this.planService.getPlan(request.planId())),
                        "tasks", GsonUtils.toJsonTree(this.planService.getTasks(request.planId())),
                        "stats", this.planService.getTaskStats(request.planId())
                )));
            } catch (Exception e) {
                log.warn("Failed to save plan tasks", e);
            }
        });
    }

    private void handleGetPlanConfig() {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        this.invokeJSCallback(JsCallback.PLAN_CONFIG, GsonUtils.toJson(Map.of(
                "planConcurrentTasks", appState.getPlanConcurrentTasks()
        )));
    }

    private void handleSavePlanConfig(SavePlanConfigRequest request) {
        EasyAgentAppState appState = EasyAgentAppState.getInstance();
        int value = Math.max(1, Math.min(5, request.planConcurrentTasks()));
        appState.setPlanConcurrentTasks(value);
        this.invokeJSCallback(JsCallback.PLAN_CONFIG_SAVED, GsonUtils.toJson(Map.of(
                "success", true, "planConcurrentTasks", value
        )));
    }

    private void handleStartPlanSplit(PlanIdRequest request) {
        if (this.planService == null) {
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                Plan plan = this.planService.getPlan(request.planId());
                if (plan == null) {
                    log.warn("[PLAN] startPlanSplit: plan not found, planId={}", request.planId());
                    return;
                }
                log.info("[PLAN] startPlanSplit: planId={}, name={}, cliType={}", request.planId(), plan.planName(), plan.cliType());

                Plan updated = Plan.builder()
                        .planId(plan.planId())
                        .projectId(plan.projectId())
                        .planName(plan.planName())
                        .description(plan.description())
                        .cliType(plan.cliType())
                        .sessionId(plan.sessionId())
                        .minTaskCount(plan.minTaskCount())
                        .executionOverview(plan.executionOverview())
                        .status(PlanStatus.TASK_SPLITTING)
                        .createdAt(plan.createdAt())
                        .updatedAt(System.currentTimeMillis())
                        .build();
                this.planService.updatePlan(updated);

                this.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(updated),
                        "tasks", GsonUtils.toJsonTree(this.planService.getTasks(request.planId())),
                        "stats", this.planService.getTaskStats(request.planId())
                )));

                String prompt = this.planService.buildRequirementPrompt(plan);
                String effectiveSessionId = "new-" + System.currentTimeMillis();
                String planId = plan.planId();
                StringBuilder responseBuilder = new StringBuilder();
                String[] resolvedSessionIdHolder = {null};

                this.chatManager.sendMessage(prompt, effectiveSessionId, plan.cliType(),
                        JCEFMessageBridge.this.currentProjectPath, null, null, new StreamEventListener() {
                            @Override
                            public void onResponse(AIResponse response) {
                                JCEFMessageBridge.this.logAIResponse(response);
                                String resolvedSessionId = response.sessionId() != null
                                        ? response.sessionId() : effectiveSessionId;
                                resolvedSessionIdHolder[0] = resolvedSessionId;
                                if (resolvedSessionId != null
                                        && (plan.sessionId() == null || plan.sessionId().startsWith("plan-"))) {
                                    Plan withSession = Plan.builder()
                                            .planId(plan.planId())
                                            .projectId(plan.projectId())
                                            .planName(plan.planName())
                                            .description(plan.description())
                                            .cliType(plan.cliType())
                                            .sessionId(resolvedSessionId)
                                            .minTaskCount(plan.minTaskCount())
                                            .executionOverview(plan.executionOverview())
                                            .status(PlanStatus.TASK_SPLITTING)
                                            .createdAt(plan.createdAt())
                                            .updatedAt(System.currentTimeMillis())
                                            .build();
                                    JCEFMessageBridge.this.planService.updatePlan(withSession);
                                }
                                if (response.message() != null && response.message().text() != null) {
                                    responseBuilder.append(response.message().text());
                                }
                                if (response.toolCall() != null) {
                                    JCEFMessageBridge.this.fileEditService.trackToolCall(resolvedSessionId, response.toolCall());
                                }
                                String eventJson = MessageConverter.toStreamEventJson(response, resolvedSessionId,
                                        JCEFMessageBridge.this.currentProjectPath);
                                JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_EVENT, eventJson);
                            }

                            @Override
                            public void onComplete() {
                                String fullResponse = responseBuilder.toString();
                                String finalSessionId = resolvedSessionIdHolder[0] != null
                                        ? resolvedSessionIdHolder[0] : plan.sessionId();
                                JCEFMessageBridge.this.tryExtractAndCreateTasks(plan, fullResponse, finalSessionId);
                                String completeSid = finalSessionId != null ? finalSessionId : effectiveSessionId;
                                JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_COMPLETE,
                                        new StreamCompletePayload(completeSid));
                            }

                            @Override
                            public void onError(Exception e) {
                                String errJson = MessageConverter.toErrorJson(e.getMessage(), effectiveSessionId);
                                JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_EVENT, errJson);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to start plan split", e);
            }
        });
    }

    private String extractTaskListJson(String text) {
        if (text == null) {
            return null;
        }
        int startMarker = text.indexOf("---TASK_LIST_START---");
        int endMarker = text.indexOf("---TASK_LIST_END---");
        if (startMarker < 0 || endMarker < 0 || endMarker <= startMarker) {
            int startBracket = text.indexOf("[");
            int endBracket = text.lastIndexOf("]");
            if (startBracket >= 0 && endBracket > startBracket) {
                return text.substring(startBracket, endBracket + 1);
            }
            return null;
        }
        String json = text.substring(startMarker + "---TASK_LIST_START---".length(), endMarker).trim();
        if (json.startsWith("[") && json.endsWith("]")) {
            return json;
        }
        int startBracket = json.indexOf("[");
        int endBracket = json.lastIndexOf("]");
        if (startBracket >= 0 && endBracket > startBracket) {
            return json.substring(startBracket, endBracket + 1);
        }
        return null;
    }

    // ==================== Plan Mode Request DTOs ====================

    private record CreatePlanRequest(String action, String planName, String description,
                                      String cliType, int minTaskCount) implements JsRequest {
    }

    private record PlanIdRequest(String action, String planId) implements JsRequest {
    }

    private record UpdatePlanRequest(String action, String planId,
                                      String planName, String description) implements JsRequest {
    }

    private record UpdatePlanTaskRequest(String action, String planId, String taskId,
                                          String title, String description, String priority,
                                          String cliType, String modelId, String status,
                                          Long completedAt, Long sortOrder) implements JsRequest {
    }

    private record ExecutePlanTaskRequest(String action, String planId, String taskId) implements JsRequest {
    }

    private record StopPlanTaskRequest(String action, String planId, String taskId) implements JsRequest {
    }

    private record AiEditTasksRequest(String action, String planId, String instruction) implements JsRequest {
    }

    private record SavePlanTasksRequest(String action, String planId, String tasksJson) implements JsRequest {
    }

    private record SavePlanConfigRequest(String action, int planConcurrentTasks) implements JsRequest {
    }
}
