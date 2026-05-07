package io.github.easyagent.ui.service.command;

import com.google.gson.reflect.TypeToken;
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

    /** OpenCode 命令字段名。 */
    private static final String FIELD_COMMAND = "command";

    /** OpenCode 配置模板字段名。 */
    private static final String FIELD_TEMPLATE = "template";

    /** OpenCode 配置描述字段名。 */
    private static final String FIELD_DESCRIPTION = "description";

    /** OpenCode 配置模型字段名。 */
    private static final String FIELD_MODEL = "model";

    /** OpenCode 配置代理字段名。 */
    private static final String FIELD_AGENT = "agent";

    /** OpenCode 配置子任务字段名。 */
    private static final String FIELD_SUBTASK = "subtask";

    /** 命令扫描最大深度。 */
    private static final int MAX_SCAN_DEPTH = 8;

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

    private final List<SlashCommandStrategy> strategies;

    /**
     * 构造命令服务。
     */
    public SlashCommandService() {
        this.strategies = List.of(
                new OpenNewSessionStrategy(),
                new PromptTemplateStrategy(),
                new InfoOnlyStrategy()
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
                parsed.arguments(), projectPath, requestId);
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
        this.addClaudeBuiltins(definitions);
        this.addSkillDirectory(definitions, CLAUDE_HOME.resolve(SKILLS_DIR), CLIType.CLAUDE,
                SlashCommandSourceType.SKILL);
        this.addCommandDirectory(definitions, CLAUDE_HOME.resolve(COMMANDS_DIR), CLIType.CLAUDE,
                SlashCommandSourceType.COMMAND);
        if (projectPath != null && !projectPath.isBlank()) {
            Path projectRoot = Path.of(projectPath);
            this.addSkillDirectory(definitions, projectRoot.resolve(".claude").resolve(SKILLS_DIR), CLIType.CLAUDE,
                    SlashCommandSourceType.SKILL);
            this.addCommandDirectory(definitions, projectRoot.resolve(".claude").resolve(COMMANDS_DIR),
                    CLIType.CLAUDE, SlashCommandSourceType.COMMAND);
        }
        this.addPluginDirectories(definitions, CLAUDE_HOME.resolve("plugins"), CLIType.CLAUDE);
    }

    /**
     * Codex 命令发现。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     */
    private void discoverCodex(List<SlashCommandDefinition> definitions, @Nullable String projectPath) {
        this.addCodexBuiltins(definitions);
        this.addCommandDirectory(definitions, CODEX_HOME.resolve("prompts"), CLIType.CODEX,
                SlashCommandSourceType.COMMAND);
        this.addCommandDirectory(definitions, CODEX_HOME.resolve(COMMANDS_DIR), CLIType.CODEX,
                SlashCommandSourceType.COMMAND);
        this.addSkillDirectory(definitions, CODEX_HOME.resolve(SKILLS_DIR), CLIType.CODEX,
                SlashCommandSourceType.SKILL);
        this.addPluginDirectories(definitions, CODEX_HOME.resolve("plugins"), CLIType.CODEX);
        if (projectPath != null && !projectPath.isBlank()) {
            Path projectRoot = Path.of(projectPath);
            this.addCommandDirectory(definitions, projectRoot.resolve(".codex").resolve("prompts"), CLIType.CODEX,
                    SlashCommandSourceType.COMMAND);
            this.addCommandDirectory(definitions, projectRoot.resolve(".codex").resolve(COMMANDS_DIR), CLIType.CODEX,
                    SlashCommandSourceType.COMMAND);
            this.addSkillDirectory(definitions, projectRoot.resolve(".codex").resolve(SKILLS_DIR), CLIType.CODEX,
                    SlashCommandSourceType.SKILL);
        }
    }

    /**
     * OpenCode 命令发现。
     *
     * @param definitions 命令定义集合
     * @param projectPath 项目路径
     */
    private void discoverOpenCode(List<SlashCommandDefinition> definitions, @Nullable String projectPath) {
        this.addOpenCodeBuiltins(definitions);
        this.addOpenCodeConfigCommands(definitions, OPENCODE_HOME.resolve(OPENCODE_CONFIG));
        this.addCommandDirectory(definitions, OPENCODE_HOME.resolve(COMMANDS_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.COMMAND);
        this.addSkillDirectory(definitions, OPENCODE_HOME.resolve(OPENCODE_SKILL_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.SKILL);
        this.addSkillDirectory(definitions, OPENCODE_HOME.resolve(SKILLS_DIR), CLIType.OPENCODE,
                SlashCommandSourceType.SKILL);
        if (projectPath != null && !projectPath.isBlank()) {
            Path projectRoot = Path.of(projectPath);
            this.addCommandDirectory(definitions, projectRoot.resolve(".opencode").resolve(COMMANDS_DIR),
                    CLIType.OPENCODE, SlashCommandSourceType.COMMAND);
            this.addSkillDirectory(definitions, projectRoot.resolve(".opencode").resolve(OPENCODE_SKILL_DIR),
                    CLIType.OPENCODE, SlashCommandSourceType.SKILL);
        }
    }

    /**
     * 追加 Claude 内建命令。
     *
     * @param definitions 命令定义集合
     */
    private void addClaudeBuiltins(List<SlashCommandDefinition> definitions) {
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "clear", "Start a new conversation with empty context",
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of("reset", "new"), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "compact", "Summarize the conversation and free context",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Summarize the conversation so far and keep the important details."));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "help", "Show help and available commands",
                SlashCommandActionType.INFO_ONLY, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "mcp", "Manage MCP server connections and OAuth",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "model", "Select or change the AI model",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "permissions", "Manage tool permissions",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of("allowed-tools"), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "review", "Review the working tree or code changes",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Review the current working tree and point out bugs, risks, and missing tests."));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "init", "Initialize project with a CLAUDE.md guide",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Initialize the project instructions file for this repository."));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "plugin", "Manage Claude Code plugins",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of("plugins"), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "debug", "Enable debug logging for the session",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.SKILL, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "claude-api", "Load Claude API reference material",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.SKILL, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "simplify", "Bundled skill for simplifying work",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.SKILL, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "batch", "Bundled skill for batching work",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.SKILL, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CLAUDE, "loop", "Bundled skill for looping tasks",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.SKILL, List.of("proactive"), null));
    }

    /**
     * 追加 Codex 内建命令。
     *
     * @param definitions 命令定义集合
     */
    private void addCodexBuiltins(List<SlashCommandDefinition> definitions) {
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "clear", "Clear the terminal and start a fresh chat",
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "compact", "Summarize the conversation and free context",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Summarize the conversation so far and keep the important details."));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "new", "Start a fresh conversation in the same CLI session",
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "review", "Ask Codex to review your working tree",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Review the current working tree and point out bugs, risks, and missing tests."));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "resume", "Resume a saved conversation",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "fork", "Fork the current conversation",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "side", "Start an ephemeral side conversation",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "mcp", "List MCP tools",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "apps", "Browse apps/connectors",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "plugins", "Browse installed and discoverable plugins",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "feedback", "Send feedback with diagnostics",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "diff", "Review changes with a diff",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "ps", "Show background terminals",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "stop", "Stop background terminals",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.CODEX, "quit", "Exit the CLI",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
    }

    /**
     * 追加 OpenCode 内建命令。
     *
     * @param definitions 命令定义集合
     */
    private void addOpenCodeBuiltins(List<SlashCommandDefinition> definitions) {
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "init", "Initialize the current project",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "undo", "Undo the last action",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "redo", "Redo the last action",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "share", "Share the current session",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "help", "Show help and available commands",
                SlashCommandActionType.INFO_ONLY, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "new", "Start a new session",
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "clear", "Clear the current chat",
                SlashCommandActionType.OPEN_NEW_SESSION, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "compact", "Compact the current conversation",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), "Summarize the conversation so far and keep the important details."));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "sessions", "List available sessions",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "agent", "Switch or inspect agents",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "models", "List available models",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "providers", "Manage providers and credentials",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of("auth"), null));
        this.addDefinition(definitions, this.definition(CLIType.OPENCODE, "mcp", "Manage MCP servers",
                SlashCommandActionType.SEND_PROMPT, SlashCommandSourceType.BUILTIN, List.of(), null));
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
                        command.subtask != null && command.subtask
                                ? SlashCommandActionType.SEND_PROMPT
                                : SlashCommandActionType.SEND_PROMPT,
                        this.normalizeGroup(configPath),
                        configPath.toString()
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
                    .filter(path -> path.getFileName().toString().endsWith(MD_SUFFIX))
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
     *
     * @param definitions 命令定义集合
     * @param root       插件根目录
     * @param cliType    CLI 类型
     */
    private void addPluginDirectories(List<SlashCommandDefinition> definitions, Path root, CLIType cliType) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (fileName.endsWith(MD_SUFFIX) && path.toString().contains(Path.of(COMMANDS_DIR).toString())) {
                            this.addMarkdownCommand(definitions, path, cliType, SlashCommandSourceType.PLUGIN, false);
                        } else if ("SKILL.md".equalsIgnoreCase(fileName)) {
                            this.addMarkdownCommand(definitions, path, cliType, SlashCommandSourceType.SKILL, true);
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to scan plugin directory: {}", root, e);
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
                    path.toString()
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
        if (rawText == null || rawText.isBlank() || !rawText.startsWith(SLASH)) {
            return null;
        }
        String line = rawText.stripLeading();
        if (!line.startsWith(SLASH)) {
            return null;
        }
        int spaceIndex = line.indexOf(' ');
        String name = spaceIndex > 1 ? line.substring(1, spaceIndex) : line.substring(1);
        String argText = spaceIndex > 0 && spaceIndex < line.length() - 1 ? line.substring(spaceIndex + 1) : "";
        List<String> arguments = this.parseArguments(argText);
        return new ParsedSlashCommand(name, arguments);
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
        return new SlashCommandDefinition(cliType, name, aliases, description, template, sourceType,
                actionType, null, null);
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
            String key = definition.name().toLowerCase(Locale.ROOT);
            map.putIfAbsent(key, definition);
        }
        return new ArrayList<>(map.values());
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
        return parent != null && parent.getFileName() != null ? parent.getFileName().toString() : null;
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
     * 斜杠命令解析结果。
     *
     * @param name      命令名
     * @param arguments 参数列表
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record ParsedSlashCommand(String name, List<String> arguments) {
    }

    /**
     * 斜杠命令执行入参。
     *
     * @param cliType     CLI 类型
     * @param rawText     原始文本
     * @param commandName 命令名
     * @param arguments   参数列表
     * @param projectPath 项目路径
     * @param requestId   请求 ID
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record SlashCommandInvocation(CLIType cliType, String rawText, String commandName,
                                          List<String> arguments, String projectPath, String requestId) {
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
                                          SlashCommandActionType actionType, String group, String originPath) {
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
            return SlashCommandExecutionPayload.builder()
                    .requestId(invocation.requestId())
                    .cliType(invocation.cliType().name())
                    .commandName(definition.name())
                    .executionType(SlashCommandActionType.OPEN_NEW_SESSION.name())
                    .prompt(null)
                    .openFreshSession(true)
                    .refreshHistory(false)
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
            String prompt = service.expandTemplate(definition, invocation);
            if (prompt == null || prompt.isBlank()) {
                prompt = invocation.rawText();
            }
            return SlashCommandExecutionPayload.builder()
                    .requestId(invocation.requestId())
                    .cliType(invocation.cliType().name())
                    .commandName(definition.name())
                    .executionType(definition.actionType().name())
                    .prompt(prompt)
                    .openFreshSession(false)
                    .refreshHistory(false)
                    .toastMessage(null)
                    .build();
        }
    }

    /**
     * 仅展示信息的命令策略。
     */
    private static final class InfoOnlyStrategy implements SlashCommandStrategy {

        @Override
        public boolean supports(SlashCommandDefinition definition) {
            return definition.actionType() == SlashCommandActionType.INFO_ONLY;
        }

        @Override
        public SlashCommandExecutionPayload execute(SlashCommandDefinition definition,
                                                    SlashCommandInvocation invocation,
                                                    SlashCommandService service) {
            return SlashCommandExecutionPayload.builder()
                    .requestId(invocation.requestId())
                    .cliType(invocation.cliType().name())
                    .commandName(definition.name())
                    .executionType(SlashCommandActionType.INFO_ONLY.name())
                    .prompt(null)
                    .openFreshSession(false)
                    .refreshHistory(false)
                    .toastMessage(definition.description())
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
