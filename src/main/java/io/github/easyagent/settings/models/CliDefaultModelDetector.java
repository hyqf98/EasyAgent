package io.github.easyagent.settings.models;

import com.google.gson.JsonObject;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI 默认模型检测器。
 * <p>
 * 从各 CLI 工具的配置文件中读取默认模型名称和上下文窗口大小。
 * <ul>
 *   <li>Claude Code：{@code ~/.claude/settings.json} 的 {@code model} 字段或环境变量 {@code ANTHROPIC_MODEL}</li>
 *   <li>OpenCode：{@code ~/.config/opencode/opencode.json} 的 {@code model} 字段</li>
 *   <li>Codex：{@code ~/.codex/config.toml} 的 {@code model} 字段</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/5/13
 * @since 1.0.0
 */
@Slf4j
public class CliDefaultModelDetector {

    private static final String HOME = System.getProperty("user.home");
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private static final Path CLAUDE_SETTINGS_PATH = Path.of(HOME, ".claude", "settings.json");
    private static final Path OPENCODE_CONFIG_PATH =
            Path.of(HOME, ".config", "opencode", "opencode.json");
    private static final Path CODEX_CONFIG_PATH = Path.of(HOME, ".codex", "config.toml");

    private static final Pattern TOML_MODEL_PATTERN = Pattern.compile(
            "^\\s*model\\s*=\\s*\"([^\"]+)\"");

    /** 缓存检测结果。 */
    private final Map<CLIType, DefaultModelInfo> cache = new ConcurrentHashMap<>();

    /**
     * 检测指定 CLI 类型的默认模型信息。
     * <p>
     * 优先使用缓存，未缓存时从配置文件读取。
     * </p>
     *
     * @param cliType CLI 类型
     * @param contextWindowFallback 上下文窗口回退值
     * @return 默认模型信息，未检测到返回 null
     */
    public DefaultModelInfo detect(CLIType cliType, int contextWindowFallback) {
        return this.cache.computeIfAbsent(cliType,
                type -> this.doDetect(type, contextWindowFallback));
    }

    /**
     * 清除缓存，强制下次重新检测。
     */
    public void clearCache() {
        this.cache.clear();
    }

    private DefaultModelInfo doDetect(CLIType cliType, int contextWindowFallback) {
        try {
            String modelName = switch (cliType) {
                case CLAUDE -> this.detectClaudeModel();
                case OPENCODE -> this.detectOpenCodeModel();
                case CODEX -> this.detectCodexModel();
            };

            if (modelName != null && !modelName.isBlank()) {
                log.info("Detected default model for {}: {}", cliType.name(), modelName);
                return DefaultModelInfo.builder()
                        .displayName(this.buildDisplayName(cliType, modelName))
                        .modelId(modelName)
                        .contextWindow(contextWindowFallback)
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to detect default model for {}", cliType.name(), e);
        }
        return null;
    }

    /**
     * 从 Claude Code 配置中检测默认模型。
     * <p>
     * 优先级：{@code settings.json} 的 {@code model} 字段 > 环境变量 {@code ANTHROPIC_MODEL}
     * </p>
     */
    private String detectClaudeModel() {
        if (Files.exists(CLAUDE_SETTINGS_PATH)) {
            try {
                String json = Files.readString(CLAUDE_SETTINGS_PATH);
                JsonObject root = GsonUtils.parseObject(json);

                String model = GsonUtils.getString(root, "model");
                if (model != null && !model.isBlank()) {
                    return model;
                }

                JsonObject env = GsonUtils.getJsonObject(root, "env");
                if (env != null) {
                    String envModel = GsonUtils.getString(env, "ANTHROPIC_MODEL");
                    if (envModel != null && !envModel.isBlank()) {
                        return envModel;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read Claude settings.json", e);
            }
        }

        String sysEnv = System.getenv("ANTHROPIC_MODEL");
        return (sysEnv != null && !sysEnv.isBlank()) ? sysEnv : null;
    }

    /**
     * 从 OpenCode 配置中检测默认模型。
     * <p>
     * 读取 {@code opencode.json} 的 {@code model} 字段，格式为 {@code provider/model}。
     * </p>
     */
    private String detectOpenCodeModel() {
        Path configPath = this.resolveOpenCodeConfigPath();
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            String json = Files.readString(configPath);
            JsonObject root = GsonUtils.parseObject(json);
            String model = GsonUtils.getString(root, "model");
            return (model != null && !model.isBlank()) ? model : null;
        } catch (Exception e) {
            log.warn("Failed to read OpenCode config", e);
            return null;
        }
    }

    /**
     * 从 Codex 配置中检测默认模型。
     * <p>
     * 读取 {@code config.toml} 的顶层 {@code model} 字段。
     * </p>
     */
    private String detectCodexModel() {
        if (!Files.exists(CODEX_CONFIG_PATH)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(CODEX_CONFIG_PATH);
            for (String line : lines) {
                Matcher m = TOML_MODEL_PATTERN.matcher(line);
                if (m.matches()) {
                    String model = m.group(1);
                    if (!model.isBlank()) {
                        return model;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read Codex config.toml", e);
        }
        return null;
    }

    /**
     * 解析 OpenCode 配置文件路径。
     * <p>
     * Windows 下路径为 {@code %APPDATA%\opencode\opencode.json}，
     * 其他平台为 {@code ~/.config/opencode/opencode.json}。
     * </p>
     */
    private Path resolveOpenCodeConfigPath() {
        if (IS_WINDOWS) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                return Path.of(appData, "opencode", "opencode.json");
            }
        }
        return OPENCODE_CONFIG_PATH;
    }

    /**
     * 构建前端显示名称。
     *
     * @param cliType CLI 类型
     * @param modelName 模型名称
     * @return 显示名称
     */
    private String buildDisplayName(CLIType cliType, String modelName) {
        return cliType == CLIType.OPENCODE ? "默认 (" + modelName + ")" : "默认 (" + modelName + ")";
    }
}
