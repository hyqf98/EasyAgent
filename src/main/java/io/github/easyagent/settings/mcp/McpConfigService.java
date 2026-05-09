package io.github.easyagent.settings.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import io.github.easyagent.util.GsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置管理服务。
 * <p>
 * 负责读取和写入 Claude / OpenCode / Codex 三个 CLI 的 MCP 服务器配置，
 * 支持用户级别和项目级别两种作用域。
 * </p>
 *
 * <h3>配置文件路径与格式</h3>
 * <table>
 *   <tr><th>CLI</th><th>用户级路径</th><th>项目级路径</th><th>顶层 Key</th><th>command 格式</th><th>env Key</th></tr>
 *   <tr><td>Claude</td><td>{@code ~/.claude.json}</td><td>{@code <project>/.mcp.json}</td><td>{@code mcpServers}</td><td>字符串</td><td>{@code env}</td></tr>
 *   <tr><td>OpenCode</td><td>{@code ~/.config/opencode/opencode.json}</td><td>{@code <project>/opencode.json}</td><td>{@code mcp}</td><td>数组</td><td>{@code environment}</td></tr>
 *   <tr><td>Codex</td><td>{@code ~/.codex/config.toml}</td><td>{@code <project>/.codex/config.toml}</td><td>{@code mcp_servers}</td><td>字符串</td><td>{@code env}</td></tr>
 * </table>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
public class McpConfigService {

    private static final Logger log = LoggerFactory.getLogger(McpConfigService.class);

    private static final String USER_HOME = System.getProperty("user.home");
    private static final Gson GSON = GsonUtils.getGson();

    /**
     * 加载指定 CLI 类型在指定项目路径下的所有 MCP 配置。
     *
     * @param cliType     CLI 类型：CLAUDE / OPENCODE / CODEX
     * @param projectPath 当前项目根路径，可为 null（仅加载用户级）
     * @return MCP 服务器条目列表，合并用户级和项目级
     */
    public List<McpServerEntry> loadMcpConfigs(String cliType, String projectPath) {
        List<McpServerEntry> result = new ArrayList<>();
        result.addAll(this.loadScope(cliType, "user", null));
        if (projectPath != null && !projectPath.isBlank()) {
            result.addAll(this.loadScope(cliType, "project", projectPath));
        }
        return result;
    }

    /**
     * 保存（新增或更新）MCP 服务器配置。
     *
     * @param cliType     CLI 类型
     * @param scope       作用域：user 或 project
     * @param projectPath 项目路径（scope 为 project 时必填）
     * @param entry       MCP 服务器条目
     * @return 操作是否成功
     */
    public boolean saveMcpServer(String cliType, String scope, String projectPath, McpServerEntry entry) {
        try {
            return switch (cliType.toUpperCase()) {
                case "CLAUDE" -> this.saveToJsonFile(
                        this.resolveConfigPath("CLAUDE", scope, projectPath),
                        "mcpServers", this.entryToClaudeJson(entry));
                case "OPENCODE" -> this.saveToJsonFile(
                        this.resolveConfigPath("OPENCODE", scope, projectPath),
                        "mcp", this.entryToOpenCodeJson(entry));
                case "CODEX" -> this.saveToTomlFile(
                        this.resolveConfigPath("CODEX", scope, projectPath),
                        "mcp_servers", entry);
                default -> false;
            };
        } catch (Exception e) {
            log.error("保存 MCP 配置失败: cliType={}, scope={}, name={}", cliType, scope, entry.name(), e);
            return false;
        }
    }

    /**
     * 删除指定 MCP 服务器配置。
     *
     * @param cliType     CLI 类型
     * @param scope       作用域：user 或 project
     * @param projectPath 项目路径
     * @param serverName  MCP 服务器名称
     * @return 操作是否成功
     */
    public boolean deleteMcpServer(String cliType, String scope, String projectPath, String serverName) {
        try {
            return switch (cliType.toUpperCase()) {
                case "CLAUDE" -> this.deleteFromJsonFile(
                        this.resolveConfigPath("CLAUDE", scope, projectPath),
                        "mcpServers", serverName);
                case "OPENCODE" -> this.deleteFromJsonFile(
                        this.resolveConfigPath("OPENCODE", scope, projectPath),
                        "mcp", serverName);
                case "CODEX" -> this.deleteFromTomlFile(
                        this.resolveConfigPath("CODEX", scope, projectPath),
                        "mcp_servers", serverName);
                default -> false;
            };
        } catch (Exception e) {
            log.error("删除 MCP 配置失败: cliType={}, scope={}, name={}", cliType, scope, serverName, e);
            return false;
        }
    }

