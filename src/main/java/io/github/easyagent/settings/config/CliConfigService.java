package io.github.easyagent.settings.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI 配置管理服务。
 * <p>
 * 负责读取和写入 Claude Code、OpenCode、Codex 三个 CLI 工具的配置信息。
 * <ul>
 *   <li>Claude Code：通过环境变量配置，写入 {@code ~/.zshrc} 或 {@code ~/.bashrc}</li>
 *   <li>OpenCode：通过 JSON 配置文件 {@code ~/.config/opencode/opencode.json}</li>
 *   <li>Codex：通过 TOML 配置文件 {@code ~/.codex/config.toml} + 环境变量</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Slf4j
public class CliConfigService {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("windows");
    private static final String HOME = System.getProperty("user.home");

    private static final Path ZSHRC_PATH = Path.of(HOME, ".zshrc");
    private static final Path BASHRC_PATH = Path.of(HOME, ".bashrc");
    private static final Path CLAUDE_SETTINGS_PATH = Path.of(HOME, ".claude", "settings.json");

    private static final Path OPENCODE_CONFIG_PATH = Path.of(HOME, ".config", "opencode", "opencode.json");
    private static final Path CODEX_CONFIG_PATH = Path.of(HOME, ".codex", "config.toml");
    private static final Path CODEX_AUTH_PATH = Path.of(HOME, ".codex", "auth.json");

    private static final Pattern EXPORT_PATTERN = Pattern.compile(
            "^\\s*export\\s+([A-Z_]+)\\s*=\\s*\"?(.*?)\"?\\s*$");

    private static final Pattern TOML_MODEL_PATTERN = Pattern.compile(
            "^\\s*model\\s*=\\s*\"([^\"]+)\"");

    private static final Pattern TOML_PROVIDER_BASE_URL_PATTERN = Pattern.compile(
            "^\\s*base_url\\s*=\\s*\"([^\"]+)\"");

    /**
     * 读取所有 CLI 配置（包含用户覆盖的命令路径）。
     *
     * @return CLI 配置集合
     */
    public CliConfigs readConfigs() {
        java.util.Map<String, String> commandPaths = this.readCommandPaths();
        return new CliConfigs(
                this.readClaudeConfig(commandPaths.get("CLAUDE")),
                this.readOpenCodeConfig(commandPaths.get("OPENCODE")),
                this.readCodexConfig(commandPaths.get("CODEX"))
        );
    }

    /**
     * 从持久化状态读取用户覆盖的 CLI 命令路径。
     *
     * @return cliType -> commandPath 映射
     */
    public java.util.Map<String, String> readCommandPaths() {
        return io.github.easyagent.settings.EasyAgentAppState.getInstance().getCliCommandPaths();
    }

    /**
     * 保存用户覆盖的 CLI 命令路径到持久化状态。
     *
     * @param cliType     CLI 类型名称
     * @param commandPath 用户设置的命令路径，为空则清除覆盖
     */
    public void saveCommandPath(String cliType, String commandPath) {
        java.util.Map<String, String> paths = io.github.easyagent.settings.EasyAgentAppState.getInstance().getCliCommandPaths();
        if (commandPath != null && !commandPath.isBlank()) {
            paths.put(cliType, commandPath.trim());
        } else {
            paths.remove(cliType);
        }
    }

    /**
     * 获取 OpenCode Provider 列表。
     * <p>
     * 优先使用从 models.dev API 缓存的动态 provider 列表，
     * 若尚未同步过则回退到内置枚举（排除 CUSTOM）。
     * </p>
     *
     * @param dynamicProviders 从 models.dev API 获取的动态 provider 列表，可为空
     * @return Provider 信息列表
     */
    public List<ProviderInfo> getOpenCodeProviders(List<ProviderInfo> dynamicProviders) {
        if (dynamicProviders != null && !dynamicProviders.isEmpty()) {
            return dynamicProviders;
        }
        List<ProviderInfo> providers = new ArrayList<>();
        for (OpenCodeProvider p : OpenCodeProvider.values()) {
            if (p == OpenCodeProvider.CUSTOM) {
                continue;
            }
            providers.add(new ProviderInfo(p.getId(), p.getDisplayName()));
        }
        return providers;
    }

