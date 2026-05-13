package io.github.easyagent.ui.service.command;

import com.intellij.util.execution.ParametersListUtil;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.ui.enums.SlashCommandActionType;
import io.github.easyagent.ui.enums.SlashCommandSourceType;
import io.github.easyagent.ui.service.entity.SlashCommandExecutionPayload;
import io.github.easyagent.ui.service.entity.SlashCommandPayload;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 斜杠命令发现与执行服务。
 * <p>
 * 负责扫描 Claude、Codex、OpenCode 的内建命令、用户命令、技能命令和插件命令，
 * 并将命令执行拆分为可扩展的策略。
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Slf4j
public final class SlashCommandService {

    /** 用户主目录。 */
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /** Claude 用户目录。 */
    private static final Path CLAUDE_HOME = HOME.resolve(".claude");

    /** Codex 用户目录。 */
    private static final Path CODEX_HOME = HOME.resolve(".codex");

    /** Codex 技能目录。 */
    private static final Path AGENTS_HOME = HOME.resolve(".agents");

    /** OpenCode 用户目录。 */
    private static final Path OPENCODE_HOME = HOME.resolve(".config").resolve("opencode");

    /** 命令文件名。 */
    private static final String COMMANDS_DIR = "commands";

    /** 技能目录名。 */
    private static final String SKILLS_DIR = "skills";

    /** OpenCode 技能目录名。 */
    private static final String OPENCODE_SKILL_DIR = "skill";

    /** Markdown 文件后缀。 */
    private static final String MD_SUFFIX = ".md";

    /** OpenCode 配置文件名。 */
    private static final String OPENCODE_CONFIG = "opencode.json";

    /** 命令扫描最大深度。 */
    private static final int MAX_SCAN_DEPTH = 8;

    /** 已安装插件清单文件名。 */
    private static final String INSTALLED_PLUGINS_FILE = "installed_plugins.json";

    /** Shell 输出最大长度。 */
    private static final int MAX_SHELL_OUTPUT_LENGTH = 4096;

    /** Shell 输出执行超时时间（秒）。 */
    private static final long SHELL_TIMEOUT_SECONDS = 8L;

    /** 数字版本目录判断。 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)*");

    /** Shell 输出占位符。 */
    private static final Pattern SHELL_PATTERN = Pattern.compile("!`([^`]+)`");

    /** 单词命令前缀。 */
    private static final String SLASH = "/";

    /** 插件目录名。 */
    private static final String PLUGINS_DIR = "plugins";

    /** 代理目录名。 */
    private static final String AGENTS_DIR = ".agents";

    /** Prompt 目录名。 */
    private static final String PROMPTS_DIR = "prompts";

    private final List<SlashCommandStrategy> strategies;

    /**
     * 构造命令服务。
     */
    public SlashCommandService() {
        this.strategies = List.of(
                new OpenNewSessionStrategy(),
                new PromptTemplateStrategy()
        );
    }

    /**
     * 列出指定 CLI 支持的斜杠命令。
     *
     * @param cliType     CLI 类型
     * @param projectPath 当前项目路径
     * @return 命令列表
     */
    public List<SlashCommandPayload> listCommands(CLIType cliType, @Nullable String projectPath) {
        List<SlashCommandDefinition> definitions = this.discoverDefinitions(cliType, projectPath);
        return definitions.stream().map(this::toPayload).toList();
    }

    /**
     * 执行指定斜杠命令。
     *
     * @param cliType     CLI 类型
     * @param rawText     原始输入文本
     * @param projectPath 当前项目路径
     * @param requestId   请求 ID
     * @return 执行结果
     */
    public SlashCommandExecutionPayload executeCommand(CLIType cliType, String rawText,
                                                       @Nullable String projectPath, @Nullable String requestId) {
        ParsedSlashCommand parsed = this.parse(rawText);
        if (parsed == null) {
            return this.buildPassthroughPayload(cliType, rawText, requestId);
        }

        SlashCommandDefinition definition = this.resolveDefinition(cliType, parsed.name(), projectPath);
        if (definition == null) {
            return this.buildPassthroughPayload(cliType, rawText, requestId);
        }

        SlashCommandInvocation invocation = new SlashCommandInvocation(cliType, rawText, parsed.name(),
                parsed.arguments(), parsed.trailingText(), projectPath, requestId);
        SlashCommandStrategy strategy = this.findStrategy(definition);
        return strategy.execute(definition, invocation, this);
    }

    /**
     * 构建直接透传的执行载荷。
     *
     * @param cliType   CLI 类型
     * @param rawText   原始输入
     * @param requestId 请求 ID
     * @return 执行载荷
     */
    private SlashCommandExecutionPayload buildPassthroughPayload(CLIType cliType, String rawText,
                                                                  @Nullable String requestId) {
        return SlashCommandExecutionPayload.builder()
                .requestId(requestId)
                .cliType(cliType.name())
                .commandName("")
                .executionType(SlashCommandActionType.PASS_THROUGH.name())
                .prompt(rawText)
                .openFreshSession(false)
                .refreshHistory(false)
                .toastMessage(null)
                .build();
    }