    /**
     * 加载指定作用域的 MCP 配置。
     */
    private List<McpServerEntry> loadScope(String cliType, String scope, String projectPath) {
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> this.loadFromJsonFile(
                    this.resolveConfigPath("CLAUDE", scope, projectPath),
                    "mcpServers", scope, false);
            case "OPENCODE" -> this.loadFromJsonFile(
                    this.resolveConfigPath("OPENCODE", scope, projectPath),
                    "mcp", scope, true);
            case "CODEX" -> this.loadFromTomlFile(
                    this.resolveConfigPath("CODEX", scope, projectPath),
                    "mcp_servers", scope);
            default -> Collections.emptyList();
        };
    }

    /**
     * 根据 CLI 类型、作用域和项目路径解析配置文件路径。
     */
    private Path resolveConfigPath(String cliType, String scope, String projectPath) {
        boolean isUser = "user".equals(scope);
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> isUser
                    ? Path.of(USER_HOME, ".claude.json")
                    : Path.of(projectPath, ".mcp.json");
            case "OPENCODE" -> isUser
                    ? Path.of(USER_HOME, ".config", "opencode", "opencode.json")
                    : Path.of(projectPath, "opencode.json");
            case "CODEX" -> isUser
                    ? Path.of(USER_HOME, ".codex", "config.toml")
                    : Path.of(projectPath, ".codex", "config.toml");
            default -> Path.of(".");
        };
    }

    /**
     * 从 JSON 配置文件中读取 MCP 服务器列表。
     */
    private List<McpServerEntry> loadFromJsonFile(Path configPath, String topKey, String scope, boolean openCodeFormat) {
        List<McpServerEntry> entries = new ArrayList<>();
        if (!Files.exists(configPath)) {
            return entries;
        }
        try {
            String content = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject servers = root.getAsJsonObject(topKey);
            if (servers == null) {
                return entries;
            }
            for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
                if (openCodeFormat) {
                    entries.add(this.parseOpenCodeServer(e.getKey(), e.getValue(), scope, configPath.toString()));
                } else {
                    entries.add(this.parseClaudeServer(e.getKey(), e.getValue(), scope, configPath.toString()));
                }
            }
        } catch (Exception e) {
            log.warn("读取 MCP 配置文件失败: {}", configPath, e);
        }
        return entries;
    }

    /**
     * 从 TOML 配置文件中读取 MCP 服务器列表（Codex 专用）。
     */
    @SuppressWarnings("unchecked")
    private List<McpServerEntry> loadFromTomlFile(Path configPath, String topKey, String scope) {
        List<McpServerEntry> entries = new ArrayList<>();
        if (!Files.exists(configPath)) {
            return entries;
        }
        try {
            Toml toml = new Toml().read(configPath.toFile());
            Toml serversTable = toml.getTable(topKey);
            if (serversTable == null) {
                return entries;
            }
            Map<String, Object> servers = serversTable.toMap();
            for (Map.Entry<String, Object> e : servers.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> map) {
                    entries.add(this.parseTomlServer(e.getKey(), (Map<String, Object>) map, scope, configPath.toString()));
                }
            }
        } catch (Exception e) {
            log.warn("读取 TOML MCP 配置文件失败: {}", configPath, e);
        }
        return entries;
    }

    /**
     * 解析 Claude JSON 格式的单个 MCP 服务器配置。
     * <p>
     * Claude 格式：command 为字符串，env key 为 {@code env}，禁用为 {@code disabled: true}。
     * </p>
     */
    private McpServerEntry parseClaudeServer(String name, JsonElement element, String scope, String configPath) {
        JsonObject obj = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        String command = obj.has("command") ? obj.get("command").getAsString() : null;
        String url = obj.has("url") ? obj.get("url").getAsString() : null;
        List<String> args = obj.has("args") ? this.jsonArrayToStringList(obj.getAsJsonArray("args")) : Collections.emptyList();
        Map<String, String> env = obj.has("env") ? this.jsonObjectToStringMap(obj.getAsJsonObject("env")) : Collections.emptyMap();
        boolean enabled = !obj.has("disabled") || !obj.get("disabled").getAsBoolean();
        String type = this.normalizeType(null, command, url);
        return McpServerEntry.builder()
                .name(name).type(type).command(command).args(args).env(env)
                .url(url).enabled(enabled).scope(scope).configPath(configPath)
                .build();
    }

    /**
     * 解析 OpenCode JSON 格式的单个 MCP 服务器配置。
     * <p>
     * OpenCode 格式差异：command 为数组 {@code ["npx", "-y", "..."]}，
     * env key 为 {@code environment}，禁用为 {@code enabled: false}。
     * </p>
     */
    private McpServerEntry parseOpenCodeServer(String name, JsonElement element, String scope, String configPath) {
        JsonObject obj = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        String command = null;
        List<String> args = Collections.emptyList();
        if (obj.has("command") && obj.get("command").isJsonArray()) {
            List<String> parts = this.jsonArrayToStringList(obj.getAsJsonArray("command"));
            if (!parts.isEmpty()) {
                command = parts.get(0);
                args = parts.size() > 1 ? parts.subList(1, parts.size()) : Collections.emptyList();
            }
        } else if (obj.has("command") && obj.get("command").isJsonPrimitive()) {
            command = obj.get("command").getAsString();
        }
        String url = obj.has("url") ? obj.get("url").getAsString() : null;
        Map<String, String> env = obj.has("environment")
                ? this.jsonObjectToStringMap(obj.getAsJsonObject("environment"))
                : Collections.emptyMap();
        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        String type = this.normalizeType(obj.has("type") ? obj.get("type").getAsString() : null, command, url);
        return McpServerEntry.builder()
                .name(name).type(type).command(command).args(args).env(env)
                .url(url).enabled(enabled).scope(scope).configPath(configPath)
                .build();
    }

    /**
     * 解析 TOML 格式的单个 MCP 服务器配置。
     */
    private McpServerEntry parseTomlServer(String name, Map<String, Object> map, String scope, String configPath) {
        String command = map.get("command") instanceof String s ? s : null;
        String url = map.get("url") instanceof String s ? s : null;
        List<String> args = map.get("args") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : Collections.emptyList();
        Map<String, String> env = map.get("env") instanceof Map<?, ?> m
                ? this.toStringMap(m)
                : Collections.emptyMap();
        boolean enabled = !(map.get("enabled") instanceof Boolean b) || b;
        String rawType = map.get("type") instanceof String s ? s : null;
        String type = this.normalizeType(rawType, command, url);
        return McpServerEntry.builder()
                .name(name).type(type).command(command).args(args).env(env)
                .url(url).enabled(enabled).scope(scope).configPath(configPath)
                .build();
    }

    /**
     * 将原始传输类型标准化为统一类型。
     * <p>
     * OpenCode 使用 {@code "local"} 表示 stdio 类型，Claude/Codex 可能使用 {@code "stdio"}。
     * 统一映射为 {@code stdio}、{@code http}、{@code sse}、{@code unknown}。
     * </p>
     *
     * @param rawType 原始类型字符串，可为 {@code null}
     * @param command 命令字段，非空则推断为 stdio
     * @param url     URL 字段，非空则推断为 http
     * @return 标准化后的类型
     */
    private String normalizeType(String rawType, String command, String url) {
        if (rawType != null) {
            String lower = rawType.toLowerCase();
            return switch (lower) {
                case "local", "stdio" -> "stdio";
                case "remote", "http", "streamable-http" -> "http";
                case "sse" -> "sse";
                default -> lower;
            };
        }
        if (command != null) {
            return "stdio";
        }
        if (url != null) {
            return "http";
        }
        return "unknown";
    }

    /**
     * 保存 MCP 服务器到 JSON 配置文件。
     */
    private boolean saveToJsonFile(Path configPath, String topKey, JsonObject serverObj) throws IOException {
        this.ensureParentDir(configPath);
        JsonObject root = this.readJsonRoot(configPath);
        JsonObject servers = root.getAsJsonObject(topKey);
        if (servers == null) {
            servers = new JsonObject();
            root.add(topKey, servers);
        }
        String serverName = serverObj.has("__name__") ? serverObj.remove("__name__").getAsString() : "unknown";
        servers.add(serverName, serverObj);
        this.writeJsonFile(configPath, root);
        return true;
    }

    /**
     * 保存 MCP 服务器到 TOML 配置文件。
     */
    private boolean saveToTomlFile(Path configPath, String topKey, McpServerEntry entry) throws IOException {
        this.ensureParentDir(configPath);
        Map<String, Object> root = this.readTomlRoot(configPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) root.computeIfAbsent(topKey, k -> new LinkedHashMap<>());
        servers.put(entry.name(), this.entryToTomlMap(entry));
        TomlWriter writer = new TomlWriter();
        writer.write(root, configPath.toFile());
        return true;
    }

    /**
     * 从 JSON 配置文件中删除指定 MCP 服务器。
     */
    private boolean deleteFromJsonFile(Path configPath, String topKey, String serverName) throws IOException {
        if (!Files.exists(configPath)) {
            return false;
        }
        JsonObject root = this.readJsonRoot(configPath);
        JsonObject servers = root.getAsJsonObject(topKey);
        if (servers == null || !servers.has(serverName)) {
            return false;
        }
        servers.remove(serverName);
        this.writeJsonFile(configPath, root);
        return true;
    }

    /**
     * 从 TOML 配置文件中删除指定 MCP 服务器。
     */
    private boolean deleteFromTomlFile(Path configPath, String topKey, String serverName) throws IOException {
        if (!Files.exists(configPath)) {
            return false;
        }
        Map<String, Object> root = this.readTomlRoot(configPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> servers = (Map<String, Object>) root.get(topKey);
        if (servers == null || !servers.containsKey(serverName)) {
            return false;
        }
        servers.remove(serverName);
        TomlWriter writer = new TomlWriter();
        writer.write(root, configPath.toFile());
        return true;
    }

    /**
     * 将 {@link McpServerEntry} 转为 Claude JSON 对象。
     * <p>
     * Claude 格式：command 为字符串，args 为独立数组，env key 为 {@code env}，禁用为 {@code disabled: true}。
     * </p>
     */
    private JsonObject entryToClaudeJson(McpServerEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("__name__", entry.name());
        if (entry.command() != null) {
            obj.addProperty("command", entry.command());
        }
        if (entry.url() != null) {
            obj.addProperty("url", entry.url());
        }
        if (entry.args() != null && !entry.args().isEmpty()) {
            JsonArray arr = new JsonArray();
            entry.args().forEach(arr::add);
            obj.add("args", arr);
        }
        if (entry.env() != null && !entry.env().isEmpty()) {
            JsonObject envObj = new JsonObject();
            entry.env().forEach(envObj::addProperty);
            obj.add("env", envObj);
        }
        if (!entry.enabled()) {
            obj.addProperty("disabled", true);
        }
        return obj;
    }

    /**
     * 将 {@link McpServerEntry} 转为 OpenCode JSON 对象。
     * <p>
     * OpenCode 格式：command 为合并数组 {@code ["cmd", "arg1", "arg2"]}，
     * env key 为 {@code environment}，禁用为 {@code enabled: false}。
     * </p>
     */
    private JsonObject entryToOpenCodeJson(McpServerEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("__name__", entry.name());
        if (entry.command() != null) {
            JsonArray cmdArr = new JsonArray();
            cmdArr.add(entry.command());
            if (entry.args() != null) {
                entry.args().forEach(cmdArr::add);
            }
            obj.add("command", cmdArr);
            obj.addProperty("type", "local");
        }
        if (entry.url() != null) {
            obj.addProperty("url", entry.url());
            obj.addProperty("type", "remote");
        }
        if (entry.env() != null && !entry.env().isEmpty()) {
            JsonObject envObj = new JsonObject();
            entry.env().forEach(envObj::addProperty);
            obj.add("environment", envObj);
        }
        if (!entry.enabled()) {
            obj.addProperty("enabled", false);
        } else {
            obj.addProperty("enabled", true);
        }
        return obj;
    }

    /**
     * 将 {@link McpServerEntry} 转为 TOML Map。
     */
    private Map<String, Object> entryToTomlMap(McpServerEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (entry.command() != null) {
            map.put("command", entry.command());
        }
        if (entry.url() != null) {
            map.put("url", entry.url());
        }
        if (entry.args() != null && !entry.args().isEmpty()) {
            map.put("args", entry.args());
        }
        if (entry.env() != null && !entry.env().isEmpty()) {
            map.put("env", entry.env());
        }
        if (!entry.enabled()) {
            map.put("enabled", false);
        }
        return map;
    }

    /**
     * 读取 JSON 配置文件的根对象。
     */
    private JsonObject readJsonRoot(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            return new JsonObject();
        }
        String content = Files.readString(configPath);
        if (content.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(content).getAsJsonObject();
    }

    /**
     * 写入 JSON 配置文件（格式化输出）。
     */
    private void writeJsonFile(Path configPath, JsonObject root) throws IOException {
        String json = GSON.toJson(root);
        Files.writeString(configPath, json);
    }

    /**
     * 读取 TOML 配置文件的根 Map。
     */
    private Map<String, Object> readTomlRoot(Path configPath) {
        if (!Files.exists(configPath)) {
            return new LinkedHashMap<>();
        }
        try {
            Toml toml = new Toml().read(configPath.toFile());
            return new LinkedHashMap<>(toml.toMap());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 确保父目录存在。
     */
    private void ensureParentDir(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * JSON 数组转字符串列表。
     */
    private List<String> jsonArrayToStringList(JsonArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (JsonElement e : arr) {
            list.add(e.getAsString());
        }
        return list;
    }

    /**
     * JSON 对象转字符串映射。
     */
    private Map<String, String> jsonObjectToStringMap(JsonObject obj) {
        Map<String, String> map = new LinkedHashMap<>();
        if (obj == null) {
            return map;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            map.put(e.getKey(), e.getValue().getAsString());
        }
        return map;
    }

    /**
     * 通配 Map 转字符串映射。
     */
    private Map<String, String> toStringMap(Map<?, ?> map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return result;
    }
}
