package io.github.easyagent.settings.mcp;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 单个 MCP 服务器配置条目。
 * <p>
 * 统一表示 Claude / OpenCode / Codex 三个 CLI 的 MCP 服务器配置，
 * 屏蔽不同 CLI 配置文件格式的差异。
 * </p>
 *
 * @param name       MCP 服务器名称（作为配置中的 key）
 * @param type       传输类型：{@code stdio} 或 {@code sse} 或 {@code http}
 * @param command    stdio 模式下的启动命令
 * @param args       stdio 模式下的命令参数列表
 * @param env        环境变量映射
 * @param url        HTTP/SSE 模式下的服务器地址
 * @param enabled    是否启用
 * @param scope      配置作用域：{@code user} 或 {@code project}
 * @param configPath 配置文件路径（只读，用于前端展示来源）
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Builder
public record McpServerEntry(
        String name,
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        boolean enabled,
        String scope,
        String configPath
) {
}
