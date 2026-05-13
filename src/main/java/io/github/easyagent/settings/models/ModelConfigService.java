package io.github.easyagent.settings.models;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 模型配置管理服务。
 * <p>
 * 提供模型配置的加载、同步、持久化和查询功能。
 * 支持两种数据来源：
 * <ul>
 *   <li>远程 GitHub 仓库的 {@code models.json}（v2 格式）</li>
 *   <li>{@code models.dev} API 获取 OpenCode 支持的全量模型</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Slf4j
public class ModelConfigService {

    /** 远程 models.json 的 GitHub raw 地址。 */
    private static final String REMOTE_MODELS_URL =
            "https://raw.githubusercontent.com/hyqf98/EasyAgent/main/models.json";

    /** models.dev API 地址，包含所有 OpenCode 支持的 provider 和模型。 */
    private static final String MODELS_DEV_URL = "https://models.dev/api.json";

    /** 默认上下文窗口大小（128K）。 */
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /** 内置推理等级（远程同步失败时的兜底）。 */
    private static final Map<CLIType, List<String>> BUILTIN_REASONING_LEVELS = Map.of(
            CLIType.CLAUDE, List.of("low", "medium", "high", "xhigh", "max"),
            CLIType.OPENCODE, List.of("minimal", "low", "medium", "high", "max"),
            CLIType.CODEX, List.of("low", "medium", "high")
    );

    /** {@code List<ModelInfo>} 的泛型类型标记。 */
    private static final Type MODEL_LIST_TYPE = new TypeToken<List<ModelInfo>>() {}.getType();

    /** 内存缓存，{@link CLIType} -> List<{@link ModelInfo}>。 */
    private final Map<CLIType, List<ModelInfo>> modelCache = new ConcurrentHashMap<>();

    /** 推理等级配置，{@link CLIType} -> 可用等级列表。 */
    private final Map<CLIType, List<String>> reasoningLevels = new ConcurrentHashMap<>();

    /** 默认模型信息，{@link CLIType} -> {displayName, contextWindow}。 */
    private final Map<CLIType, DefaultModelInfo> defaultModelInfoMap = new ConcurrentHashMap<>();

    /** 从 models.dev API 缓存的动态 provider 列表（id + displayName）。 */
    private volatile List<CliConfigService.ProviderInfo> cachedDynamicProviders = Collections.emptyList();