    /**
     * 发现指定 CLI 的命令定义。
     *
     * @param cliType     CLI 类型
     * @param projectPath 当前项目路径
     * @return 命令定义列表
     */
    private List<SlashCommandDefinition> discoverDefinitions(CLIType cliType, @Nullable String projectPath) {
        List<SlashCommandDefinition> definitions = new ArrayList<>();
        if (cliType == null) {
            return definitions;
        }

        switch (cliType) {
            case CLAUDE -> this.discoverClaude(definitions, projectPath);
            case CODEX -> this.discoverCodex(definitions, projectPath);
            case OPENCODE -> this.discoverOpenCode(definitions, projectPath);
        }
        return this.deduplicate(definitions);
    }

    /**
     * Claude 命令发现。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     */
    private void discoverClaude(List<SlashCommandDefinition> definitions, @Nullable String projectPath) {
        this.scanAncestorScopes(definitions, projectPath, ".claude", CLIType.CLAUDE, true, true, true, false);
        this.addCoreDefinitions(definitions, CLIType.CLAUDE);
        this.addSkillDirectory(definitions, CLAUDE_HOME.resolve(SKILLS_DIR), CLIType.CLAUDE,
                SlashCommandSourceType.SKILL);
        this.addCommandDirectory(definitions, CLAUDE_HOME.resolve(COMMANDS_DIR), CLIType.CLAUDE,
                SlashCommandSourceType.COMMAND);
        this.addPluginDirectories(definitions, CLAUDE_HOME.resolve(PLUGINS_DIR), CLIType.CLAUDE);
    }

    /**
     * Codex 命令发现。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     */
    private void discoverCodex(List<SlashCommandDefinition> definitions, @Nullable String projectPath) {
        this.scanAncestorScopes(definitions, projectPath, ".codex", CLIType.CODEX, true, false, true, true);
        this.scanAncestorScopes(definitions, projectPath, AGENTS_DIR, CLIType.CODEX, false, true, false, false);
        this.addCoreDefinitions(definitions, CLIType.CODEX);
        this.addCommandDirectory(definitions, CODEX_HOME.resolve(PROMPTS_DIR), CLIType.CODEX,
                SlashCommandSourceType.CUSTOM);
        this.addCommandDirectory(definitions, CODEX_HOME.resolve(COMMANDS_DIR), CLIType.CODEX,
                SlashCommandSourceType.COMMAND);
        this.addSkillDirectory(definitions, AGENTS_HOME.resolve(SKILLS_DIR), CLIType.CODEX,
                SlashCommandSourceType.SKILL);
        this.addSkillDirectory(definitions, CODEX_HOME.resolve(SKILLS_DIR), CLIType.CODEX,
                SlashCommandSourceType.SKILL);
        this.addPluginDirectories(definitions, CODEX_HOME.resolve(PLUGINS_DIR), CLIType.CODEX);
    }

    /**
     * OpenCode 命令发现。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     */
    private void discoverOpenCode(List<SlashCommandDefinition> definitions, @Nullable String projectPath) {
        this.scanAncestorScopes(definitions, projectPath, ".opencode", CLIType.OPENCODE, true, true, false, false);
        this.addCoreDefinitions(definitions, CLIType.OPENCODE);
        this.addOpenCodeConfigCommands(definitions, OPENCODE_HOME.resolve(OPENCODE_CONFIG));
        this.addCommandDirectory(definitions, OPENCODE_HOME.resolve(COMMANDS_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.COMMAND);
        this.addSkillDirectory(definitions, OPENCODE_HOME.resolve(OPENCODE_SKILL_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.SKILL);
        this.addSkillDirectory(definitions, OPENCODE_HOME.resolve(SKILLS_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.SKILL);
        this.addPluginDirectories(definitions, OPENCODE_HOME.resolve(PLUGINS_DIR), CLIType.OPENCODE);
    }

    /**
     * 追加当前 CLI 的核心斜杠命令。
     *
     * @param definitions 命令定义集合
     * @param cliType     CLI 类型
     */
    private void addCoreDefinitions(List<SlashCommandDefinition> definitions, CLIType cliType) {
        this.addDefinition(definitions, this.definition(cliType, "plan", this.corePlanDescription(cliType),
                SlashCommandActionType.PASS_THROUGH, SlashCommandSourceType.BUILTIN, List.of(),
                null, false, true));
        this.addDefinition(definitions, this.definition(cliType, "new", this.coreNewDescription(cliType),
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of("clear", "reset"),
                null, true, false));
        this.addDefinition(definitions, this.definition(cliType, "compact", this.coreCompactDescription(cliType),
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(),
                this.coreCompactPrompt(), false, true));
        this.addDefinition(definitions, this.definition(cliType, "init", this.coreInitDescription(cliType),
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(),
                this.coreInitPrompt(cliType), false, false));
        this.addDefinition(definitions, this.definition(cliType, "plugins", this.corePluginsDescription(cliType),
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of("plugin"),
                this.corePluginsPrompt(cliType), false, false));
    }

    /**
     * 获取计划模式命令描述。
     *
     * @param cliType CLI 类型
     * @return 描述文本
     */
    private String corePlanDescription(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Enter plan mode to analyze before making changes";
            case CODEX -> "Enter plan mode";
            case OPENCODE -> "Enter plan mode";
        };
    }

    /**
     * 获取新会话命令描述。
     *
     * @param cliType CLI 类型
     * @return 描述文本
     */
    private String coreNewDescription(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Start a new conversation with empty context";
            case CODEX -> "Start a fresh conversation";
            case OPENCODE -> "Start a new session";
        };
    }

    /**
     * 获取压缩命令描述。
     *
     * @param cliType CLI 类型
     * @return 描述文本
     */
    private String coreCompactDescription(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Summarize the conversation and free context";
            case CODEX -> "Compact the current conversation";
            case OPENCODE -> "Compact the current conversation";
        };
    }

