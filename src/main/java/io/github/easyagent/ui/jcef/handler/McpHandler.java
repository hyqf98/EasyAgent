package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.settings.mcp.McpServerEntry;
import io.github.easyagent.settings.mcp.McpTestService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.McpSavedPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.McpTestConnectedPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.McpToolResultPayload;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads.McpToolsPayload;
import io.github.easyagent.ui.jcef.dto.McpRequests.CallMcpToolRequest;
import io.github.easyagent.ui.jcef.dto.McpRequests.DeleteMcpServerRequest;
import io.github.easyagent.ui.jcef.dto.McpRequests.ListMcpToolsRequest;
import io.github.easyagent.ui.jcef.dto.McpRequests.McpConfigsRequest;
import io.github.easyagent.ui.jcef.dto.McpRequests.SaveMcpServerRequest;
import io.github.easyagent.ui.jcef.dto.McpRequests.TestMcpConnectRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置管理、连接测试和工具调用的处理器。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
@Slf4j
public class McpHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_MCP_CONFIGS, McpConfigsRequest.class,
                request -> this.handleGetMcpConfigs(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_MCP_SERVER, SaveMcpServerRequest.class,
                request -> this.handleSaveMcpServer(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_MCP_SERVER, DeleteMcpServerRequest.class,
                request -> this.handleDeleteMcpServer(ctx, request));
        ctx.registerHandler(handlers, JsAction.TEST_MCP_CONNECT, TestMcpConnectRequest.class,
                request -> this.handleTestMcpConnect(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_MCP_TOOLS, ListMcpToolsRequest.class,
                request -> this.handleListMcpTools(ctx, request));
        ctx.registerHandler(handlers, JsAction.CALL_MCP_TOOL, CallMcpToolRequest.class,
                request -> this.handleCallMcpTool(ctx, request));
    }

    /**
     * 加载指定 CLI 类型的 MCP 配置列表。
     *
     * @param ctx     共享上下文
     * @param request MCP 配置请求
     */
    private void handleGetMcpConfigs(BridgeContext ctx, McpConfigsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                String projectPath = ctx.getProjectPath();
                List<McpServerEntry> entries = ctx.mcpConfigService().loadMcpConfigs(cliType, projectPath);
                ctx.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
            } catch (Exception e) {
                log.error("加载 MCP 配置失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_CONFIGS, List.of());
            }
        });
    }

    /**
     * 保存 MCP 服务器配置。
     *
     * @param ctx     共享上下文
     * @param request 保存请求
     */
    private void handleSaveMcpServer(BridgeContext ctx, SaveMcpServerRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String projectPath = "project".equals(scope) ? ctx.getProjectPath() : null;
                McpServerEntry entry = request.toEntry();
                boolean success = ctx.mcpConfigService().saveMcpServer(cliType, scope, projectPath, entry);
                ctx.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(success, cliType, success ? "" : "Save failed"));
                if (success) {
                    List<McpServerEntry> entries = ctx.mcpConfigService().loadMcpConfigs(cliType, ctx.getProjectPath());
                    ctx.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
                }
            } catch (Exception e) {
                log.error("保存 MCP 配置失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 删除 MCP 服务器配置。
     *
     * @param ctx     共享上下文
     * @param request 删除请求
     */
    private void handleDeleteMcpServer(BridgeContext ctx, DeleteMcpServerRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String projectPath = "project".equals(scope) ? ctx.getProjectPath() : null;
                boolean success = ctx.mcpConfigService().deleteMcpServer(cliType, scope, projectPath, request.serverName());
                ctx.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(success, cliType, success ? "" : "Delete failed"));
                if (success) {
                    List<McpServerEntry> entries = ctx.mcpConfigService().loadMcpConfigs(cliType, ctx.getProjectPath());
                    ctx.invokeJSCallback(JsCallback.MCP_CONFIGS, entries);
                }
            } catch (Exception e) {
                log.error("删除 MCP 配置失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_SAVED,
                        new McpSavedPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    /**
     * 测试连接 MCP 服务器，成功后返回连接 ID 和可用工具列表。
     *
     * @param ctx     共享上下文
     * @param request 测试连接请求
     */
    private void handleTestMcpConnect(BridgeContext ctx, TestMcpConnectRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                String scope = request.scope();
                String serverName = request.serverName();
                String projectPath = "project".equals(scope) ? ctx.getProjectPath() : null;
                List<McpServerEntry> entries = ctx.mcpConfigService().loadMcpConfigs(cliType, projectPath);
                McpServerEntry target = null;
                for (McpServerEntry e : entries) {
                    if (e.name().equals(serverName) && e.scope().equals(scope)) {
                        target = e;
                        break;
                    }
                }
                if (target == null) {
                    ctx.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                            new McpTestConnectedPayload(false, null, serverName, "Server config not found", List.of(), "", Map.of()));
                    return;
                }
                McpTestService.ConnectResult result = ctx.mcpTestService().connect(target);
                List<McpTestService.ToolInfo> tools = List.of();
                if (result.success()) {
                    tools = ctx.mcpTestService().listTools(result.connectionId());
                }
                ctx.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                        new McpTestConnectedPayload(result.success(), result.connectionId(),
                                serverName, result.serverNameOrError(), tools,
                                result.transportType(), result.env()));
            } catch (Exception e) {
                log.error("测试 MCP 连接失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_TEST_CONNECTED,
                        new McpTestConnectedPayload(false, null, "", e.getMessage(), List.of(), "", Map.of()));
            }
        });
    }

    /**
     * 列出已连接 MCP 服务器的可用工具。
     *
     * @param ctx     共享上下文
     * @param request 列出工具请求
     */
    private void handleListMcpTools(BridgeContext ctx, ListMcpToolsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                List<McpTestService.ToolInfo> tools = ctx.mcpTestService().listTools(request.connectionId());
                ctx.invokeJSCallback(JsCallback.MCP_TOOLS,
                        new McpToolsPayload(request.connectionId(), tools));
            } catch (Exception e) {
                log.error("列出 MCP 工具失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_TOOLS,
                        new McpToolsPayload(request.connectionId(), List.of()));
            }
        });
    }

    /**
     * 调用 MCP 工具并返回执行结果。
     *
     * @param ctx     共享上下文
     * @param request 工具调用请求
     */
    private void handleCallMcpTool(BridgeContext ctx, CallMcpToolRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
                McpTestService.ToolCallResult result = ctx.mcpTestService().callTool(
                        request.connectionId(), request.toolName(), args);
                ctx.invokeJSCallback(JsCallback.MCP_TOOL_RESULT,
                        new McpToolResultPayload(request.connectionId(), request.toolName(), result));
            } catch (Exception e) {
                log.error("调用 MCP 工具失败", e);
                ctx.invokeJSCallback(JsCallback.MCP_TOOL_RESULT,
                        new McpToolResultPayload(request.connectionId(), request.toolName(),
                                new McpTestService.ToolCallResult(false, e.getMessage(), false, List.of())));
            }
        });
    }
}
