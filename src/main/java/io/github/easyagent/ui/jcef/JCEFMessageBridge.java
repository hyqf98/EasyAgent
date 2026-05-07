package io.github.easyagent.ui.jcef;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
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
import io.github.easyagent.ui.service.ChatManager;
import io.github.easyagent.ui.service.MessageConverter;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import javax.swing.UIManager;
import java.util.Arrays;
import java.util.List;
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

    /** JSON 请求字段名：action。 */
    private static final String FIELD_ACTION = "action";

    /** JSON 请求字段名：cliType。 */
    private static final String FIELD_CLI_TYPE = "cliType";

    /** JSON 请求字段名：sessionId。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** JSON 请求字段名：text。 */
    private static final String FIELD_TEXT = "text";

    /** JSON 请求字段名：sessionIds。 */
    private static final String FIELD_SESSION_IDS = "sessionIds";

    /** JSON 请求字段名：pendingQueue。 */
    private static final String FIELD_PENDING_QUEUE = "pendingQueue";

    /** JSON 请求字段名：retryMaxCount。 */
    private static final String FIELD_RETRY_MAX_COUNT = "retryMaxCount";

    /** JSON 请求字段名：retryTimeoutMs。 */
    private static final String FIELD_RETRY_TIMEOUT_MS = "retryTimeoutMs";

    /** JSON 请求字段名：modelId。 */
    private static final String FIELD_MODEL_ID = "modelId";

    private final JBCefBrowser browser;

    private final ChatManager chatManager;

    private final SessionService sessionService;

    private final ModelConfigService modelConfigService;

    private final Project project;

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
        ThemeType theme = ThemeType.fromLafName(UIManager.getLookAndFeel().getName());
        this.invokeJSCallback(JsCallback.THEME_CHANGED, "{isDark:" + theme.isDark() + "}");
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
        });
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
                String json = this.chatManager.loadHistory(sessionId, type);
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
    private void sendUserMessage(String text, String cliType, String sessionId, String modelId) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            String effectiveSessionId = sessionId != null ? sessionId : this.currentSessionId;

            this.chatManager.sendMessage(text, effectiveSessionId, type, modelId, new StreamEventListener() {
                @Override
                public void onResponse(AIResponse response) {
                    JCEFMessageBridge.this.logAIResponse(response);
                    String eventJson = MessageConverter.toStreamEventJson(response);
                    JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_EVENT, eventJson);
                    if (response.sessionId() != null) {
                        JCEFMessageBridge.this.currentSessionId = response.sessionId();
                    }
                }

                @Override
                public void onComplete() {
                    JCEFMessageBridge.this.invokeJSCallback(JsCallback.STREAM_COMPLETE, "");
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
    private void handleDeleteSessions(JsonObject obj) {
        String idsStr = GsonUtils.getString(obj, FIELD_SESSION_IDS);
        if (idsStr == null || idsStr.isBlank()) {
            return;
        }
        List<String> sessionIds = Arrays.asList(idsStr.split(","));
        sessionIds.forEach(this.historyCache::remove);
        this.sessionService.deleteSessions(sessionIds);
        this.pushAllSessions();
    }

    /**
     * 保存指定会话的待发送队列到项目级持久化。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleSavePendingQueue(JsonObject obj) {
        if (this.project == null) return;
        String sessionId = GsonUtils.getString(obj, FIELD_SESSION_ID);
        String pendingJson = GsonUtils.getString(obj, FIELD_PENDING_QUEUE);
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
        JsonObject data = new JsonObject();
        data.addProperty("currentSessionId", state.getCurrentSessionId() != null ? state.getCurrentSessionId() : "");
        data.addProperty("currentCliType", state.getCurrentCliType() != null ? state.getCurrentCliType() : "");
        data.add("pendingQueues", GsonUtils.toJsonTree(state.getPendingQueues()));
        data.addProperty("retryMaxCount", state.getRetryMaxCount());
        data.addProperty("retryTimeoutMs", state.getRetryTimeoutMs());
        this.invokeJSCallback(JsCallback.STATE_RESTORED, GsonUtils.toJson(data));
    }

    /**
     * 推送当前 AI 重试策略配置到前端。
     */
    private void pushRetryConfig() {
        if (this.project == null) return;
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        JsonObject data = new JsonObject();
        data.addProperty("retryMaxCount", state.getRetryMaxCount());
        data.addProperty("retryTimeoutMs", state.getRetryTimeoutMs());
        this.invokeJSCallback(JsCallback.RETRY_CONFIG, GsonUtils.toJson(data));
    }

    /**
     * 保存 AI 重试策略配置。
     *
     * @param obj 请求 JSON 对象
     */
    private void handleSaveRetryConfig(JsonObject obj) {
        if (this.project == null) return;
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        int maxCount = GsonUtils.getInt(obj, FIELD_RETRY_MAX_COUNT, 0);
        long timeoutMs = GsonUtils.getLong(obj, FIELD_RETRY_TIMEOUT_MS, 0);
        state.setRetryMaxCount(maxCount);
        state.setRetryTimeoutMs(timeoutMs);
        this.chatManager.updateRetryConfig(maxCount, timeoutMs);
    }

    /**
     * 推送模型配置列表到前端。
     */
    private void pushModels() {
        String json = GsonUtils.toJson(this.modelConfigService.getAllModels());
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
    private void handleSaveModels(JsonObject obj) {
        String modelsJson = GsonUtils.getString(obj, "models");
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
    private void handleQueryCliModels(JsonObject obj) {
        String cliTypeStr = GsonUtils.getString(obj, FIELD_CLI_TYPE);
        this.asyncExecutor.submit(() -> {
            if (CLIType.OPENCODE.name().equals(cliTypeStr)) {
                List<ModelInfo> models = this.modelConfigService.queryOpenCodeModels();
                String json = GsonUtils.toJson(models);
                this.invokeJSCallback(JsCallback.CLI_MODELS, json);
            } else {
                this.invokeJSCallback(JsCallback.CLI_MODELS, "[]");
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
     * @param data     要传递的 JSON 数据
     */
    private void invokeJSCallback(JsCallback callback, String data) {
        String name = callback.getValue();
        String js = JS_CALLBACK_PREFIX + name + "&&" + JS_CALLBACK_PREFIX + name + "(" + data + ")";
        this.executeJS(js);
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

            JsonObject obj = GsonUtils.parseObject(request);
            String action = GsonUtils.getString(obj, FIELD_ACTION);
            if (action == null) {
                return;
            }

            JsAction jsAction = JsAction.fromValue(action);
            if (jsAction == null) {
                log.debug("Unknown JS action: {}", action);
                return;
            }

            switch (jsAction) {
                case LIST_ALL_SESSIONS -> JCEFMessageBridge.this.pushAllSessions();
                case LIST_SESSIONS -> JCEFMessageBridge.this.pushSessionList(
                        GsonUtils.getString(obj, FIELD_CLI_TYPE));
                case LOAD_HISTORY -> JCEFMessageBridge.this.loadHistory(
                        GsonUtils.getString(obj, FIELD_SESSION_ID),
                        GsonUtils.getString(obj, FIELD_CLI_TYPE));
                case SEND_MESSAGE -> JCEFMessageBridge.this.sendUserMessage(
                        GsonUtils.getString(obj, FIELD_TEXT),
                        GsonUtils.getString(obj, FIELD_CLI_TYPE),
                        GsonUtils.getString(obj, FIELD_SESSION_ID),
                        GsonUtils.getString(obj, FIELD_MODEL_ID));
                case STOP_GENERATION -> JCEFMessageBridge.this.chatManager.stopGeneration(
                        GsonUtils.getString(obj, FIELD_SESSION_ID));
                case GET_THEME -> JCEFMessageBridge.this.sendThemeUpdate();
                case GET_AVAILABLE_CLIS -> JCEFMessageBridge.this.pushAvailableCLIs();
                case PAGE_READY -> JCEFMessageBridge.this.pushInitialData();
                case DELETE_SESSIONS -> JCEFMessageBridge.this.handleDeleteSessions(obj);
                case SAVE_PENDING_QUEUE -> JCEFMessageBridge.this.handleSavePendingQueue(obj);
                case GET_RETRY_CONFIG -> JCEFMessageBridge.this.pushRetryConfig();
                case SAVE_RETRY_CONFIG -> JCEFMessageBridge.this.handleSaveRetryConfig(obj);
                case GET_MODELS -> JCEFMessageBridge.this.pushModels();
                case SYNC_MODELS -> JCEFMessageBridge.this.handleSyncModels();
                case SAVE_MODELS -> JCEFMessageBridge.this.handleSaveModels(obj);
                case QUERY_CLI_MODELS -> JCEFMessageBridge.this.handleQueryCliModels(obj);
            }
        }
    }
}
