package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.EasyAgentState;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.CommonRequests;
import io.github.easyagent.ui.service.entity.SlashCommandExecutionPayload;
import io.github.easyagent.ui.service.entity.SlashCommandsPayload;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 会话管理 handler，负责会话列表推送、历史加载、删除、状态持久化、
 * 斜杠命令和重试配置。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
@Slf4j
public class SessionHandler implements MessageHandler {

    private static final String JS_EMPTY_ARRAY = "[]";

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.LIST_ALL_SESSIONS, CommonRequests.ActionRequest.class,
                request -> this.pushAllSessions(ctx));
        ctx.registerHandler(handlers, JsAction.LIST_SESSIONS, CommonRequests.ListSessionsRequest.class,
                request -> this.pushSessionList(ctx, request.cliType()));
        ctx.registerHandler(handlers, JsAction.LOAD_HISTORY, CommonRequests.LoadHistoryRequest.class,
                request -> this.loadHistory(ctx, request.sessionId(), request.cliType(), request.forceReload()));
        ctx.registerHandler(handlers, JsAction.DELETE_SESSIONS, CommonRequests.DeleteSessionsRequest.class,
                request -> this.handleDeleteSessions(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_PENDING_QUEUE, CommonRequests.SavePendingQueueRequest.class,
                request -> this.handleSavePendingQueue(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_PANE_LAYOUT, CommonRequests.SavePaneLayoutRequest.class,
                request -> this.handleSavePaneLayout(ctx, request));
        ctx.registerHandler(handlers, JsAction.GET_AVAILABLE_CLIS, CommonRequests.ActionRequest.class,
                request -> this.pushAvailableCLIs(ctx));
        ctx.registerHandler(handlers, JsAction.PAGE_READY, CommonRequests.ActionRequest.class,
                request -> this.pushInitialData(ctx));
        ctx.registerHandler(handlers, JsAction.GET_RETRY_CONFIG, CommonRequests.ActionRequest.class,
                request -> this.pushRetryConfig(ctx));
        ctx.registerHandler(handlers, JsAction.SAVE_RETRY_CONFIG, CommonRequests.SaveRetryConfigRequest.class,
                request -> this.handleSaveRetryConfig(ctx, request));
        ctx.registerHandler(handlers, JsAction.GET_SLASH_COMMANDS, CommonRequests.GetSlashCommandsRequest.class,
                request -> this.handleGetSlashCommands(ctx, request));
        ctx.registerHandler(handlers, JsAction.EXECUTE_SLASH_COMMAND, CommonRequests.ExecuteSlashCommandRequest.class,
                request -> this.handleExecuteSlashCommand(ctx, request));
    }

    public void pushAvailableCLIs(BridgeContext ctx) {
        List<SessionService.CLIDescriptor> clis = ctx.sessionService().listAvailableCLIs();
        ctx.invokeJSCallback(JsCallback.AVAILABLE_CLIS, GsonUtils.toJson(clis));
    }

    public void pushInitialData(BridgeContext ctx) {
        this.pushRestoredState(ctx);
        ctx.invokeJSCallback(JsCallback.STATE_RESTORED, "{}");
    }

    private void pushRestoredState(BridgeContext ctx) {
        EasyAgentState state = ctx.getState();
        if (state == null) {
            return;
        }
        EasyAgentAppState appState = ctx.getAppState();
        ctx.invokeJSCallback(JsCallback.STATE_RESTORED, new CallbackPayloads.RestoredStatePayload(
                state.getCurrentSessionId() != null ? state.getCurrentSessionId() : "",
                appState.getCurrentCliType() != null ? appState.getCurrentCliType() : "",
                this.pendingQueueStates(state.getPendingQueues()),
                appState.getRetryMaxCount(),
                appState.getRetryTimeoutMs(),
                state.getPaneLayoutJson() != null ? state.getPaneLayoutJson() : ""
        ));
    }

    private void pushRetryConfig(BridgeContext ctx) {
        EasyAgentAppState appState = ctx.getAppState();
        ctx.invokeJSCallback(JsCallback.RETRY_CONFIG,
                new CallbackPayloads.RetryConfigPayload(appState.getRetryMaxCount(), appState.getRetryTimeoutMs()));
    }

    private void handleSaveRetryConfig(BridgeContext ctx, CommonRequests.SaveRetryConfigRequest request) {
        EasyAgentAppState appState = ctx.getAppState();
        appState.setRetryMaxCount(request.retryMaxCount());
        appState.setRetryTimeoutMs(request.retryTimeoutMs());
        ctx.chatManager().updateRetryConfig(request.retryMaxCount(), request.retryTimeoutMs());
    }

    private void handleGetSlashCommands(BridgeContext ctx, CommonRequests.GetSlashCommandsRequest request) {
        String cliTypeValue = request.cliType();
        if (cliTypeValue == null || cliTypeValue.isBlank()) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                CLIType cliType = CLIType.valueOf(cliTypeValue);
                ctx.invokeJSCallback(JsCallback.SLASH_COMMANDS, SlashCommandsPayload.builder()
                        .requestId(request.requestId())
                        .cliType(cliType.name())
                        .commands(ctx.slashCommandService().listCommands(cliType, ctx.getProjectPath()))
                        .build());
            } catch (Exception e) {
                log.warn("Failed to load slash commands for {}", cliTypeValue, e);
                ctx.invokeJSCallback(JsCallback.SLASH_COMMANDS, SlashCommandsPayload.builder()
                        .requestId(request.requestId())
                        .cliType(cliTypeValue)
                        .commands(List.of())
                        .build());
            }
        });
    }

    private void handleExecuteSlashCommand(BridgeContext ctx, CommonRequests.ExecuteSlashCommandRequest request) {
        String cliTypeValue = request.cliType();
        String rawText = request.rawText();
        if (cliTypeValue == null || cliTypeValue.isBlank() || rawText == null || rawText.isBlank()) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                CLIType cliType = CLIType.valueOf(cliTypeValue);
                SlashCommandExecutionPayload payload = ctx.slashCommandService().executeCommand(
                        cliType, rawText, ctx.getProjectPath(), request.requestId());
                ctx.invokeJSCallback(JsCallback.SLASH_COMMAND_EXECUTED, payload);
            } catch (Exception e) {
                log.warn("Failed to execute slash command: {}", rawText, e);
                ctx.invokeJSCallback(JsCallback.SLASH_COMMAND_EXECUTED, SlashCommandExecutionPayload.builder()
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

    private void pushAllSessions(BridgeContext ctx) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String projectPath = ctx.getProjectPath();
                List<SessionInfo> sessions = projectPath != null
                        ? ctx.sessionService().listAllSessions(projectPath)
                        : ctx.sessionService().listAllSessions();
                ctx.invokeJSCallback(JsCallback.SESSION_LIST, GsonUtils.toJson(sessions));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SESSION_LIST, JS_EMPTY_ARRAY);
            }
        });
    }

    private void pushSessionList(BridgeContext ctx, String cliType) {
        ctx.asyncExecutor().submit(() -> {
            try {
                CLIType type = CLIType.valueOf(cliType);
                String projectPath = ctx.getProjectPath();
                List<SessionInfo> sessions = projectPath != null
                        ? ctx.sessionService().listSessions(type, projectPath)
                        : ctx.sessionService().listSessions(type);
                ctx.invokeJSCallback(JsCallback.SESSION_LIST, GsonUtils.toJson(sessions));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SESSION_LIST, JS_EMPTY_ARRAY);
            }
        });
    }

    private void loadHistory(BridgeContext ctx, String sessionId, String cliType, boolean forceReload) {
        ctx.asyncExecutor().submit(() -> {
            try {
                CLIType type = CLIType.valueOf(cliType);
                String projectPath = ctx.getProjectPath();
                String json = ctx.chatManager().loadHistory(sessionId, type, projectPath);
                ctx.historyCache().put(sessionId, json);
                ctx.invokeJSCallback(JsCallback.HISTORY_LOADED, json);
                this.persistCurrentSession(ctx, sessionId, cliType);
            } catch (Exception e) {
                // silently ignore
            }
        });
    }

    private void handleDeleteSessions(BridgeContext ctx, CommonRequests.DeleteSessionsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String[] ids = request.sessionIds().split(",");
                int deleted = ctx.sessionService().deleteSessions(List.of(ids));
                ctx.invokeJSCallback(JsCallback.SESSIONS_DELETED,
                        new CallbackPayloads.SessionsDeletedPayload(deleted, List.of(ids)));
            } catch (Exception e) {
                // silently ignore
            }
        });
    }

    private void handleSavePendingQueue(BridgeContext ctx, CommonRequests.SavePendingQueueRequest request) {
        EasyAgentState state = ctx.getState();
        if (state != null) {
            state.savePendingQueue(request.sessionId(), request.pendingQueue());
        }
    }

    private void handleSavePaneLayout(BridgeContext ctx, CommonRequests.SavePaneLayoutRequest request) {
        EasyAgentState state = ctx.getState();
        if (state != null) {
            state.setPaneLayoutJson(request.paneLayoutJson());
        }
    }

    private void persistCurrentSession(BridgeContext ctx, String sessionId, String cliType) {
        EasyAgentState state = ctx.getState();
        if (state != null) {
            state.setCurrentSessionId(sessionId);
        }
        EasyAgentAppState.getInstance().setCurrentCliType(cliType);
    }

    private List<CallbackPayloads.PendingQueueStatePayload> pendingQueueStates(Map<String, String> pendingQueues) {
        if (pendingQueues == null || pendingQueues.isEmpty()) {
            return List.of();
        }
        return pendingQueues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new CallbackPayloads.PendingQueueStatePayload(entry.getKey(), entry.getValue()))
                .toList();
    }
}