    /**
     * 获取初始化命令描述。
     *
     * @param cliType CLI 类型
     * @return 描述文本
     */
    private String coreInitDescription(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Initialize the project with a CLAUDE.md guide";
            case CODEX -> "Initialize the current project";
            case OPENCODE -> "Initialize the current project";
        };
    }

    /**
     * 获取插件命令描述。
     *
     * @param cliType CLI 类型
     * @return 描述文本
     */
    private String corePluginsDescription(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Manage Claude Code plugins";
            case CODEX -> "Manage Codex plugins";
            case OPENCODE -> "Manage OpenCode plugins";
        };
    }

    /**
     * 获取压缩命令的基础提示词。
     *
     * @return 提示词
     */
    private String coreCompactPrompt() {
        return "Summarize the conversation so far and keep the important details.";
    }

    /**
     * 获取初始化命令的基础提示词。
     *
     * @param cliType CLI 类型
     * @return 提示词
     */
    private String coreInitPrompt(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Initialize the project instructions file for this repository.";
            case CODEX -> "Initialize the project instructions for this repository.";
            case OPENCODE -> "Initialize the current project instructions for this repository.";
        };
    }

    /**
     * 获取插件命令的基础提示词。
     *
     * @param cliType CLI 类型
     * @return 提示词
     */
    private String corePluginsPrompt(CLIType cliType) {
        return switch (cliType) {
            case CLAUDE -> "Open the plugin manager and show installed Claude Code plugins.";
            case CODEX -> "Open the plugin manager and show installed Codex plugins.";
            case OPENCODE -> "Open the plugin manager and show installed OpenCode plugins.";
        };
    }

    /**
     * 读取 OpenCode JSON 配置中的 command 定义。
     *
     * @param definitions 命令定义集合
     * @param configPath  配置文件路径
     */
    private void addOpenCodeConfigCommands(List<SlashCommandDefinition> definitions, Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            return;
        }
        try {
            OpenCodeConfig config = io.github.easyagent.util.GsonUtils.fromJson(Files.readString(configPath), OpenCodeConfig.class);
            if (config == null || config.command == null || config.command.isEmpty()) {
                return;
            }
            for (Map.Entry<String, OpenCodeCommandConfig> entry : config.command.entrySet()) {
                OpenCodeCommandConfig command = entry.getValue();
                if (command == null) {
                    continue;
                }
                definitions.add(new SlashCommandDefinition(
                        CLIType.OPENCODE,
                        entry.getKey(),
                        List.of(),
                        command.description,
                        command.template,
                        SlashCommandSourceType.COMMAND,
                        SlashCommandActionType.SEND_PROMPT,
                        this.normalizeGroup(configPath),
                        configPath.toString(),
                        false,
                        false
                ));
            }
        } catch (Exception e) {
            log.debug("Failed to read OpenCode commands from {}", configPath, e);
        }
    }

    /**
     * 追加命令目录中的 markdown 命令。
     *
     * @param definitions   命令定义集合
     * @param root          根目录
     * @param cliType       CLI 类型
     * @param sourceType    来源类型
     */
    private void addCommandDirectory(List<SlashCommandDefinition> definitions, Path root,
                                     CLIType cliType, SlashCommandSourceType sourceType) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(MD_SUFFIX) && !"README.md".equalsIgnoreCase(fileName);
                    })
                    .forEach(path -> this.addMarkdownCommand(definitions, path, cliType, sourceType, false));
        } catch (IOException e) {
            log.debug("Failed to scan command directory: {}", root, e);
        }
    }

    /**
     * 追加技能目录中的 SKILL.md。
     *
     * @param definitions 命令定义集合
     * @param root       根目录
     * @param cliType    CLI 类型
     * @param sourceType 来源类型
     */
    private void addSkillDirectory(List<SlashCommandDefinition> definitions, Path root,
                                   CLIType cliType, SlashCommandSourceType sourceType) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(path -> this.addMarkdownCommand(definitions, path, cliType, sourceType, true));
        } catch (IOException e) {
            log.debug("Failed to scan skill directory: {}", root, e);
        }
    }

    /**
     * 扫描插件目录中的命令与技能。
     * <p>
     * 优先读取 {@code installed_plugins.json} 清单，仅加载已安装的插件；
     * 清单不存在时退化为扫描直接子目录（如 Codex 插件结构）。
     * </p>
     *
     * @param definitions 命令定义集合
     * @param root       插件根目录
     * @param cliType    CLI 类型
     */
    private void addPluginDirectories(List<SlashCommandDefinition> definitions, Path root, CLIType cliType) {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> installPaths = this.resolveInstalledPluginPaths(root);
        if (!installPaths.isEmpty()) {
            for (Path installPath : installPaths) {
                if (!Files.isDirectory(installPath)) {
                    continue;
                }
                this.scanSinglePluginDir(definitions, installPath, cliType);
            }
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> this.scanSinglePluginDir(definitions, dir, cliType));
        } catch (IOException e) {
            log.debug("Failed to list plugin directories: {}", root, e);
        }
    }

    /**
     * 从 {@code installed_plugins.json} 解析已安装插件的路径列表。
     *
     * @param pluginsRoot 插件根目录（如 {@code ~/.claude/plugins}）
     * @return 已安装插件的路径列表，无清单时返回空列表
     */
    private List<Path> resolveInstalledPluginPaths(Path pluginsRoot) {
        Path manifest = pluginsRoot.resolve(INSTALLED_PLUGINS_FILE);
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            InstalledPluginsDescriptor descriptor = io.github.easyagent.util.GsonUtils.fromJson(
                    json, InstalledPluginsDescriptor.class);
            if (descriptor == null || descriptor.plugins == null || descriptor.plugins.isEmpty()) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>();
            for (List<InstalledPluginEntry> entries : descriptor.plugins.values()) {
                if (entries == null) {
                    continue;
                }
                for (InstalledPluginEntry entry : entries) {
                    if (entry.installPath != null && !entry.installPath.isBlank()) {
                        paths.add(Path.of(entry.installPath));
                    }
                }
            }
            return paths;
        } catch (Exception e) {
            log.debug("Failed to read installed plugins manifest: {}", manifest, e);
            return List.of();
        }
    }

    /**
     * 扫描单个插件目录中的命令和技能文件。
     *
     * @param definitions 命令定义集合
     * @param pluginDir   插件安装目录
     * @param cliType     CLI 类型
     */
    private void scanSinglePluginDir(List<SlashCommandDefinition> definitions, Path pluginDir, CLIType cliType) {
        try (Stream<Path> stream = Files.walk(pluginDir, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (fileName.endsWith(MD_SUFFIX) && !"README.md".equalsIgnoreCase(fileName)
                                && this.hasAncestor(path, COMMANDS_DIR)) {
                            this.addMarkdownCommand(definitions, path, cliType, SlashCommandSourceType.PLUGIN, false);
                        } else if ("SKILL.md".equalsIgnoreCase(fileName)) {
                            this.addMarkdownCommand(definitions, path, cliType, SlashCommandSourceType.SKILL, true);
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan plugin directory: {}", pluginDir, e);
        }
    }

    /**
     * 解析 markdown 命令文件。
     *
     * @param definitions 命令定义集合
     * @param path        文件路径
     * @param cliType     CLI 类型
     * @param sourceType  来源类型
     * @param skill       是否技能文件
     */
    private void addMarkdownCommand(List<SlashCommandDefinition> definitions, Path path, CLIType cliType,
                                    SlashCommandSourceType sourceType, boolean skill) {
        try {
            MarkdownCommand markdownCommand = this.parseMarkdown(path, skill);
            String name = skill ? this.skillCommandName(path) : this.fileCommandName(path);
            if (name == null || name.isBlank()) {
                return;
            }
            definitions.add(new SlashCommandDefinition(
                    cliType,
                    name,
                    List.of(),
                    markdownCommand.description(),
                    markdownCommand.template(),
                    sourceType,
                    SlashCommandActionType.SEND_PROMPT,
                    this.normalizeGroup(path),
                    path.toString(),
                    false,
                    false
            ));
        } catch (Exception e) {
            log.debug("Failed to parse markdown command: {}", path, e);
        }
    }

    /**
     * 解析 markdown 文件中的前置声明和正文。
     *
     * @param path  文件路径
     * @param skill 是否技能文件
     * @return 解析结果
     * @throws IOException IO 异常
     */
    private MarkdownCommand parseMarkdown(Path path, boolean skill) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String description = null;
        String body = text;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                String frontMatter = text.substring(3, end).trim();
                body = text.substring(end + 4).trim();
                description = this.extractFrontMatterValue(frontMatter, "description");
            }
        }
        if (description == null || description.isBlank()) {
            description = skill ? "Skill command" : "Custom command";
        }
        return new MarkdownCommand(description, body);
    }

    /**
     * 解析 frontmatter 中的指定字段。
     *
     * @param frontMatter frontmatter 文本
     * @param key         字段名
     * @return 字段值
     */
    private String extractFrontMatterValue(String frontMatter, String key) {
        String[] lines = frontMatter.split("\\R");
        StringBuilder value = new StringBuilder();
        boolean reading = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String raw = trimmed.substring(key.length() + 1).trim();
                if (!raw.isEmpty() && !">".equals(raw) && !"|".equals(raw)) {
                    return raw;
                }
                reading = true;
                continue;
            }
            if (reading) {
                if (trimmed.matches("^[A-Za-z0-9_-]+:\\s*.*$")) {
                    break;
                }
                if (!trimmed.isEmpty()) {
                    if (value.length() > 0) {
                        value.append(' ');
                    }
                    value.append(trimmed);
                }
            }
        }
        return value.length() == 0 ? null : value.toString();
    }

    /**
     * 生成命令的展示模型。
     *
     * @param definition 命令定义
     * @return 前端载荷
     */
    private SlashCommandPayload toPayload(SlashCommandDefinition definition) {
        return SlashCommandPayload.builder()
                .cliType(definition.cliType().name())
                .name(definition.name())
                .commandText(SLASH + definition.name())
                .description(definition.description())
                .sourceType(definition.sourceType().name())
                .aliases(definition.aliases())
                .group(definition.group())
                .actionType(definition.actionType().name())
                .build();
    }

    /**
     * 查找命令定义。
     *
     * @param cliType     CLI 类型
     * @param commandName  命令名
     * @param projectPath  项目路径
     * @return 命令定义
     */
    private SlashCommandDefinition resolveDefinition(CLIType cliType, String commandName, @Nullable String projectPath) {
        if (commandName == null || commandName.isBlank()) {
            return null;
        }
        String normalized = commandName.toLowerCase(Locale.ROOT);
        for (SlashCommandDefinition definition : this.discoverDefinitions(cliType, projectPath)) {
            if (normalized.equals(definition.name().toLowerCase(Locale.ROOT))) {
                return definition;
            }
            for (String alias : definition.aliases()) {
                if (normalized.equals(alias.toLowerCase(Locale.ROOT))) {
                    return definition;
                }
            }
        }
        return null;
    }

    /**
     * 解析输入命令。
     *
     * @param rawText 原始文本
     * @return 解析结果
     */
    private ParsedSlashCommand parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String line = rawText.stripLeading();
        if (!line.startsWith(SLASH)) {
            return null;
        }
        int endOfName = 1;
        while (endOfName < line.length() && !Character.isWhitespace(line.charAt(endOfName))) {
            endOfName++;
        }
        String name = line.substring(1, endOfName);
        String remainder = endOfName < line.length() ? line.substring(endOfName).stripLeading() : "";
        String argText = remainder.isEmpty() ? "" : remainder.split("\\R", 2)[0];
        List<String> arguments = this.parseArguments(argText);
        return new ParsedSlashCommand(name, arguments, remainder);
    }

    /**
     * 解析命令参数。
     *
     * @param argText 参数文本
     * @return 参数列表
     */
    private List<String> parseArguments(String argText) {
        if (argText == null || argText.isBlank()) {
            return List.of();
        }
        try {
            return ParametersListUtil.parse(argText);
        } catch (Exception e) {
            return List.of(argText.trim());
        }
    }

    /**
     * 构造命令定义。
     *
     * @param cliType    CLI 类型
     * @param name       命令名
     * @param description 描述
     * @param actionType 执行类型
     * @param sourceType 来源类型
     * @param aliases    别名列表
     * @param template   额外模板
     * @return 命令定义
     */
    private SlashCommandDefinition definition(CLIType cliType, String name, String description,
                                              SlashCommandActionType actionType, SlashCommandSourceType sourceType,
                                              List<String> aliases, @Nullable String template) {
        return this.definition(cliType, name, description, actionType, sourceType, aliases, template, false, false);
    }

    /**
     * 构造命令定义。
     *
     * @param cliType           CLI 类型
     * @param name              命令名
     * @param description       描述
     * @param actionType        执行类型
     * @param sourceType        来源类型
     * @param aliases           别名列表
     * @param template          额外模板
     * @param openFreshSession   是否需要打开新会话
     * @param refreshHistory     是否需要刷新历史
     * @return 命令定义
     */
    private SlashCommandDefinition definition(CLIType cliType, String name, String description,
                                              SlashCommandActionType actionType, SlashCommandSourceType sourceType,
                                              List<String> aliases, @Nullable String template,
                                              boolean openFreshSession, boolean refreshHistory) {
        return new SlashCommandDefinition(cliType, name, aliases, description, template, sourceType,
                actionType, null, null, openFreshSession, refreshHistory);
    }

    /**
     * 追加定义并保持去重前的顺序。
     *
     * @param definitions 命令定义集合
     * @param definition  命令定义
     */
    private void addDefinition(List<SlashCommandDefinition> definitions, SlashCommandDefinition definition) {
        if (definition != null) {
            definitions.add(definition);
        }
    }

    /**
     * 去重命令定义。
     *
     * @param definitions 原始定义
     * @return 去重后的定义
     */
    private List<SlashCommandDefinition> deduplicate(List<SlashCommandDefinition> definitions) {
        Map<String, SlashCommandDefinition> map = new LinkedHashMap<>();
        for (SlashCommandDefinition definition : definitions) {
            String key = this.definitionKey(definition);
            map.putIfAbsent(key, definition);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 生成命令定义的去重键。
     *
     * @param definition 命令定义
     * @return 去重键
     */
    private String definitionKey(SlashCommandDefinition definition) {
        String origin = definition.originPath();
        if (origin != null && !origin.isBlank()) {
            return definition.cliType().name() + "|" + definition.sourceType().name() + "|" + origin;
        }
        return definition.cliType().name() + "|" + definition.sourceType().name() + "|" + definition.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 选择执行策略。
     *
     * @param definition 命令定义
     * @return 执行策略
     */
    private SlashCommandStrategy findStrategy(SlashCommandDefinition definition) {
        for (SlashCommandStrategy strategy : this.strategies) {
            if (strategy.supports(definition)) {
                return strategy;
            }
        }
        return new PromptTemplateStrategy();
    }

    /**
     * 规范化分组名称。
     *
     * @param path 路径
     * @return 分组名称
     */
    private String normalizeGroup(Path path) {
        if (path == null) {
            return null;
        }
        Path marker = null;
        for (Path current = path.getParent(); current != null; current = current.getParent()) {
            String name = current.getFileName() != null ? current.getFileName().toString() : null;
            if (COMMANDS_DIR.equalsIgnoreCase(name) || SKILLS_DIR.equalsIgnoreCase(name) || OPENCODE_SKILL_DIR.equalsIgnoreCase(name)) {
                marker = current;
                break;
            }
        }
        if (marker == null || marker.getParent() == null) {
            return null;
        }
        Path group = marker.getParent();
        while (group != null && group.getFileName() != null
                && this.isScopeDirectory(group.getFileName().toString())) {
            group = group.getParent();
        }
        if (group == null || HOME.equals(group)) {
            return null;
        }
        if (group.getParent() != null && group.getFileName() != null
                && VERSION_PATTERN.matcher(group.getFileName().toString()).matches()) {
            group = group.getParent();
        }
        return group.getFileName() != null ? group.getFileName().toString() : null;
    }

    /**
     * 提取命令文件名。
     *
     * @param path 文件路径
     * @return 命令名
     */
    private String fileCommandName(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString() : null;
        if (fileName == null || !fileName.endsWith(MD_SUFFIX)) {
            return null;
        }
        return fileName.substring(0, fileName.length() - MD_SUFFIX.length());
    }

    /**
     * 提取技能命令名。
     *
     * @param path 文件路径
     * @return 命令名
     */
    private String skillCommandName(Path path) {
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) {
            return null;
        }
        String leaf = parent.getFileName().toString();
        String namespace = this.pluginNamespace(path);
        if (namespace == null || namespace.isBlank()) {
            return leaf;
        }
        return namespace + ":" + leaf;
    }

    /**
     * 解析插件命名空间。
     *
     * @param path 文件路径
     * @return 插件命名空间，无法解析时返回 {@code null}
     */
    private String pluginNamespace(Path path) {
        if (path == null) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path fileName = current.getFileName();
            if (fileName != null) {
                segments.add(0, fileName.toString());
            }
        }
        int pluginsIndex = this.lastIndexOf(segments, PLUGINS_DIR);
        if (pluginsIndex >= 0) {
            for (int i = pluginsIndex + 1; i < segments.size() - 1; i++) {
                String segment = segments.get(i);
                if (!this.isStructuralSegment(segment)) {
                    return segment;
                }
            }
        }
        int marketplacesIndex = this.lastIndexOf(segments, "marketplaces");
        if (marketplacesIndex >= 0 && marketplacesIndex + 1 < segments.size() - 1) {
            return segments.get(marketplacesIndex + 1);
        }
        return null;
    }

    /**
     * 查找列表中某个值的最后一次出现位置。
     *
     * @param values 值列表
     * @param value  目标值
     * @return 最后一次出现的索引
     */
    private int lastIndexOf(List<String> values, String value) {
        if (values == null || values.isEmpty() || value == null) {
            return -1;
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (value.equalsIgnoreCase(values.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断路径段是否属于结构性目录。
     *
     * @param segment 路径段
     * @return 是否为结构性目录
     */
    private boolean isStructuralSegment(String segment) {
        if (segment == null) {
            return false;
        }
        return PLUGINS_DIR.equalsIgnoreCase(segment)
                || "marketplaces".equalsIgnoreCase(segment)
                || COMMANDS_DIR.equalsIgnoreCase(segment)
                || SKILLS_DIR.equalsIgnoreCase(segment)
                || OPENCODE_SKILL_DIR.equalsIgnoreCase(segment)
                || AGENTS_DIR.equalsIgnoreCase(segment)
                || PROMPTS_DIR.equalsIgnoreCase(segment)
                || "examples".equalsIgnoreCase(segment)
                || "external_plugins".equalsIgnoreCase(segment);
    }

    /**
     * 判断路径是否为作用域目录。
     *
     * @param segment 路径段
     * @return 是否为作用域目录
     */
    private boolean isScopeDirectory(String segment) {
        if (segment == null) {
            return false;
        }
        return segment.startsWith(".")
                || AGENTS_DIR.equalsIgnoreCase(segment)
                || "marketplaces".equalsIgnoreCase(segment);
    }

    /**
     * 判断路径是否包含指定祖先目录名。
     *
     * @param path     文件路径
     * @param ancestor 祖先目录名
     * @return 是否包含
     */
    private boolean hasAncestor(Path path, String ancestor) {
        if (path == null || ancestor == null) {
            return false;
        }
        for (Path current = path.getParent(); current != null; current = current.getParent()) {
            Path fileName = current.getFileName();
            if (fileName != null && ancestor.equalsIgnoreCase(fileName.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描项目路径下的祖先作用域目录。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     * @param scopeDir    作用域目录名
     * @param cliType     CLI 类型
     * @param commands    是否扫描 commands
     * @param skills      是否扫描 skills
     * @param plugins     是否扫描 plugins
     */
    private void scanAncestorScopes(List<SlashCommandDefinition> definitions, @Nullable String projectPath,
                                    String scopeDir, CLIType cliType, boolean commands, boolean skills,
                                    boolean plugins, boolean prompts) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        Path start = Path.of(projectPath).toAbsolutePath().normalize();
        for (Path current = start; current != null; current = current.getParent()) {
            Path scopeRoot = current.resolve(scopeDir);
            if (commands) {
                this.addCommandDirectory(definitions, scopeRoot.resolve(COMMANDS_DIR), cliType,
                        SlashCommandSourceType.COMMAND);
                if (prompts) {
                    this.addCommandDirectory(definitions, scopeRoot.resolve(PROMPTS_DIR), cliType,
                            SlashCommandSourceType.CUSTOM);
                }
            }
            if (skills) {
                this.addSkillDirectory(definitions, scopeRoot.resolve(SKILLS_DIR), cliType,
                        SlashCommandSourceType.SKILL);
                this.addSkillDirectory(definitions, scopeRoot.resolve(OPENCODE_SKILL_DIR), cliType,
                        SlashCommandSourceType.SKILL);
            }
            if (plugins) {
                this.addPluginDirectories(definitions, scopeRoot.resolve(PLUGINS_DIR), cliType);
            }
        }
    }
    /**
     * 定义命令模板信息。
     *
     * @param description 描述
     * @param template    模板正文
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record MarkdownCommand(String description, String template) {
    }

    /**
     * 已安装插件清单描述（对应 {@code installed_plugins.json}）。
     *
     * @param plugins 插件映射，key 为 {@code name@marketplace}，value 为安装条目列表
     * @author haijun
     * @date 2026/5/11
     * @since 1.0.0
     */
    private record InstalledPluginsDescriptor(Map<String, List<InstalledPluginEntry>> plugins) {
    }

    /**
     * 单个已安装插件条目。
     *
     * @param installPath 安装路径
     * @author haijun
     * @date 2026/5/11
     * @since 1.0.0
     */
    private record InstalledPluginEntry(String installPath) {
    }

    /**
     * 斜杠命令解析结果。
     *
     * @param name      命令名
     * @param arguments 参数列表
     * @param trailingText 命令后缀提示词
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record ParsedSlashCommand(String name, List<String> arguments, String trailingText) {
    }

    /**
     * 斜杠命令执行入参。
     *
     * @param cliType     CLI 类型
     * @param rawText     原始文本
     * @param commandName 命令名
     * @param arguments   参数列表
     * @param trailingText 命令后缀提示词
     * @param projectPath 项目路径
     * @param requestId   请求 ID
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record SlashCommandInvocation(CLIType cliType, String rawText, String commandName,
                                          List<String> arguments, String trailingText,
                                          String projectPath, String requestId) {
    }

    /**
     * 斜杠命令定义。
     *
     * @param cliType     CLI 类型
     * @param name        命令名
     * @param aliases     别名列表
     * @param description 描述
     * @param template    执行模板
     * @param sourceType  来源类型
     * @param actionType  执行类型
     * @param group       分组
     * @param originPath  来源路径
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record SlashCommandDefinition(CLIType cliType, String name, List<String> aliases,
                                          String description, String template, SlashCommandSourceType sourceType,
                                          SlashCommandActionType actionType, String group, String originPath,
                                          boolean openFreshSession, boolean refreshHistory) {
    }

    /**
     * 命令执行策略。
     */
    private interface SlashCommandStrategy {

        /**
         * 判断是否支持该命令。
         *
         * @param definition 命令定义
         * @return 是否支持
         */
        boolean supports(SlashCommandDefinition definition);

        /**
         * 执行命令。
         *
         * @param definition 命令定义
         * @param invocation 执行入参
         * @param service    命令服务
         * @return 执行结果
         */
        SlashCommandExecutionPayload execute(SlashCommandDefinition definition, SlashCommandInvocation invocation,
                                             SlashCommandService service);
    }

    /**
     * 新会话命令策略。
     */
    private static final class OpenNewSessionStrategy implements SlashCommandStrategy {

        @Override
        public boolean supports(SlashCommandDefinition definition) {
            return definition.actionType() == SlashCommandActionType.OPEN_NEW_SESSION;
        }

        @Override
        public SlashCommandExecutionPayload execute(SlashCommandDefinition definition,
                                                    SlashCommandInvocation invocation,
                                                    SlashCommandService service) {
            String prompt = invocation.trailingText();
            return SlashCommandExecutionPayload.builder()
                    .requestId(invocation.requestId())
                    .cliType(invocation.cliType().name())
                    .commandName(definition.name())
                    .executionType(SlashCommandActionType.OPEN_NEW_SESSION.name())
                    .prompt(prompt == null || prompt.isBlank() ? null : prompt)
                    .openFreshSession(definition.openFreshSession() || definition.actionType() == SlashCommandActionType.OPEN_NEW_SESSION)
                    .refreshHistory(definition.refreshHistory())
                    .toastMessage(null)
                    .build();
        }
    }

    /**
     * 提示词命令策略。
     */
    private static final class PromptTemplateStrategy implements SlashCommandStrategy {

        @Override
        public boolean supports(SlashCommandDefinition definition) {
            return definition.actionType() == SlashCommandActionType.SEND_PROMPT
                    || definition.actionType() == SlashCommandActionType.PASS_THROUGH;
        }

        @Override
        public SlashCommandExecutionPayload execute(SlashCommandDefinition definition,
                                                    SlashCommandInvocation invocation,
                                                    SlashCommandService service) {
            String prompt;
            if (definition.actionType() == SlashCommandActionType.PASS_THROUGH) {
                prompt = invocation.rawText();
            } else {
                prompt = service.composePrompt(definition, invocation);
                if (prompt == null || prompt.isBlank()) {
                    prompt = invocation.rawText();
                }
            }
            return SlashCommandExecutionPayload.builder()
                    .requestId(invocation.requestId())
                    .cliType(invocation.cliType().name())
                    .commandName(definition.name())
                    .executionType(definition.actionType().name())
                    .prompt(prompt)
                    .openFreshSession(definition.openFreshSession())
                    .refreshHistory(definition.refreshHistory())
                    .toastMessage(null)
                    .build();
        }
    }

    /**
     * 展开命令模板。
     *
     * @param definition 命令定义
     * @param invocation 执行入参
     * @return 展开后的提示词
     */
    private String expandTemplate(SlashCommandDefinition definition, SlashCommandInvocation invocation) {
        String template = definition.template();
        if (template == null || template.isBlank()) {
            return null;
        }
        String expanded = template;
        String arguments = String.join(" ", invocation.arguments());
        expanded = expanded.replace("$ARGUMENTS", arguments);
        for (int i = 0; i < invocation.arguments().size(); i++) {
            expanded = expanded.replace("$" + (i + 1), invocation.arguments().get(i));
        }
        expanded = this.expandShellBlocks(expanded, invocation.projectPath());
        return expanded.trim();
    }

    /**
     * 组合命令基础提示词和用户额外提示词。
     *
     * @param definition 命令定义
     * @param invocation 执行入参
     * @return 合并后的提示词
     */
    private String composePrompt(SlashCommandDefinition definition, SlashCommandInvocation invocation) {
        String basePrompt = this.expandTemplate(definition, invocation);
        String userPrompt = invocation.trailingText();
        if (userPrompt == null || userPrompt.isBlank()) {
            return basePrompt;
        }
        if (basePrompt == null || basePrompt.isBlank()) {
            return userPrompt.trim();
        }
        if (definition.sourceType() != SlashCommandSourceType.BUILTIN
                && this.usesTemplateArguments(definition.template())) {
            return basePrompt;
        }
        return basePrompt.trim() + "\n\n" + userPrompt.trim();
    }

    /**
     * 判断模板是否已经消费了用户参数。
     *
     * @param template 模板文本
     * @return 是否包含参数占位符
     */
    private boolean usesTemplateArguments(@Nullable String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        return template.contains("$ARGUMENTS") || template.matches(".*\\$\\d+.*");
    }

    /**
     * 展开模板中的 shell 输出块。
     *
     * @param text        模板文本
     * @param projectPath 项目路径
     * @return 展开后的文本
     */
    private String expandShellBlocks(String text, @Nullable String projectPath) {
        Matcher matcher = SHELL_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String command = matcher.group(1);
            String output = this.runShellCommand(command, projectPath);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(output));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 运行 shell 命令并返回输出。
     *
     * @param command     shell 命令
     * @param projectPath 项目路径
     * @return 命令输出
     */
    private String runShellCommand(String command, @Nullable String projectPath) {
        if (command == null || command.isBlank()) {
            return "";
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-lc", command);
            if (projectPath != null && !projectPath.isBlank()) {
                builder.directory(Path.of(projectPath).toFile());
            }
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "[shell timeout]";
            }
            byte[] bytes = process.getInputStream().readAllBytes();
            String output = new String(bytes, StandardCharsets.UTF_8).trim();
            if (output.length() > MAX_SHELL_OUTPUT_LENGTH) {
                return output.substring(0, MAX_SHELL_OUTPUT_LENGTH) + "...";
            }
            return output;
        } catch (Exception e) {
            log.debug("Failed to run shell command: {}", command, e);
            return "[shell failed]";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * OpenCode 配置文件结构。
     *
     * @param command 命令映射
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record OpenCodeConfig(Map<String, OpenCodeCommandConfig> command) {
    }

    /**
     * OpenCode 命令配置结构。
     *
     * @param template   模板
     * @param description 描述
     * @param agent      代理
     * @param model      模型
     * @param subtask    是否子任务
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record OpenCodeCommandConfig(String template, String description, String agent,
                                         String model, Boolean subtask) {
    }
}
