package io.github.easyagent.settings.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 服务器测试服务。
 * <p>
 * 通过 MCP Java SDK ({@code io.modelcontextprotocol.sdk}) 连接 MCP 服务器，支持：
 * <ul>
 *   <li>stdio transport — 通过子进程 stdin/stdout 通信</li>
 *   <li>HTTP (Streamable HTTP) transport — 通过 HTTP POST 通信（优先尝试）</li>
 *   <li>SSE transport — 旧版 SSE 协议（Streamable HTTP 失败时自动回退）</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
public class McpTestService {

    private static final Logger log = LoggerFactory.getLogger(McpTestService.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(
            tools.jackson.databind.json.JsonMapper.builder().build());

    private static final JsonSchemaValidator NOOP_VALIDATOR = (schema, content) ->
            new JsonSchemaValidator.ValidationResponse(true, null, null);

    /**
     * 活跃的 MCP 客户端连接，key 为连接 ID。
     */
    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /**
     * 连接到 MCP 服务器并完成 initialize 握手。
     *
     * @param entry MCP 服务器配置条目
     * @return 连接结果，包含连接 ID 和服务器信息
     */
    public ConnectResult connect(McpServerEntry entry) {
        String connectionId = "mcp-" + System.currentTimeMillis();
        try {
            log.info("MCP 连接开始: name={}, type={}, command={}", entry.name(), entry.type(), entry.command());

            CompletableFuture<ConnectResult> future = CompletableFuture.supplyAsync(() ->
                    this.tryConnect(entry, connectionId));

            return future.get(90, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("MCP 连接超时: {}", entry.name());
            this.closeConnection(connectionId);
            return new ConnectResult(false, null, "Connection timeout (90s)", Collections.emptyMap(), "", Collections.emptyMap());
        } catch (Exception e) {
            log.warn("MCP 连接失败: {}", entry.name(), e);
            return new ConnectResult(false, null, e.getMessage(), Collections.emptyMap(), "", Collections.emptyMap());
        }
    }

    /**
     * 根据 MCP 类型创建对应的 Transport。
     *
     * @param type  传输类型（stdio / http / sse）
     * @param entry MCP 服务器配置条目
     * @return Transport 实例
     */
    private McpClientTransport createTransport(String type, McpServerEntry entry) {
        return switch (type) {
            case "http" -> this.createHttpTransport(entry, false);
            case "sse" -> this.createHttpTransport(entry, true);
            default -> {
                if (entry.command() == null || entry.command().isBlank()) {
                    throw new IllegalArgumentException("Command is required for stdio MCP server");
                }
                String command = resolveCommand(entry.command());
                ServerParameters.Builder builder = ServerParameters.builder(command);
                if (entry.args() != null && !entry.args().isEmpty()) {
                    builder.args(entry.args());
                }
                if (entry.env() != null && !entry.env().isEmpty()) {
                    builder.env(entry.env());
                }
                yield new StdioClientTransport(builder.build(), JSON_MAPPER);
            }
        };
    }

    /**
     * 创建 HTTP 类型的 Transport，优先使用 Streamable HTTP，回退到 SSE。
     * <p>
     * 对于 HTTP/SSE 类型的 MCP 服务器，{@code entry.env()} 中的键值对将作为
     * HTTP 请求头添加到每个请求中（例如 Authorization、X-Api-Key 等）。
     * </p>
     *
     * @param entry   MCP 服务器配置条目
     * @param forceSse 是否强制使用 SSE transport
     * @return Transport 实例
     */
    private McpClientTransport createHttpTransport(McpServerEntry entry, boolean forceSse) {
        if (entry.url() == null || entry.url().isBlank()) {
            throw new IllegalArgumentException("URL is required for HTTP MCP server");
        }
        McpSyncHttpClientRequestCustomizer headerCustomizer = this.createHeaderCustomizer(entry.env());
        if (forceSse) {
            return HttpClientSseClientTransport.builder(entry.url())
                    .jsonMapper(JSON_MAPPER)
                    .httpRequestCustomizer(headerCustomizer)
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(entry.url())
                .jsonMapper(JSON_MAPPER)
                .httpRequestCustomizer(headerCustomizer)
                .build();
    }

    /**
     * 根据环境变量映射创建 HTTP 请求头定制器。
     * <p>
     * 将 {@code env} 中的所有键值对作为 HTTP 请求头添加。
     * 常见用法包括：{@code Authorization}、{@code X-Api-Key} 等。
     * </p>
     *
     * @param env 环境变量映射，可为 null 或空
     * @return HTTP 请求头定制器
     */
    private McpSyncHttpClientRequestCustomizer createHeaderCustomizer(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return (builder, method, endpoint, body, context) -> {};
        }
        Map<String, String> headers = new LinkedHashMap<>(env);
        return (builder, method, endpoint, body, context) -> {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        };
    }

    /**
     * 使用指定的 transport 类型尝试连接（HTTP 类型会在 Streamable HTTP 失败时自动回退到 SSE）。
     *
     * @param entry      MCP 服务器配置条目
     * @param connectionId 连接 ID
     * @return 连接结果
     */
    private ConnectResult tryConnect(McpServerEntry entry, String connectionId) {
        String type = entry.type() != null ? entry.type().toLowerCase() : "stdio";
        boolean isHttp = "http".equals(type) || "sse".equals(type);

        if (!isHttp) {
            return this.doConnect(entry, connectionId, type);
        }

        if ("sse".equals(type)) {
            return this.doConnect(entry, connectionId, "sse");
        }

        ConnectResult streamableResult = this.doConnect(entry, connectionId, "http");
        if (streamableResult.success()) {
            return streamableResult;
        }

        log.info("Streamable HTTP 连接失败，尝试 SSE 回退: {}", entry.name());
        return this.doConnect(entry, connectionId, "sse");
    }

    /**
     * 执行实际的 MCP 连接和 initialize 握手。
     *
     * @param entry        MCP 服务器配置条目
     * @param connectionId 连接 ID
     * @param transportType transport 类型
     * @return 连接结果
     */
    private ConnectResult doConnect(McpServerEntry entry, String connectionId, String transportType) {
        try {
            McpClientTransport transport = this.createTransport(transportType, entry);
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .initializationTimeout(INIT_TIMEOUT)
                    .jsonSchemaValidator(NOOP_VALIDATOR)
                    .build();

            log.info("MCP 客户端已创建 ({}), 开始 initialize: {}", transportType, entry.name());
            McpSchema.InitializeResult initResult = client.initialize();
            log.info("MCP initialize 完成: {}", entry.name());

            this.clients.put(connectionId, client);

            String serverName = "";
            if (initResult.serverInfo() != null) {
                serverName = initResult.serverInfo().name();
            }

            Map<String, Object> serverCapabilities = new LinkedHashMap<>();
            if (initResult.capabilities() != null) {
                McpSchema.ServerCapabilities caps = initResult.capabilities();
                if (caps.tools() != null) {
                    serverCapabilities.put("tools", Map.of("listChanged", true));
                }
                if (caps.resources() != null) {
                    serverCapabilities.put("resources", Map.of("subscribe", true, "listChanged", true));
                }
                if (caps.prompts() != null) {
                    serverCapabilities.put("prompts", Map.of("listChanged", true));
                }
                if (caps.logging() != null) {
                    serverCapabilities.put("logging", Map.of());
                }
            }

            return new ConnectResult(true, connectionId, serverName, serverCapabilities,
                    transportType, entry.env() != null ? entry.env() : Collections.emptyMap());
        } catch (Exception e) {
            log.warn("MCP 连接失败 ({}): {}", transportType, entry.name(), e);
            return new ConnectResult(false, null, e.getMessage(), Collections.emptyMap(),
                    transportType, Collections.emptyMap());
        }
    }

    /**
     * 列出 MCP 服务器提供的所有工具。
     *
     * @param connectionId 连接 ID
     * @return 工具列表
     */
    public List<ToolInfo> listTools(String connectionId) {
        McpSyncClient client = this.clients.get(connectionId);
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            McpSchema.ListToolsResult result = client.listTools();
            if (result.tools() == null) {
                return Collections.emptyList();
            }
            List<ToolInfo> toolList = new ArrayList<>();
            for (McpSchema.Tool tool : result.tools()) {
                toolList.add(this.convertTool(tool));
            }
            return toolList;
        } catch (Exception e) {
            log.warn("列出 MCP 工具失败: {}", connectionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 调用 MCP 服务器上的指定工具。
     *
     * @param connectionId 连接 ID
     * @param toolName     工具名称
     * @param arguments    工具参数
     * @return 调用结果
     */
    public ToolCallResult callTool(String connectionId,
                                   String toolName,
                                   Map<String, Object> arguments) {
        McpSyncClient client = this.clients.get(connectionId);
        if (client == null) {
            return new ToolCallResult(false, "Connection not found: " + connectionId, false, Collections.emptyList());
        }
        try {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
            McpSchema.CallToolResult result = client.callTool(request);

            boolean isError = result.isError() != null && result.isError();
            List<ContentItem> contents = new ArrayList<>();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    contents.add(this.convertContent(content));
                }
            }
            return new ToolCallResult(true, "", isError, contents);
        } catch (Exception e) {
            log.warn("调用 MCP 工具失败: {} / {}", connectionId, toolName, e);
            return new ToolCallResult(false, e.getMessage(), false, Collections.emptyList());
        }
    }

    /**
     * 关闭指定连接。
     *
     * @param connectionId 连接 ID
     */
    public void closeConnection(String connectionId) {
        McpSyncClient client = this.clients.remove(connectionId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("关闭 MCP 连接异常: {}", connectionId, e);
            }
        }
    }

    /**
     * 关闭所有连接。
     */
    public void closeAll() {
        this.clients.forEach((id, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("关闭 MCP 连接异常: {}", id, e);
            }
        });
        this.clients.clear();
    }

