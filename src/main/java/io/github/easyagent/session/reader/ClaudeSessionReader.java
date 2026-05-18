package io.github.easyagent.session.reader;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.ContentBlockType;
import io.github.easyagent.enums.SessionRole;
import io.github.easyagent.enums.ValueEnum;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.HistoricalFileEditData;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.entity.TokenUsage;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Claude CLI 会话读取器。
 * <p>
 * 从本机 Claude CLI 的 JSONL 会话文件（{@code ~/.claude/projects/}）中
 * 读取会话和消息数据，解析会话元信息和消息详情，
 * 统一转换为 {@link SessionInfo} 和 {@link SessionMessage}。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class ClaudeSessionReader implements SessionReader {

    private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + File.separator + ".claude"
            + File.separator + "projects";

    private final String baseDir;

    /**
     * 创建 Claude 会话读取器。
     */
    public ClaudeSessionReader() {
        this(DEFAULT_BASE_DIR);
    }

    /**
     * 创建用于测试或指定目录的 Claude 会话读取器。
     *
     * @param baseDir Claude 项目根目录
     */
    ClaudeSessionReader(String baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 获取此读取器对应的 CLI 类型。
     *
     * @return {@link CLIType#CLAUDE}
     */
    @Override
    public CLIType getCliType() {
        return CLIType.CLAUDE;
    }

    /**
     * 判断 Claude CLI 在本机是否可用。
     *
     * @return 数据目录是否存在
     */
    @Override
    public boolean isAvailable() {
        return Files.isDirectory(Paths.get(baseDir));
    }

    /**
     * 列出 Claude 所有可用的会话，遍历项目目录下的 JSONL 文件。
     *
     * @return 会话摘要信息列表，按更新时间倒序排列
     */
    @Override
    public List<SessionInfo> listSessions() {
        List<SessionInfo> sessions = new ArrayList<>();
        Path base = Paths.get(baseDir);
        if (!Files.isDirectory(base)) {
            return sessions;
        }

        try (DirectoryStream<Path> projectDirs = Files.newDirectoryStream(base)) {
            for (Path projectDir : projectDirs) {
                if (!Files.isDirectory(projectDir)) {
                    continue;
                }
                try (DirectoryStream<Path> sessionFiles = Files.newDirectoryStream(projectDir, "*.jsonl")) {
                    for (Path sessionFile : sessionFiles) {
                        SessionInfo info = parseSessionInfo(sessionFile, projectDir);
                        if (info != null) {
                            sessions.add(info);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to list Claude sessions", e);
        }

        sessions.sort(Comparator.comparing(SessionInfo::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return sessions;
    }

    /**
     * 按项目路径筛选 Claude 会话列表。
     *
     * @param projectPath 项目路径关键词
     * @return 匹配的会话摘要信息列表
     */
    @Override
    public List<SessionInfo> listSessions(String projectPath) {
        return listSessions().stream()
                .filter(s -> matchesPath(s.projectPath(), projectPath))
                .collect(Collectors.toList());
    }

    /**
     * 根据会话 ID 查询指定会话的摘要信息。
     *
     * @param sessionId 会话唯一标识
     * @return 会话摘要信息，不存在时返回 {@code null}
     */
    @Override
    public SessionInfo getSession(String sessionId) {
        return listSessions().stream()
                .filter(s -> sessionId.equals(s.sessionId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 读取指定会话的完整消息列表。
     *
     * @param sessionId 会话唯一标识
     * @return 消息列表，按 JSONL 文件中的行顺序排列
     */
    @Override
    public List<SessionMessage> readMessages(String sessionId) {
        Path sessionFile = findSessionFile(sessionId);
        if (sessionFile == null) {
            return Collections.emptyList();
        }

        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> entry = GsonUtils.fromJson(line, GsonUtils.MAP_TYPE);
                    if (entry == null) {
                        continue;
                    }
                    SessionMessage msg = parseMessage(entry, sessionId);
                    if (msg == null) {
                        continue;
                    }
                    if (shouldMergeToolResult(msg) && mergeToolResultIntoAssistant(messages, msg)) {
                        continue;
                    }
                    if ("user".equals(entry.get("type")) || "assistant".equals(entry.get("type"))) {
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse Claude session line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read Claude session file: {}", sessionFile, e);
        }

        return messages;
    }

    /**
     * 删除指定会话的 JSONL 文件。
     *
     * @param sessionId 会话唯一标识
     * @return {@code true} 删除成功，{@code false} 会话文件不存在或删除失败
     */
    @Override
    public boolean deleteSession(String sessionId) {
        Path sessionFile = findSessionFile(sessionId);
        if (sessionFile == null) {
            return false;
        }
        try {
            Files.delete(sessionFile);
            log.info("Deleted Claude session: {}", sessionId);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete Claude session: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 解析单个 JSONL 会话文件的摘要信息。
     *
     * @param sessionFile 会话 JSONL 文件路径
     * @param projectDir  关联的项目目录
     * @return 会话摘要，文件为空或解析失败时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private SessionInfo parseSessionInfo(Path sessionFile, Path projectDir) {
        String sessionId = sessionFile.getFileName().toString().replace(".jsonl", "");
        long updatedAt = 0;
        long createdAt = Long.MAX_VALUE;
        int messageCount = 0;
        String model = null;
        String gitBranch = null;
        String title = null;
        String projectPath = decodeProjectPath(projectDir);

        try (BufferedReader reader = Files.newBufferedReader(sessionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> entry = GsonUtils.fromJson(line, GsonUtils.MAP_TYPE);
                    if (entry == null) {
                        continue;
                    }
                    String type = (String) entry.get("type");

                    long ts = GsonUtils.parseTimestamp(entry.get("timestamp"));
                    if (ts > 0) {
                        updatedAt = Math.max(updatedAt, ts);
                        createdAt = Math.min(createdAt, ts);
                    }

                    if ("user".equals(type) || "assistant".equals(type)) {
                        messageCount++;
                        if ("user".equals(type) && title == null) {
                            Map<String, Object> message = (Map<String, Object>) entry.get("message");
                            if (message != null) {
                                Object content = message.get("content");
                                if (content instanceof String text) {
                                    title = extractTitle(text);
                                }
                            }
                        }
                    }

                    if (entry.containsKey("model")) {
                        String m = (String) entry.get("model");
                        if (m != null && !m.startsWith("<")) {
                            model = m;
                        }
                    }
                    if (entry.containsKey("gitBranch")) {
                        gitBranch = (String) entry.get("gitBranch");
                    }
                    if (entry.containsKey("cwd")) {
                        String cwd = (String) entry.get("cwd");
                        if (cwd != null && !cwd.isBlank()) {
                            projectPath = cwd;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            return null;
        }

        if (updatedAt == 0) {
            return null;
        }

        return SessionInfo.builder()
                .sessionId(sessionId)
                .cliType(getCliType())
                .projectPath(projectPath)
                .title(title != null ? title : "Claude Session")
                .model(model)
                .gitBranch(gitBranch)
                .createdAt(createdAt > 0 && createdAt < Long.MAX_VALUE ? createdAt : null)
                .updatedAt(updatedAt > 0 ? updatedAt : null)
                .messageCount(messageCount)
                .build();
    }

    /**
     * 将 Claude 项目目录名解码为可读路径。
     *
     * @param projectDir Claude 项目目录
     * @return 解码后的项目路径
     */
    private String decodeProjectPath(Path projectDir) {
        if (projectDir == null || projectDir.getFileName() == null) {
            return null;
        }
        String encoded = projectDir.getFileName().toString();
        if (encoded.isBlank()) {
            return null;
        }
        if (encoded.startsWith("-")) {
            return File.separator + encoded.substring(1).replace('-', File.separatorChar);
        }
        return encoded.replace('-', File.separatorChar);
    }

    /**
     * 解析 Claude JSONL 行数据为 {@link SessionMessage}。
     *
     * @param entry     JSONL 行解析后的 Map
     * @param sessionId 所属会话 ID
     * @return 会话消息，无 message 字段时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private SessionMessage parseMessage(Map<String, Object> entry, String sessionId) {
        Map<String, Object> message = (Map<String, Object>) entry.get("message");
        Object toolUseResultRaw = entry.get("toolUseResult");
        Map<String, Object> toolUseResult = (toolUseResultRaw instanceof Map<?, ?>)
                ? (Map<String, Object>) toolUseResultRaw : null;
        if (message == null) {
            return null;
        }

        String role = (String) message.get("role");
        String uuid = (String) entry.get("uuid");
        String parentUuid = (String) entry.get("parentUuid");
        String model = (String) message.get("model");
        String cwd = (String) entry.get("cwd");
        Long timestamp = GsonUtils.parseTimestamp(entry.get("timestamp"));

        List<ContentBlock> contents = new ArrayList<>();
        Object contentObj = message.get("content");
        if (contentObj instanceof List<?> contentList) {
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> block = (Map<String, Object>) map;
                    ContentBlock cb = parseContentBlock(block, toolUseResult);
                    if (cb != null) {
                        contents.add(cb);
                    }
                } else if (item instanceof String text && !text.isBlank()) {
                    contents.add(ContentBlock.builder()
                            .type(ContentBlockType.TEXT)
                            .text(text)
                            .build());
                }
            }
        } else if (contentObj instanceof String text && !text.isBlank()) {
            contents.add(ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text(text)
                    .build());
        }

        TokenUsage tokenUsage = null;
        Map<String, Object> usage = (Map<String, Object>) message.get("usage");
        if (usage != null) {
            tokenUsage = TokenUsage.builder()
                    .inputTokens(GsonUtils.toInt(usage.get("input_tokens")))
                    .outputTokens(GsonUtils.toInt(usage.get("output_tokens")))
                    .cacheCreationInputTokens(GsonUtils.toInt(usage.get("cache_creation_input_tokens")))
                    .cacheReadInputTokens(GsonUtils.toInt(usage.get("cache_read_input_tokens")))
                    .build();
        }

        return SessionMessage.builder()
                .uuid(uuid)
                .parentUuid(parentUuid)
                .sessionId(sessionId)
                .role(ValueEnum.fromValue(SessionRole.class, role))
                .model(model)
                .cwd(cwd)
                .timestamp(timestamp)
                .contents(contents)
                .tokenUsage(tokenUsage)
                .build();
    }

    /**
     * 判断消息是否应当作为 tool_result 合并到前一个助手消息。
     *
     * @param msg 会话消息
     * @return 是否应合并
     */
    private boolean shouldMergeToolResult(SessionMessage msg) {
        return msg != null
                && msg.role() == SessionRole.USER
                && isToolResultOnly(msg)
                && msg.parentUuid() != null
                && !msg.parentUuid().isBlank();
    }

    /**
     * 判断消息内容是否全部为工具结果。
     *
     * @param msg 会话消息
     * @return 是否只有工具结果块
     */
    private boolean isToolResultOnly(SessionMessage msg) {
        return msg != null
                && msg.contents() != null
                && !msg.contents().isEmpty()
                && msg.contents().stream().allMatch(block -> block != null && block.type() == ContentBlockType.TOOL_RESULT);
    }

    /**
     * 将 Claude 的 tool_result 行合并到对应的助手消息中。
     *
     * @param messages 已读取消息列表
     * @param toolResultMessage 工具结果消息
     * @return 合并成功返回 {@code true}
     */
    private boolean mergeToolResultIntoAssistant(List<SessionMessage> messages, SessionMessage toolResultMessage) {
        if (messages.isEmpty() || toolResultMessage == null) {
            return false;
        }
        String parentUuid = toolResultMessage.parentUuid();
        if (parentUuid == null || parentUuid.isBlank()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage previous = messages.get(i);
            if (previous == null || previous.role() != SessionRole.ASSISTANT) {
                continue;
            }
            if (!parentUuid.equals(previous.uuid())) {
                continue;
            }
            if (previous.contents() != null && toolResultMessage.contents() != null) {
                previous.contents().addAll(toolResultMessage.contents());
            }
            return true;
        }
        return false;
    }

    /**
     * 解析 Claude 原始内容块为统一的 {@link ContentBlock}。
     *
     * @param block 原始内容块数据
     * @return 统一内容块，type 为空时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private ContentBlock parseContentBlock(Map<String, Object> block, Map<String, Object> toolUseResult) {
        String type = (String) block.get("type");
        if (type == null) {
            return null;
        }

        return switch (type) {
            case "text" -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text((String) block.get("text"))
                    .build();
            case "thinking" -> ContentBlock.builder()
                    .type(ContentBlockType.THINKING)
                    .thinking((String) block.get("thinking"))
                    .build();
            case "tool_use" -> ContentBlock.builder()
                    .type(ContentBlockType.TOOL_USE)
                    .toolUseId((String) block.get("id"))
                    .toolName((String) block.get("name"))
                    .toolInput(block.get("input") != null ? GsonUtils.toJson(block.get("input")) : null)
                    .build();
            case "tool_result" -> ContentBlock.builder()
                    .type(ContentBlockType.TOOL_RESULT)
                    .toolUseId(GsonUtils.stringValue(block.get("tool_use_id")))
                    .toolOutput(block.get("content") instanceof String s ? s : String.valueOf(block.get("content")))
                    .isError(block.get("is_error") instanceof Boolean b && b)
                    .historicalFileEditData(parseHistoricalFileEditData(toolUseResult))
                    .build();
            default -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text(GsonUtils.toJson(block))
                    .build();
        };
    }

    /**
     * 解析 Claude 原始工具结果中的文件编辑数据。
     *
     * @param toolUseResult 原始工具结果
     * @return 历史文件编辑原始数据
     */
    private HistoricalFileEditData parseHistoricalFileEditData(Map<String, Object> toolUseResult) {
        if (toolUseResult == null) {
            return null;
        }
        String originalFile = GsonUtils.stringValue(toolUseResult.get("originalFile"));
        String oldString = GsonUtils.stringValue(toolUseResult.get("oldString"));
        String newString = GsonUtils.stringValue(toolUseResult.get("newString"));
        Boolean replaceAll = toolUseResult.get("replaceAll") instanceof Boolean b ? b : null;
        if (originalFile == null && oldString == null && newString == null) {
            return null;
        }
        return HistoricalFileEditData.builder()
                .originalFile(originalFile)
                .oldString(oldString)
                .newString(newString)
                .replaceAll(replaceAll)
                .build();
    }

    /**
     * 在所有项目目录中查找指定会话 ID 对应的 JSONL 文件。
     *
     * @param sessionId 会话唯一标识
     * @return 会话文件路径，未找到时返回 {@code null}
     */
    private Path findSessionFile(String sessionId) {
        Path base = Paths.get(baseDir);
        if (!Files.isDirectory(base)) {
            return null;
        }

        try (DirectoryStream<Path> projectDirs = Files.newDirectoryStream(base)) {
            for (Path projectDir : projectDirs) {
                if (!Files.isDirectory(projectDir)) {
                    continue;
                }
                Path sessionFile = projectDir.resolve(sessionId + ".jsonl");
                if (Files.exists(sessionFile)) {
                    return sessionFile;
                }
            }
        } catch (IOException e) {
            log.error("Failed to find Claude session file", e);
        }
        return null;
    }

    /**
     * 从用户消息内容中提取会话标题（取第一行非空文本）。
     *
     * @param content 用户消息原始内容
     * @return 提取的标题，最长 100 字符
     */
    private String extractTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("user:")) {
                String userText = trimmed.substring(5).trim();
                if (!userText.isBlank()) {
                    return userText.length() > 100 ? userText.substring(0, 100) + "..." : userText;
                }
            }
        }
        return null;
    }
}