    // ==================== Claude Code ====================

    /**
     * 读取 Claude Code 配置（从 shell profile 环境变量）。
     *
     * @param commandPath 用户覆盖的命令路径，可为 null
     * @return Claude 配置
     */
    public ClaudeConfig readClaudeConfig(String commandPath) {
        java.util.Map<String, String> envVars = this.readShellExports();
        if (IS_WINDOWS) {
            java.util.Map<String, String> settingsEnv = this.readClaudeSettingsJson();
            for (Map.Entry<String, String> e : settingsEnv.entrySet()) {
                envVars.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return ClaudeConfig.builder()
                .baseUrl(envVars.getOrDefault("ANTHROPIC_BASE_URL", ""))
                .apiKey(envVars.getOrDefault("ANTHROPIC_API_KEY", ""))
                .authToken(envVars.getOrDefault("ANTHROPIC_AUTH_TOKEN", ""))
                .model(envVars.getOrDefault("ANTHROPIC_MODEL", ""))
                .commandPath(commandPath != null ? commandPath : "")
                .build();
    }

    /**
     * 保存 Claude Code 配置（写入 shell profile 环境变量）。
     *
     * @param config Claude 配置
     */
    public void saveClaudeConfig(ClaudeConfig config) {
        if (IS_WINDOWS) {
            this.writeClaudeSettingsJson(config);
        } else {
            Path shellFile = this.detectShellProfile();
            this.writeShellExport(shellFile, "ANTHROPIC_BASE_URL", config.baseUrl());
            this.writeShellExport(shellFile, "ANTHROPIC_API_KEY", config.apiKey());
            this.writeShellExport(shellFile, "ANTHROPIC_AUTH_TOKEN",
                    config.authToken() != null ? config.authToken() : "");
            this.writeShellExport(shellFile, "ANTHROPIC_MODEL", config.model());
            log.info("Claude Code config saved to {}", shellFile);
        }
    }

    // ==================== OpenCode ====================

    /**
     * 读取 OpenCode 配置（从 opencode.json）。
     *
     * @param commandPath 用户覆盖的命令路径，可为 null
     * @return OpenCode 配置
     */
    public OpenCodeConfig readOpenCodeConfig(String commandPath) {
        Path configPath = OPENCODE_CONFIG_PATH;
        if (!Files.exists(configPath)) {
            return OpenCodeConfig.empty();
        }
        try {
            String json = Files.readString(configPath);
            JsonObject root = GsonUtils.parseObject(json);

            String model = GsonUtils.getString(root, "model");
            String providerId = "";
            String apiKey = "";
            String baseUrl = "";

            JsonObject provider = GsonUtils.getJsonObject(root, "provider");
            if (provider != null && !provider.keySet().isEmpty()) {
                providerId = provider.keySet().iterator().next();
                JsonObject providerObj = GsonUtils.getJsonObject(provider, providerId);
                if (providerObj != null) {
                    JsonObject options = GsonUtils.getJsonObject(providerObj, "options");
                    if (options != null) {
                        apiKey = GsonUtils.getString(options, "apiKey");
                        if (apiKey == null) {
                            apiKey = "";
                        }
                        baseUrl = GsonUtils.getString(options, "baseURL");
                        if (baseUrl == null) {
                            baseUrl = "";
                        }
                    }
                }
            }

            if (model == null) {
                model = "";
            }

            return OpenCodeConfig.builder()
                    .providerId(providerId)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .model(model)
                    .commandPath(commandPath != null ? commandPath : "")
                    .build();
        } catch (Exception e) {
            log.warn("Failed to read OpenCode config", e);
            return OpenCodeConfig.empty();
        }
    }

    /**
     * 保存 OpenCode 配置（写入 opencode.json）。
     *
     * @param config OpenCode 配置
     */
    public void saveOpenCodeConfig(OpenCodeConfig config) {
        Path configPath = OPENCODE_CONFIG_PATH;
        try {
            Files.createDirectories(configPath.getParent());

            JsonObject root = new JsonObject();
            if (Files.exists(configPath)) {
                try {
                    String existing = Files.readString(configPath);
                    root = GsonUtils.parseObject(existing);
                } catch (Exception ignored) {
                }
            }

            if (config.model() != null && !config.model().isBlank()) {
                root.addProperty("model", config.model());
            }

            String effectiveId = config.providerId();
            if (effectiveId != null && !effectiveId.isBlank()) {
                if (!root.has("provider") || !root.get("provider").isJsonObject()) {
                    root.add("provider", new JsonObject());
                }
                JsonObject provider = root.getAsJsonObject("provider");

                provider.remove(effectiveId);
                JsonObject providerEntry = new JsonObject();
                JsonObject options = new JsonObject();
                if (config.apiKey() != null && !config.apiKey().isBlank()) {
                    options.addProperty("apiKey", config.apiKey());
                }
                if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                    options.addProperty("baseURL", config.baseUrl());
                }
                if (options.size() > 0) {
                    providerEntry.add("options", options);
                }
                provider.add(effectiveId, providerEntry);
            }

            String json = GsonUtils.toJson(root);
            Files.writeString(configPath, json);
            log.info("OpenCode config saved to {}", configPath);
        } catch (IOException e) {
            log.warn("Failed to save OpenCode config", e);
            throw new RuntimeException("Failed to save OpenCode config: " + e.getMessage(), e);
        }
    }

    // ==================== Codex ====================

    /**
     * 读取 Codex 配置（从 config.toml + auth.json + 环境变量）。
     * <p>
     * API Key 优先级：shell 环境变量 > ~/.codex/auth.json。
     * model 从 config.toml 的 model 字段读取，
     * baseUrl 从 config.toml 的 model_providers 段读取。
     * </p>
     *
     * @param commandPath 用户覆盖的命令路径，可为 null
     * @return Codex 配置
     */
    public CodexConfig readCodexConfig(String commandPath) {
        String apiKey = this.readCodexApiKey();

        String model = "";
        String baseUrl = "";
        Path configPath = CODEX_CONFIG_PATH;
        if (Files.exists(configPath)) {
            try {
                List<String> lines = Files.readAllLines(configPath);
                boolean inCustomProvider = false;
                for (String line : lines) {
                    Matcher modelMatcher = TOML_MODEL_PATTERN.matcher(line);
                    if (modelMatcher.matches()) {
                        model = modelMatcher.group(1);
                    }
                    if (line.contains("[model_providers")) {
                        inCustomProvider = true;
                    } else if (line.startsWith("[") && !line.contains("model_providers")) {
                        inCustomProvider = false;
                    }
                    if (inCustomProvider) {
                        Matcher urlMatcher = TOML_PROVIDER_BASE_URL_PATTERN.matcher(line);
                        if (urlMatcher.matches()) {
                            baseUrl = urlMatcher.group(1);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read Codex config", e);
            }
        }

        return CodexConfig.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .commandPath(commandPath != null ? commandPath : "")
                .build();
    }

    /**
     * 读取 Codex API Key。
     * <p>
     * 优先从 shell 环境变量读取 OPENAI_API_KEY，
     * 若为空则从 ~/.codex/auth.json 文件中读取。
     * </p>
     *
     * @return API Key，未找到返回空字符串
     */
    private String readCodexApiKey() {
        java.util.Map<String, String> envVars = this.readShellExports();
        String apiKey = envVars.getOrDefault("OPENAI_API_KEY", "");
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        Path authPath = CODEX_AUTH_PATH;
        if (Files.exists(authPath)) {
            try {
                String json = Files.readString(authPath);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                var keyEl = obj.get("OPENAI_API_KEY");
                if (keyEl != null && !keyEl.isJsonNull()) {
                    String key = keyEl.getAsString();
                    if (!key.isBlank()) {
                        return key;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read Codex auth.json", e);
            }
        }
        return "";
    }

    /**
     * 保存 Codex 配置（写入 config.toml + shell profile 环境变量）。
     *
     * @param config Codex 配置
     */
    public void saveCodexConfig(CodexConfig config) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            if (IS_WINDOWS) {
                this.writeCodexAuthJson(config.apiKey());
            } else {
                Path shellFile = this.detectShellProfile();
                this.writeShellExport(shellFile, "OPENAI_API_KEY", config.apiKey());
                log.info("Codex API key saved to {}", shellFile);
            }
        }

        Path configPath = CODEX_CONFIG_PATH;
        try {
            Files.createDirectories(configPath.getParent());

            List<String> lines = new ArrayList<>();
            if (Files.exists(configPath)) {
                lines = new ArrayList<>(Files.readAllLines(configPath));
            }

            boolean hasModel = false;
            boolean hasProvider = false;
            int providerStartIdx = -1;
            int providerEndIdx = -1;

            for (int i = 0; i < lines.size(); i++) {
                Matcher modelMatcher = TOML_MODEL_PATTERN.matcher(lines.get(i));
                if (modelMatcher.matches()) {
                    if (config.model() != null && !config.model().isBlank()) {
                        lines.set(i, "model = \"" + config.model() + "\"");
                    }
                    hasModel = true;
                }
                if (lines.get(i).contains("[model_providers.easyagent]")) {
                    hasProvider = true;
                    providerStartIdx = i;
                    for (int j = i + 1; j < lines.size(); j++) {
                        if (lines.get(j).startsWith("[") && !lines.get(j).contains("model_providers")) {
                            providerEndIdx = j;
                            break;
                        }
                    }
                    if (providerEndIdx < 0) {
                        providerEndIdx = lines.size();
                    }
                }
            }

            if (!hasModel && config.model() != null && !config.model().isBlank()) {
                lines.add(0, "model = \"" + config.model() + "\"");
            }

            if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                if (hasProvider && providerStartIdx >= 0) {
                    boolean foundBaseUrl = false;
                    for (int i = providerStartIdx + 1; i < providerEndIdx; i++) {
                        if (lines.get(i).contains("base_url")) {
                            lines.set(i, "base_url = \"" + config.baseUrl() + "\"");
                            foundBaseUrl = true;
                            break;
                        }
                    }
                    if (!foundBaseUrl) {
                        lines.add(providerStartIdx + 1, "base_url = \"" + config.baseUrl() + "\"");
                    }
                } else {
                    lines.add("");
                    lines.add("[model_providers.easyagent]");
                    lines.add("name = \"easyagent\"");
                    lines.add("base_url = \"" + config.baseUrl() + "\"");
                    lines.add("wire_api = \"responses\"");
                }
            }

            Files.writeString(configPath, String.join("\n", lines));
            log.info("Codex config saved to {}", configPath);
        } catch (IOException e) {
            log.warn("Failed to save Codex config", e);
            throw new RuntimeException("Failed to save Codex config: " + e.getMessage(), e);
        }
    }

    // ==================== Shell Profile Helpers ====================

    /**
     * 从 Claude 的 {@code ~/.claude/settings.json} 中读取 {@code env} 段的配置。
     * <p>
     * Windows 下 Claude 不写入 shell profile，而是将环境变量存储在 settings.json 的 env 字段中。
     * </p>
     *
     * @return 环境变量映射
     */
    private Map<String, String> readClaudeSettingsJson() {
        Map<String, String> vars = new HashMap<>();
        if (!Files.exists(CLAUDE_SETTINGS_PATH)) {
            return vars;
        }
        try {
            String json = Files.readString(CLAUDE_SETTINGS_PATH);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject env = root.getAsJsonObject("env");
            if (env != null) {
                for (Map.Entry<String, JsonElement> e : env.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        vars.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read Claude settings.json: {}", CLAUDE_SETTINGS_PATH, e);
        }
        return vars;
    }

    /**
     * 检测当前用户的 shell profile 文件路径。
     *
     * @return shell profile 文件路径
     */
    private Path detectShellProfile() {
        if (!IS_WINDOWS && Files.exists(ZSHRC_PATH)) {
            return ZSHRC_PATH;
        }
        if (!IS_WINDOWS && Files.exists(BASHRC_PATH)) {
            return BASHRC_PATH;
        }
        return ZSHRC_PATH;
    }

    /**
     * 将 Claude 配置写入 {@code ~/.claude/settings.json} 的 {@code env} 段。
     * <p>
     * Windows 下 Claude 使用 settings.json 存储环境变量，不写入 shell profile。
     * </p>
     *
     * @param config Claude 配置
     */
    private void writeClaudeSettingsJson(ClaudeConfig config) {
        try {
            Files.createDirectories(CLAUDE_SETTINGS_PATH.getParent());
            JsonObject root = new JsonObject();
            if (Files.exists(CLAUDE_SETTINGS_PATH)) {
                try {
                    String existing = Files.readString(CLAUDE_SETTINGS_PATH);
                    root = JsonParser.parseString(existing).getAsJsonObject();
                } catch (Exception ignored) {
                }
            }
            JsonObject env = root.has("env") && root.get("env").isJsonObject()
                    ? root.getAsJsonObject("env")
                    : new JsonObject();
            this.putEnvIfNotBlank(env, "ANTHROPIC_BASE_URL", config.baseUrl());
            this.putEnvIfNotBlank(env, "ANTHROPIC_API_KEY", config.apiKey());
            this.putEnvIfNotBlank(env, "ANTHROPIC_AUTH_TOKEN", config.authToken());
            this.putEnvIfNotBlank(env, "ANTHROPIC_MODEL", config.model());
            root.add("env", env);
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(CLAUDE_SETTINGS_PATH, json);
            log.info("Claude Code config saved to {}", CLAUDE_SETTINGS_PATH);
        } catch (IOException e) {
            log.warn("Failed to write Claude settings.json: {}", CLAUDE_SETTINGS_PATH, e);
            throw new RuntimeException("Failed to save Claude config: " + e.getMessage(), e);
        }
    }

    /**
     * 向 JSON 对象中写入非空环境变量值。
     *
     * @param env   env JSON 对象
     * @param key   环境变量名
     * @param value 环境变量值
     */
    private void putEnvIfNotBlank(com.google.gson.JsonObject env, String key, String value) {
        if (value != null && !value.isBlank()) {
            env.addProperty(key, value);
        }
    }

    /**
     * 将 Codex API Key 写入 {@code ~/.codex/auth.json}。
     * <p>
     * Windows 下 Codex 使用 auth.json 存储密钥，不写入 shell profile。
     * </p>
     *
     * @param apiKey API Key
     */
    private void writeCodexAuthJson(String apiKey) {
        try {
            Files.createDirectories(CODEX_AUTH_PATH.getParent());
            JsonObject obj = new JsonObject();
            if (Files.exists(CODEX_AUTH_PATH)) {
                try {
                    String existing = Files.readString(CODEX_AUTH_PATH);
                    obj = JsonParser.parseString(existing).getAsJsonObject();
                } catch (Exception ignored) {
                }
            }
            obj.addProperty("OPENAI_API_KEY", apiKey);
            Files.writeString(CODEX_AUTH_PATH, new GsonBuilder().setPrettyPrinting().create().toJson(obj));
            log.info("Codex API key saved to {}", CODEX_AUTH_PATH);
        } catch (IOException e) {
            log.warn("Failed to write Codex auth.json: {}", CODEX_AUTH_PATH, e);
            throw new RuntimeException("Failed to save Codex API key: " + e.getMessage(), e);
        }
    }

    /**
     * 从 shell profile 中解析所有 export 变量。
     *
     * @return 变量名到值的映射
     */
    private java.util.Map<String, String> readShellExports() {
        java.util.Map<String, String> vars = new java.util.HashMap<>();
        if (IS_WINDOWS) {
            return vars;
        }
        Path shellProfile = this.detectShellProfile();
        if (!Files.exists(shellProfile)) {
            return vars;
        }
        try {
            List<String> lines = Files.readAllLines(shellProfile);
            for (String line : lines) {
                Matcher m = EXPORT_PATTERN.matcher(line);
                if (m.matches()) {
                    vars.put(m.group(1), m.group(2));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read shell profile: {}", shellProfile, e);
        }
        return vars;
    }

    /**
     * 向 shell profile 中写入或更新一个 export 语句。
     * <p>
     * 如果变量已存在则替换其值，否则追加到文件末尾。
     * 使用 EasyAgent 标记注释以便识别。
     * </p>
     *
     * @param shellFile shell profile 文件路径
     * @param key       环境变量名
     * @param value     环境变量值
     */
    private void writeShellExport(Path shellFile, String key, String value) {
        try {
            List<String> lines = Files.exists(shellFile) ? new ArrayList<>(Files.readAllLines(shellFile)) : new ArrayList<>();

            String exportLine = "export " + key + "=\"" + (value != null ? value : "") + "\"";
            boolean replaced = false;

            String marker = "#EasyAgent";
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = EXPORT_PATTERN.matcher(lines.get(i));
                if (m.matches() && key.equals(m.group(1))) {
                    lines.set(i, exportLine + "  " + marker);
                    replaced = true;
                    break;
                }
            }

            if (!replaced) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add(exportLine + "  " + marker);
            }

            Files.writeString(shellFile, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            log.warn("Failed to write shell export {} to {}", key, shellFile, e);
        }
    }

    /**
     * Provider 信息，用于前端下拉选择。
     *
     * @param id          Provider ID
     * @param displayName 显示名称
     */
    public record ProviderInfo(String id, String displayName) {
    }

    // ==================== Profile Management ====================

    /** CliProfile 列表的泛型类型标记。 */
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<CliProfile>>() {}.getType();

    /**
     * 从持久化 JSON 字符串加载配置档案列表。
     *
     * @param profilesJson JSON 字符串（cliType -> List<CliProfile> 的序列化）
     * @return cliType -> List<CliProfile>
     */
    public List<CliProfile> loadProfiles(String profilesJson) {
        if (profilesJson == null || profilesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<CliProfile> profiles = GsonUtils.fromJson(profilesJson, PROFILE_LIST_TYPE);
            return profiles != null ? profiles : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to parse CLI profiles", e);
            return Collections.emptyList();
        }
    }

    /**
     * 将配置档案列表序列化为 JSON 字符串。
     *
     * @param profiles 配置档案列表
     * @return JSON 字符串
     */
    public String serializeProfiles(List<CliProfile> profiles) {
        return GsonUtils.toJson(profiles);
    }

    /**
     * 创建新的配置档案，生成唯一 ID。
     *
     * @param name    档案名称
     * @param cliType CLI 类型
     * @return 新建的配置档案
     */
    public CliProfile createProfile(String name, String cliType) {
        return CliProfile.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .cliType(cliType)
                .build();
    }

    /**
     * 应用指定档案的配置到 CLI 配置文件。
     *
     * @param profile 配置档案
     */
    public void applyProfile(CliProfile profile) {
        switch (profile.getCliType()) {
            case "CLAUDE" -> {
                if (profile.getClaude() != null) {
                    this.saveClaudeConfig(profile.getClaude());
                }
            }
            case "OPENCODE" -> {
                if (profile.getOpencode() != null) {
                    this.saveOpenCodeConfig(profile.getOpencode());
                }
            }
            case "CODEX" -> {
                if (profile.getCodex() != null) {
                    this.saveCodexConfig(profile.getCodex());
                }
            }
            default -> log.warn("Unknown CLI type: {}", profile.getCliType());
        }
    }

    /**
     * 将当前 CLI 配置快照为一个新档案。
     *
     * @param name    档案名称
     * @param cliType CLI 类型
     * @return 包含当前配置的档案
     */
    public CliProfile snapshotCurrentConfig(String name, String cliType) {
        CliProfile profile = this.createProfile(name, cliType);
        switch (cliType) {
            case "CLAUDE" -> profile.setClaude(this.readClaudeConfig(null));
            case "OPENCODE" -> profile.setOpencode(this.readOpenCodeConfig(null));
            case "CODEX" -> profile.setCodex(this.readCodexConfig(null));
            default -> log.warn("Unknown CLI type: {}", cliType);
        }
        return profile;
    }
}