    /** HTTP 客户端，用于远程同步（自动检测环境变量代理）。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(detectProxy())
            .build();

    private static final Pattern PROXY_URL_PATTERN = Pattern.compile(
            "https?://([^:/]+)(?::(\\d+))?/?");

    /**
     * 从环境变量 {@code HTTP_PROXY} / {@code HTTPS_PROXY} / {@code ALL_PROXY} 检测代理。
     *
     * @return 代理选择器，无代理时返回 {@link ProxySelector#getDefault()}
     */
    private static ProxySelector detectProxy() {
        String proxyUrl = System.getenv("HTTPS_PROXY");
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = System.getenv("HTTP_PROXY");
        }
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = System.getenv("ALL_PROXY");
        }
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = System.getenv("https_proxy");
        }
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = System.getenv("http_proxy");
        }
        if (proxyUrl == null || proxyUrl.isBlank()) {
            proxyUrl = System.getenv("all_proxy");
        }
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            Matcher m = PROXY_URL_PATTERN.matcher(proxyUrl.trim());
            if (m.find()) {
                String host = m.group(1);
                int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 7890;
                log.info("Detected proxy from env: {}:{}", host, port);
                return ProxySelector.of(new InetSocketAddress(host, port));
            }
        }
        return ProxySelector.getDefault();
    }

    /**
     * 获取指定 CLI 类型的模型列表。
     *
     * @param cliType CLI 类型，为 {@code null} 时返回全部
     * @return 模型配置列表
     */
    public List<ModelInfo> getModels(CLIType cliType) {
        if (cliType != null) {
            return modelCache.getOrDefault(cliType, Collections.emptyList());
        }
        return modelCache.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * 获取全部模型列表。
     *
     * @return 所有模型配置列表
     */
    public List<ModelInfo> getAllModels() {
        return getModels(null);
    }

    /**
     * 从 JSON 字符串加载模型配置到内存缓存（仅支持 v2 格式）。
     *
     * @param json 模型配置 JSON 字符串
     */
    public void loadFromJson(String json) {
        try {
            JsonObject root = GsonUtils.parseObject(json);
            this.modelCache.clear();
            this.reasoningLevels.clear();
            this.defaultModelInfoMap.clear();

            if (root.has("cliGroups") && root.get("cliGroups").isJsonObject()) {
                this.loadCliGroups(root.getAsJsonObject("cliGroups"));
            }

            int total = this.modelCache.values().stream().mapToInt(List::size).sum();
            log.debug("Loaded {} models from JSON", total);
        } catch (Exception e) {
            log.warn("Failed to parse models JSON", e);
        }
    }

    /**
     * 解析 v2 格式的 cliGroups 对象。
     *
     * @param cliGroups CLI 分组 JSON 对象
     */
    private void loadCliGroups(JsonObject cliGroups) {
        for (String cliKey : cliGroups.keySet()) {
            try {
                CLIType cliType = CLIType.valueOf(cliKey);
                JsonObject group = cliGroups.getAsJsonObject(cliKey);

                if (group.has("models") && group.get("models").isJsonArray()) {
                    List<ModelInfo> models = GsonUtils.fromJson(
                            group.getAsJsonArray("models").toString(), MODEL_LIST_TYPE);
                    List<ModelInfo> enriched = models.stream()
                            .map(m -> ModelInfo.builder()
                                    .modelId(m.modelId())
                                    .displayName(m.displayName())
                                    .cliType(cliType)
                                    .contextWindow(m.contextWindow())
                                    .provider(m.provider())
                                    .build())
                            .collect(Collectors.toList());
                    this.modelCache.put(cliType, enriched);
                }

                if (group.has("reasoningLevels") && group.get("reasoningLevels").isJsonArray()) {
                    List<String> levels = new ArrayList<>();
                    group.getAsJsonArray("reasoningLevels").forEach(e -> levels.add(e.getAsString()));
                    this.reasoningLevels.put(cliType, levels);
                }

                if (group.has("defaultModelInfo") && group.get("defaultModelInfo").isJsonObject()) {
                    JsonObject info = group.getAsJsonObject("defaultModelInfo");
                    DefaultModelInfo defaultInfo = DefaultModelInfo.builder()
                            .displayName(GsonUtils.getString(info, "displayName"))
                            .contextWindow(GsonUtils.getInt(info, "contextWindow",
                                    DefaultModelInfo.DEFAULT_CONTEXT_WINDOW))
                            .build();
                    this.defaultModelInfoMap.put(cliType, defaultInfo);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    /**
     * 将当前内存缓存序列化为 JSON 字符串，用于持久化。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);

        JsonObject cliGroups = new JsonObject();
        for (CLIType cliType : CLIType.values()) {
            JsonObject group = new JsonObject();

            List<String> levels = this.getReasoningLevels(cliType);
            group.add("reasoningLevels", GsonUtils.toJsonTree(levels));

            List<ModelInfo> models = this.modelCache.getOrDefault(cliType, Collections.emptyList());
            group.add("models", GsonUtils.toJsonTree(models));

            DefaultModelInfo defaultInfo = this.defaultModelInfoMap.get(cliType);
            if (defaultInfo != null) {
                group.add("defaultModelInfo", GsonUtils.toJsonTree(defaultInfo));
            }

            cliGroups.add(cliType.name(), group);
        }
        root.add("cliGroups", cliGroups);

        return GsonUtils.toJson(root);
    }

    /**
     * 从本地项目根目录的 models.json 加载。
     *
     * @return {@code true} 加载成功
     */
    public boolean loadFromLocal() {
        String rootPath = this.findProjectRoot();
        if (rootPath == null) {
            return false;
        }
        Path file = Paths.get(rootPath, "models.json");
        if (!Files.exists(file)) {
            return false;
        }
        try {
            String json = Files.readString(file);
            this.loadFromJson(json);
            log.info("Loaded models from local file: {}", file);
            return true;
        } catch (IOException e) {
            log.warn("Failed to read local models.json", e);
            return false;
        }
    }

    /**
     * 从远程 GitHub 仓库同步最新的 models.json。
     *
     * @return 同步后的 JSON 字符串，失败返回 {@code null}
     */
    public String syncFromRemote() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(REMOTE_MODELS_URL))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String json = response.body();
                this.loadFromJson(json);
                log.info("Synced {} models from remote", this.getAllModels().size());
                return json;
            }
            log.warn("Failed to sync models, HTTP status: {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to sync models from remote", e);
        }
        return null;
    }

    /**
     * 从 models.dev API 获取所有 provider 的模型列表。
     * <p>
     * 遍历 {@code https://models.dev/api.json} 返回的所有 provider，
     * 将每个 provider 下的模型以 {@code providerId/modelId} 格式汇总。
     * 同时更新内部的动态 provider 缓存列表，供配置管理页面使用。
     * </p>
     *
     * @return 模型配置列表，查询失败返回空列表
     */
    public List<ModelInfo> queryModelsDev() {
        List<ModelInfo> result = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODELS_DEV_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("models.dev API returned status: {}", response.statusCode());
                return result;
            }

            JsonObject root = GsonUtils.parseObject(response.body());
            List<CliConfigService.ProviderInfo> dynamicProviders = new ArrayList<>();

            for (String providerKey : root.keySet()) {
                try {
                    JsonObject providerObj = root.getAsJsonObject(providerKey);
                    if (providerObj == null || !providerObj.has("models")) {
                        continue;
                    }
                    String providerName = GsonUtils.getString(providerObj, "name");
                    if (providerName != null && !providerName.isBlank()) {
                        dynamicProviders.add(new CliConfigService.ProviderInfo(providerKey, providerName));
                    }

                    JsonObject modelsObj = providerObj.getAsJsonObject("models");
                    for (String modelId : modelsObj.keySet()) {
                        try {
                            JsonObject m = modelsObj.getAsJsonObject(modelId);
                            String name = GsonUtils.getString(m, "name");
                            String displayName = (name != null && !name.isBlank()) ? name : modelId;

                            int context = 0;
                            JsonObject limit = GsonUtils.getJsonObject(m, "limit");
                            if (limit != null) {
                                context = GsonUtils.getInt(limit, "context", 0);
                            }

                            result.add(ModelInfo.builder()
                                    .modelId(providerKey + "/" + modelId)
                                    .displayName(displayName)
                                    .cliType(CLIType.OPENCODE)
                                    .contextWindow(context > 0 ? context : ModelInfo.DEFAULT_CONTEXT_WINDOW)
                                    .provider(providerKey)
                                    .build());
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            this.cachedDynamicProviders = dynamicProviders;
            log.info("Queried {} models from {} providers via models.dev", result.size(), dynamicProviders.size());
        } catch (Exception e) {
            log.warn("Failed to query models.dev", e);
        }
        return result;
    }

    /**
     * 获取缓存的动态 provider 列表（来自 models.dev API）。
     *
     * @return provider 信息列表，未查询过则返回空列表
     */
    public List<CliConfigService.ProviderInfo> getDynamicProviders() {
        return this.cachedDynamicProviders;
    }

    /**
     * 保存模型列表到内存缓存（覆盖式）。
     *
     * @param models 模型列表
     */
    public void saveModels(List<ModelInfo> models) {
        this.modelCache.clear();
        for (ModelInfo m : models) {
            this.modelCache.computeIfAbsent(m.cliType(), k -> new ArrayList<>()).add(m);
        }
    }

    /**
     * 将模型列表合并到内存缓存，跳过重复的 modelId。
     *
     * @param models 待合并的模型列表
     */
    public void mergeModels(List<ModelInfo> models) {
        var existingIds = this.getAllModels().stream()
                .map(ModelInfo::modelId)
                .collect(Collectors.toSet());
        for (ModelInfo m : models) {
            if (!existingIds.contains(m.modelId())) {
                this.modelCache.computeIfAbsent(m.cliType(), k -> new ArrayList<>()).add(m);
                existingIds.add(m.modelId());
            }
        }
    }

    /**
     * 根据模型 ID 查找上下文窗口大小。
     *
     * @param modelId 模型 ID
     * @return 上下文窗口大小，未找到返回 {@link ModelInfo#DEFAULT_CONTEXT_WINDOW}
     */
    public int getContextWindow(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        String lower = modelId.toLowerCase();
        for (ModelInfo m : this.getAllModels()) {
            if (m.modelId().equalsIgnoreCase(modelId) || lower.contains(m.modelId().toLowerCase())) {
                return m.contextWindow();
            }
        }
        return DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * 获取指定 CLI 类型的推理等级列表。
     *
     * @param cliType CLI 类型
     * @return 可用推理等级列表，不存在返回空列表
     */
    public List<String> getReasoningLevels(CLIType cliType) {
        List<String> levels = this.reasoningLevels.get(cliType);
        if (levels != null && !levels.isEmpty()) {
            return levels;
        }
        return BUILTIN_REASONING_LEVELS.getOrDefault(cliType, Collections.emptyList());
    }

    /**
     * 获取所有 CLI 类型的推理等级配置。
     * <p>
     * 优先返回远程同步的数据，为空时回退到内置默认值。
     * </p>
     *
     * @return CLI 类型到推理等级列表的映射
     */
    public Map<CLIType, List<String>> getAllReasoningLevels() {
        Map<CLIType, List<String>> result = new LinkedHashMap<>();
        for (CLIType cliType : CLIType.values()) {
            result.put(cliType, this.getReasoningLevels(cliType));
        }
        return result;
    }

    /**
     * 获取指定 CLI 类型的默认模型信息。
     *
     * @param cliType CLI 类型
     * @return 默认模型信息，不存在返回 null
     */
    public DefaultModelInfo getDefaultModelInfo(CLIType cliType) {
        return this.defaultModelInfoMap.get(cliType);
    }

    /**
     * 保存指定 CLI 类型的默认模型信息。
     *
     * @param cliType CLI 类型
     * @param info    默认模型信息
     */
    public void saveDefaultModelInfo(CLIType cliType,
                                     DefaultModelInfo info) {
        if (info != null) {
            this.defaultModelInfoMap.put(cliType, info);
        }
    }

    /**
     * 查找项目根目录（向上查找包含 models.json 的目录）。
     *
     * @return 项目根目录路径，未找到返回 {@code null}
     */
    private String findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10; i++) {
            if (Files.exists(dir.resolve("models.json"))) {
                return dir.toString();
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return null;
    }
}