    private static final java.util.Set<String> WIN_BUILTINS = java.util.Set.of(
            "cmd", "powershell", "pwsh", "bash", "sh", "python", "python3", "node", "java", "dotnet"
    );

    /**
     * 解析 MCP 服务器启动命令，在 Windows 上自动补充 {@code .cmd} 后缀。
     * <p>
     * Node.js 的 {@code npx}、{@code npm} 等命令在 Windows 上实际是
     * {@code npx.cmd}、{@code npm.cmd}，直接传给 {@link ProcessBuilder}
     * 会因 {@code CreateProcess error=2} 失败。
     * 但 {@code cmd}、{@code powershell} 等 Windows 内建命令不需要加后缀。
     * </p>
     *
     * @param command 原始命令
     * @return 解析后的命令
     */
    private static String resolveCommand(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return command;
        }
        if (command.contains("/") || command.contains("\\") || command.contains(".")) {
            return command;
        }
        if (WIN_BUILTINS.contains(command.toLowerCase())) {
            return command;
        }
        return command + ".cmd";
    }

    /**
     * 将 SDK {@link McpSchema.Tool} 转换为 {@link ToolInfo}。
     */
    private ToolInfo convertTool(McpSchema.Tool tool) {
        String name = tool.name() != null ? tool.name() : "";
        String description = tool.description() != null ? tool.description() : "";
        Map<String, PropertyInfo> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (tool.inputSchema() != null) {
            McpSchema.JsonSchema schema = tool.inputSchema();
            if (schema.properties() != null) {
                for (Map.Entry<String, Object> e : schema.properties().entrySet()) {
                    String propName = e.getKey();
                    String propType = "string";
                    String propDesc = "";
                    if (e.getValue() instanceof Map<?, ?> propMap) {
                        if (propMap.get("type") instanceof String t) {
                            propType = t;
                        }
                        if (propMap.get("description") instanceof String d) {
                            propDesc = d;
                        }
                    }
                    properties.put(propName, new PropertyInfo(propName, propType, propDesc, false));
                }
            }
            if (schema.required() != null) {
                required.addAll(schema.required());
            }
        }

        return new ToolInfo(name, description, properties, required);
    }

    /**
     * 将 SDK {@link McpSchema.Content} 转换为 {@link ContentItem}。
     */
    private ContentItem convertContent(McpSchema.Content content) {
        String type = content.type() != null ? content.type() : "text";
        String text = "";
        if (content instanceof McpSchema.TextContent tc) {
            text = tc.text() != null ? tc.text() : "";
        }
        return new ContentItem(type, text);
    }

    /**
     * 连接结果。
     *
     * @param success            是否成功
     * @param connectionId       连接 ID（成功时非 null）
     * @param serverNameOrError  服务器名称或错误信息
     * @param serverCapabilities 服务器能力
     * @param transportType      实际使用的传输类型（stdio / http / sse）
     * @param env                回显的环境变量/请求头数据
     */
    public record ConnectResult(boolean success, String connectionId, String serverNameOrError,
                                Map<String, Object> serverCapabilities,
                                String transportType,
                                Map<String, String> env) {
    }

    /**
     * 工具信息。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param properties  输入参数属性
     * @param required    必填参数列表
     */
    public record ToolInfo(String name, String description,
                           Map<String, PropertyInfo> properties,
                           List<String> required) {
    }

    /**
     * 工具参数属性信息。
     *
     * @param name        参数名
     * @param type        参数类型
     * @param description 参数描述
     * @param required    是否必填
     */
    public record PropertyInfo(String name, String type, String description, boolean required) {
    }

    /**
     * 工具调用结果。
     *
     * @param success  是否成功
     * @param error    错误信息
     * @param isError  服务端是否返回错误
     * @param contents 内容列表
     */
    public record ToolCallResult(boolean success, String error, boolean isError,
                                 List<ContentItem> contents) {
    }

    /**
     * 内容项。
     *
     * @param type 内容类型（text / image / resource）
     * @param text 内容文本
     */
    public record ContentItem(String type, String text) {
    }
}
