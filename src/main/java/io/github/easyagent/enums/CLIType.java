package io.github.easyagent.enums;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 类型枚举。
 * <p>
 * 定义系统支持的 CLI 工具类型，每种类型包含名称和默认可执行命令路径。
 * 支持自动检测已安装的 CLI 命令路径，也允许用户手动覆盖。
 * 统一供 AI 模块和 Session 模块共用。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
@Getter
public enum CLIType implements ValueEnum<String> {

    /** OpenCode CLI 工具。 */
    OPENCODE("opencode-cli", "opencode"),

    /** Claude Code CLI 工具。 */
    CLAUDE("claude-cli", "claude"),

    /** Codex CLI 工具。 */
    CODEX("codex-cli", "codex");

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("windows");
    private static final String HOME = System.getProperty("user.home");

    /** 缓存自动检测结果：cliType -> resolvedPath（null 表示未找到）。 */
    private static final Map<CLIType, String> DETECTED_PATH_CACHE = new ConcurrentHashMap<>();

    /** CLI 类型名称。 */
    private final String name;

    /** CLI 默认可执行命令路径。 */
    private final String commandPath;

    CLIType(String name, String commandPath) {
        this.name = name;
        this.commandPath = commandPath;
    }

    @Override
    public String getValue() {
        return this.name;
    }

    /**
     * 自动检测 CLI 可执行文件路径。
     * <p>
     * 通过系统命令 {@code where}（Windows）或 {@code which}（macOS/Linux）查找 PATH 中的可执行文件，
     * 未找到时回退到常见安装路径。结果会缓存，重复调用直接返回缓存值。
     * </p>
     *
     * @return 检测到的绝对路径，未找到返回 null
     */
    public String detectCommandPath() {
        return DETECTED_PATH_CACHE.computeIfAbsent(this, this::doDetectCommandPath);
    }

    /**
     * 解析最终使用的命令路径。
     * <p>
     * 优先级：用户手动覆盖路径 > 自动检测路径 > 枚举默认命令名。
     * </p>
     *
     * @param userOverridePath 用户手动覆盖的路径，可为 null 或空
     * @return 最终使用的命令路径
     */
    public String resolveCommandPath(String userOverridePath) {
        if (userOverridePath != null && !userOverridePath.isBlank()) {
            return userOverridePath.trim();
        }
        String detected = this.detectCommandPath();
        return detected != null ? detected : this.commandPath;
    }

    private String doDetectCommandPath(CLIType type) {
        String cmd = type.commandPath;
        try {
            String detected = this.detectBySystemCommand(type, cmd);
            if (detected != null) {
                return detected;
            }
            return this.detectByFallbackPaths(type, cmd);
        } catch (Exception e) {
            log.warn("Failed to detect {} CLI path", type.name(), e);
            return null;
        }
    }

    /**
     * 通过 {@code where}（Windows）或 {@code which}（macOS/Linux）查找可执行文件。
     *
     * @param type CLI 类型
     * @param cmd  命令名
     * @return 检测到的绝对路径，未找到返回 null
     */
    private String detectBySystemCommand(CLIType type, String cmd) throws Exception {
        String lookupCmd = IS_WINDOWS ? "where" : "which";
        ProcessBuilder pb = new ProcessBuilder(lookupCmd, cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        proc.waitFor();

        if (proc.exitValue() != 0 || output.isEmpty()) {
            log.debug("{} lookup found nothing for: {}", lookupCmd, cmd);
            return null;
        }

        String firstResult = output.split("\\R")[0].trim();
        if (!firstResult.isEmpty() && Files.isExecutable(Path.of(firstResult))) {
            log.info("Detected {} CLI via {} at: {}", type.name(), lookupCmd, firstResult);
            return firstResult;
        }
        return null;
    }

    /**
     * 回退到常见安装路径逐个检查。
     *
     * @param type CLI 类型
     * @param cmd  命令名
     * @return 检测到的绝对路径，未找到返回 null
     */
    private String detectByFallbackPaths(CLIType type, String cmd) {
        String[] fallbackDirs;
        if (IS_WINDOWS) {
            String appData = System.getenv("APPDATA");
            fallbackDirs = new String[]{
                    appData != null ? Path.of(appData, "npm").toString() : null,
                    "C:\\Program Files\\nodejs"
            };
        } else {
            fallbackDirs = new String[]{
                    "/opt/homebrew/bin",
                    "/usr/local/bin",
                    HOME + "/.npm/bin",
                    HOME + "/.nvm/versions/node/current/bin"
            };
        }

        for (String dir : fallbackDirs) {
            if (dir == null) {
                continue;
            }
            String suffix = IS_WINDOWS ? ".cmd" : "";
            Path candidate = Path.of(dir, cmd + suffix);
            if (Files.isExecutable(candidate)) {
                log.info("Detected {} CLI at fallback path: {}", type.name(), candidate);
                return candidate.toString();
            }
        }
        log.info("{} CLI not found", type.name());
        return null;
    }

    /**
     * 清除自动检测缓存（用于测试或重新检测）。
     */
    public static void clearDetectionCache() {
        DETECTED_PATH_CACHE.clear();
    }
}
