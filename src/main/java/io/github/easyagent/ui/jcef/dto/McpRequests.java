package io.github.easyagent.ui.jcef.dto;

import io.github.easyagent.settings.mcp.McpServerEntry;

import java.util.List;
import java.util.Map;

/**
 * MCP 相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class McpRequests {

    private McpRequests() {
    }

    /**
     * 获取 MCP 配置列表请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record McpConfigsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 保存 MCP 服务器配置请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     * @param scope   作用域
     * @param name    服务器名称
     * @param type    传输类型
     * @param command 启动命令
     * @param args    命令参数
     * @param env     环境变量
     * @param url     服务器地址
     * @param enabled 是否启用
     */
    public record SaveMcpServerRequest(String action, String cliType, String scope,
                                        String name, String type, String command,
                                        List<String> args, Map<String, String> env,
                                        String url, boolean enabled) implements JsRequest {
        /**
         * 转为 {@link McpServerEntry} 实体。
         *
         * @return MCP 服务器条目
         */
        public McpServerEntry toEntry() {
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
    public record DeleteMcpServerRequest(String action, String cliType,
                                           String scope, String serverName) implements JsRequest {
    }

    /**
     * 测试连接 MCP 服务器请求。
     *
     * @param action     动作名称
     * @param cliType    CLI 类型
     * @param scope      作用域
     * @param serverName 服务器名称
     */
    public record TestMcpConnectRequest(String action, String cliType,
                                         String scope, String serverName) implements JsRequest {
    }

    /**
     * 列出 MCP 工具请求。
     *
     * @param action       动作名称
     * @param connectionId 连接 ID
     */
    public record ListMcpToolsRequest(String action, String connectionId) implements JsRequest {
    }

    /**
     * 调用 MCP 工具请求。
     *
     * @param action       动作名称
     * @param connectionId 连接 ID
     * @param toolName     工具名称
     * @param arguments    工具参数
     */
    public record CallMcpToolRequest(String action, String connectionId,
                                      String toolName,
                                      Map<String, Object> arguments) implements JsRequest {
    }
}
