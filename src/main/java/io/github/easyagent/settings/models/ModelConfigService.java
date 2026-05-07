package io.github.easyagent.settings.models;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
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
import java.util.stream.Collectors;

/**
 * AI 模型配置管理服务。
 * <p>
 * 提供模型配置的加载、同步、持久化和 CLI 查询功能。
 * 支持三种数据来源：
 * <ul>
 *   <li>远程 GitHub 仓库的 {@code models.json}</li>
 *   <li>本地项目根目录的 {@code models.json}</li>
 *   <li>OpenCode CLI 的 {@code opencode models --verbose} 命令</li>
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

    /** 默认上下文窗口大小（128K）。 */
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /** {@code List<ModelInfo>} 的泛型类型标记。 */
    private static final Type MODEL_LIST_TYPE = new TypeToken<List<ModelInfo>>() {}.getType();

    /** 内存缓存，{@link CLIType} -> List<{@link ModelInfo}>。 */
    private final Map<CLIType, List<ModelInfo>> modelCache = new ConcurrentHashMap<>();

    /** 默认模型配置，{@link CLIType} -> {modelId, contextWindow}。 */
    private final Map<CLIType, DefaultModelConfig> defaultModels = new ConcurrentHashMap<>();

    /** HTTP 客户端，用于远程同步。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
     * 从 JSON 字符串加载模型配置到内存缓存。
     *
     * @param json 模型配置 JSON 字符串
     */
    public void loadFromJson(String json) {
        try {
            JsonObject root = GsonUtils.parseObject(json);
            List<ModelInfo> all = GsonUtils.fromJson(
                    root.getAsJsonArray("models").toString(), MODEL_LIST_TYPE);
            this.modelCache.clear();
            for (ModelInfo m : all) {
                this.modelCache.computeIfAbsent(m.cliType(), k -> new ArrayList<>()).add(m);
            }

            this.defaultModels.clear();
            if (root.has("defaultModels") && root.get("defaultModels").isJsonObject()) {
                JsonObject dm = root.getAsJsonObject("defaultModels");
                for (String key : dm.keySet()) {
                    try {
                        CLIType cliType = CLIType.valueOf(key);
                        JsonObject val = dm.getAsJsonObject(key);
                        String modelId = GsonUtils.getString(val, "modelId");
                        int ctx = GsonUtils.getInt(val, "contextWindow", DEFAULT_CONTEXT_WINDOW);
                        this.defaultModels.put(cliType, new DefaultModelConfig(
                                modelId != null ? modelId : "",
                                ctx
                        ));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }

            log.debug("Loaded {} models from JSON", all.size());
        } catch (Exception e) {
            log.warn("Failed to parse models JSON", e);
        }
    }

    /**
     * 将当前内存缓存序列化为 JSON 字符串，用于持久化。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("models", GsonUtils.toJsonTree(getAllModels()));

        JsonObject dm = new JsonObject();
        for (Map.Entry<CLIType, DefaultModelConfig> entry : this.defaultModels.entrySet()) {
            JsonObject val = new JsonObject();
            val.addProperty("modelId", entry.getValue().modelId());
            val.addProperty("contextWindow", entry.getValue().contextWindow());
            dm.add(entry.getKey().name(), val);
        }
        root.add("defaultModels", dm);

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
     * 查询 OpenCode CLI 可用模型列表。
     * <p>
     * 执行 {@code opencode models --verbose} 命令，解析 JSON 输出中的
     * 模型 ID、名称和上下文窗口大小。
     * </p>
     *
     * @return 模型配置列表，查询失败返回空列表
     */
    public List<ModelInfo> queryOpenCodeModels() {
        List<ModelInfo> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(CLIType.OPENCODE.getCommandPath(), "models", "--refresh", "--verbose");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();

            String raw = stripAnsi(output.toString()).trim();
            for (String json : this.extractJsonBlocks(raw)) {
                try {
                    ModelInfo model = this.parseOpenCodeModel(json);
                    if (model != null) {
                        result.add(model);
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("Queried {} models from OpenCode CLI", result.size());
        } catch (Exception e) {
            log.warn("Failed to query OpenCode models", e);
        }
        return result;
    }

    /**
     * 保存用户编辑后的模型列表到内存缓存。
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
     * 获取指定 CLI 类型的默认模型配置。
     *
     * @param cliType CLI 类型
     * @return 默认模型配置，不存在返回 {@code null}
     */
    public DefaultModelConfig getDefaultModel(CLIType cliType) {
        return this.defaultModels.get(cliType);
    }

    /**
     * 获取所有默认模型配置。
     *
     * @return CLI 类型到默认模型配置的映射
     */
    public Map<CLIType, DefaultModelConfig> getAllDefaultModels() {
        return new LinkedHashMap<>(this.defaultModels);
    }

    /**
     * 保存指定 CLI 类型的默认模型配置。
     *
     * @param cliType CLI 类型
     * @param config  默认模型配置
     */
    public void saveDefaultModel(CLIType cliType, DefaultModelConfig config) {
        if (config != null) {
            this.defaultModels.put(cliType, config);
        } else {
            this.defaultModels.remove(cliType);
        }
    }

    /**
     * 解析单个 OpenCode 模型 JSON 块为 {@link ModelInfo}。
     *
     * @param json 模型 JSON 字符串
     * @return {@link ModelInfo}，缺少必要字段时返回 {@code null}
     */
    private ModelInfo parseOpenCodeModel(String json) {
        JsonObject obj = GsonUtils.parseObject(json);
        String modelId = GsonUtils.getString(obj, "id");
        if (modelId == null) {
            return null;
        }
        String providerId = GsonUtils.getString(obj, "providerID");
        String fullId = (providerId == null || providerId.isEmpty()) ? modelId : providerId + "/" + modelId;
        String name = GsonUtils.getString(obj, "name");
        String displayName = (name != null) ? name : modelId;

        int context = 0;
        JsonObject limit = GsonUtils.getJsonObject(obj, "limit");
        if (limit != null) {
            context = GsonUtils.getInt(limit, "context", 0);
        }

        return ModelInfo.builder()
                .modelId(fullId)
                .displayName(displayName)
                .cliType(CLIType.OPENCODE)
                .contextWindow(context > 0 ? context : ModelInfo.DEFAULT_CONTEXT_WINDOW)
                .provider(providerId != null ? providerId : "")
                .build();
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

    /**
     * 在 JSON 字符串中查找第一个顶层闭合大括号位置。
     *
     * @param json JSON 字符串
     * @return 闭合大括号索引，未找到返回 -1
     */
    private int findMatchingBrace(String json) {
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inStr) {
                i++;
                continue;
            }
            if (c == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 从 CLI 原始输出中提取顶层 JSON 对象块。
     *
     * @param raw CLI 原始输出
     * @return JSON 对象字符串列表
     */
    private List<String> extractJsonBlocks(String raw) {
        List<String> blocks = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            int start = raw.indexOf('{', index);
            if (start < 0) {
                break;
            }
            int end = this.findMatchingBrace(raw.substring(start));
            if (end < 0) {
                break;
            }
            blocks.add(raw.substring(start, start + end + 1));
            index = start + end + 1;
        }
        return blocks;
    }

    /**
     * 去除终端 ANSI 转义序列。
     *
     * @param text 原始终端输出
     * @return 清洗后的文本
     */
    private static String stripAnsi(String text) {
        return text.replaceAll("\\u001B\\[[;\\d]*m", "");
    }
}
