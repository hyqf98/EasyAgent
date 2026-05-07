package io.github.easyagent.ui.jcef;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ui.StartupUiUtil;
import com.intellij.ui.jcef.JBCefBrowser;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.settings.EasyAgentState;
import io.github.easyagent.settings.models.ModelConfigService;
import io.github.easyagent.settings.models.ModelInfo;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.enums.ThemeType;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.ChatManager;
import io.github.easyagent.ui.service.FileEditService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.MessageConverter;
import io.github.easyagent.ui.service.entity.FileReferenceCandidatePayload;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import io.github.easyagent.ui.service.entity.SlashCommandExecutionPayload;
import io.github.easyagent.ui.service.entity.SlashCommandsPayload;
import io.github.easyagent.ui.service.command.SlashCommandService;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final Project project;

    private final FileReferenceService fileReferenceService;

    private final ChatUiBridgeService chatUiBridgeService;

    private final FileEditService fileEditService;

    private final SlashCommandService slashCommandService;

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
        this.project = project;
        this.fileReferenceService = project != null ? project.getService(FileReferenceService.class) : null;
        this.chatUiBridgeService = project != null ? project.getService(ChatUiBridgeService.class) : null;
        this.fileEditService = new FileEditService(project, project != null ? project.getBasePath() : null);
        this.slashCommandService = new SlashCommandService();
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
        if (this.project != null) {
            EasyAgentState state = EasyAgentState.getInstance(this.project);
            String saved = state.getModelsJson();
            if (saved != null && !saved.isBlank()) {
                this.modelConfigService.loadFromJson(saved);
                return;
            }
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
                request -> this.loadHistory(request.sessionId(), request.cliType()));
        this.registerHandler(JsAction.SEND_MESSAGE, SendMessageRequest.class, request -> this.sendUserMessage(
                request.text(), request.cliType(), request.sessionId(), request.modelId(), request.fileReferences()));
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
                request -> this.handleOpenFileEditDiff(request.editId()));
        this.registerHandler(JsAction.REVERT_FILE_EDIT, RevertFileEditRequest.class,
                request -> this.handleRevertFileEdit(request.editId()));
        this.registerHandler(JsAction.GET_SLASH_COMMANDS, GetSlashCommandsRequest.class,
                this::handleGetSlashCommands);
        this.registerHandler(JsAction.EXECUTE_SLASH_COMMAND, ExecuteSlashCommandRequest.class,
                this::handleExecuteSlashCommand);
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
        ThemeType theme = ThemeType.fromDark(StartupUiUtil.isUnderDarcula());
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
        this.currentSessionId = sessionId;
        String cached = this.historyCache.get(sessionId);
        if (cached != null) {
            this.invokeJSCallback(JsCallback.HISTORY_LOADED, cached);
            this.persistCurrentSession(sessionId, cliType);
            return;
        }
        this.asyncExecutor.submit(() -> {
            try {
                CLIType type = CLIType.valueOf(cliType);
                String json = this.chatManager.loadHistory(sessionId, type, this.currentProjectPath);
                this.historyCache.put(sessionId, json);
                this.invokeJSCallback(JsCallback.HISTORY_LOADED, json);
                this.persistCurrentSession(sessionId, cliType);
            } catch (Exception e) {
                log.warn("Failed to load history for session {}", sessionId, e);
            }
        });
    }

    /**
     * 持久化当前会话 ID 和 CLI 类型。
     *
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型名称
     */
    private void persistCurrentSession(String sessionId, String cliType) {
        if (this.project != null) {
            EasyAgentState state = EasyAgentState.getInstance(this.project);
            state.setCurrentSessionId(sessionId);
            state.setCurrentCliType(cliType);
        }
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
                                 List<FileReferencePayload> fileReferences) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            String effectiveSessionId = StringUtil.isNotEmpty(sessionId)
                    ? sessionId
                    : "new-" + System.currentTimeMillis();
            String prompt = this.fileReferenceService != null
                    ? this.fileReferenceService.enrichPrompt(text, fileReferences)
                    : text;

            this.currentSessionId = effectiveSessionId;

            this.chatManager.sendMessage(prompt, effectiveSessionId, type,
                    this.currentProjectPath, modelId, new StreamEventListener() {
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
        if (this.project == null) return;
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
        if (this.project == null) return;
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        this.invokeJSCallback(JsCallback.STATE_RESTORED, new RestoredStatePayload(
                state.getCurrentSessionId() != null ? state.getCurrentSessionId() : "",
                state.getCurrentCliType() != null ? state.getCurrentCliType() : "",
                this.pendingQueueStates(state.getPendingQueues()),
                state.getRetryMaxCount(),
                state.getRetryTimeoutMs()
        ));
    }

    /**
     * 推送当前 AI 重试策略配置到前端。
     */
    private void pushRetryConfig() {
        if (this.project == null) return;
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        this.invokeJSCallback(JsCallback.RETRY_CONFIG,
                new RetryConfigPayload(state.getRetryMaxCount(), state.getRetryTimeoutMs()));
    }

    /**
     * 保存 AI 重试策略配置。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleSaveRetryConfig(SaveRetryConfigRequest request) {
        if (this.project == null) return;
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        int maxCount = request.retryMaxCount();
        long timeoutMs = request.retryTimeoutMs();
        state.setRetryMaxCount(maxCount);
        state.setRetryTimeoutMs(timeoutMs);
        this.chatManager.updateRetryConfig(maxCount, timeoutMs);
    }

    /**
     * 推送模型配置列表到前端。
     * <p>
     * 包含模型列表和每种 CLI 类型的默认模型配置。
     * </p>
     */
    private void pushModels() {
        String json = this.modelConfigService.toJson();
        this.invokeJSCallback(JsCallback.MODELS, json);
    }

    /**
     * 从远程同步最新的模型配置，并查询所有 CLI 可用模型。
     */
    private void handleSyncModels() {
        this.asyncExecutor.submit(() -> {
            String json = this.modelConfigService.syncFromRemote();
            if (json != null && this.project != null) {
                EasyAgentState state = EasyAgentState.getInstance(this.project);
                state.setModelsJson(json);
            }
            List<ModelInfo> cliModels = this.modelConfigService.queryOpenCodeModels();
            if (!cliModels.isEmpty()) {
                this.modelConfigService.mergeModels(cliModels);
                if (this.project != null) {
                    EasyAgentState state = EasyAgentState.getInstance(this.project);
                    state.setModelsJson(this.modelConfigService.toJson());
                }
            }
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
        if (this.project != null) {
            EasyAgentState state = EasyAgentState.getInstance(this.project);
            state.setModelsJson(this.modelConfigService.toJson());
        }
    }

    /**
     * 查询指定 CLI 的可用模型列表。
     *
     * @param obj 请求 JSON 对象，包含 cliType 字段
     */
    private void handleQueryCliModels(QueryCliModelsRequest request) {
        String cliTypeStr = request.cliType();
        this.asyncExecutor.submit(() -> {
            if (CLIType.OPENCODE.name().equals(cliTypeStr)) {
                List<ModelInfo> models = this.modelConfigService.queryOpenCodeModels();
                this.invokeJSCallback(JsCallback.CLI_MODELS, models);
            } else {
                this.invokeJSCallback(JsCallback.CLI_MODELS, "[]");
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
     * @param editId 编辑 ID
     */
    private void handleOpenFileEditDiff(String editId) {
        if (editId == null || editId.isBlank()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> JCEFMessageBridge.this.fileEditService.openDiff(editId));
    }

    /**
     * 回撤文件编辑。
     *
     * @param editId 编辑 ID
     */
    private void handleRevertFileEdit(String editId) {
        if (editId == null || editId.isBlank()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> JCEFMessageBridge.this.fileEditService.revertEdit(editId));
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
                handleRequest(request);
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

            JsAction jsAction = JsAction.fromValue(envelope.action());
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
    private record LoadHistoryRequest(String action, String sessionId, String cliType) implements JsRequest {
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
                                      String modelId, List<FileReferencePayload> fileReferences) implements JsRequest {
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
     * @param action 动作名称
     * @param editId 编辑 ID
     */
    private record OpenFileEditDiffRequest(String action, String editId) implements JsRequest {
    }

    /**
     * 回撤文件编辑请求。
     *
     * @param action 动作名称
     * @param editId 编辑 ID
     */
    private record RevertFileEditRequest(String action, String editId) implements JsRequest {
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
}
