package io.github.easyagent.session.reader;

import com.google.gson.reflect.TypeToken;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.ContentBlockType;
import io.github.easyagent.enums.SessionRole;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.entity.TokenUsage;
import io.github.easyagent.ui.service.ToolMetadataSupport;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.sqlite.JDBC;

/**
 * Codex CLI 会话读取器。
 * <p>
 * 从本机 Codex CLI 的 SQLite 数据库（{@code ~/.codex/state_5.sqlite}）和
 * JSONL 会话文件（{@code ~/.codex/sessions}）中读取会话数据，
 * 解析会话元信息和消息详情，统一转换为 {@link SessionInfo} 和 {@link SessionMessage}。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class CodexSessionReader implements SessionReader {

    private static final String HOME = System.getProperty("user.home");
    private static final String SESSIONS_DIR = HOME + File.separator + ".codex" + File.separator + "sessions";
    private static final String STATE_DB = HOME + File.separator + ".codex" + File.separator + "state_5.sqlite";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /**
     * 获取此读取器对应的 CLI 类型。
     *
     * @return {@link CLIType#CODEX}
     */
    @Override
    public CLIType getCliType() {
        return CLIType.CODEX;
    }

    /**
     * 判断 Codex CLI 在本机是否可用。
     *
     * @return 数据目录或数据库文件是否存在
     */
    @Override
    public boolean isAvailable() {
        return Files.isDirectory(Paths.get(SESSIONS_DIR)) || Files.exists(Paths.get(STATE_DB));
    }

    /**
     * 列出 Codex 所有可用的会话，从 SQLite 数据库读取。
     *
     * @return 会话摘要信息列表，按更新时间倒序排列
     */
    @Override
    public List<SessionInfo> listSessions() {
        if (!Files.exists(Paths.get(STATE_DB))) {
            return Collections.emptyList();
        }

        List<SessionInfo> sessions = new ArrayList<>();
        String sql = """
                SELECT id, cwd, title, model, model_provider, source, git_branch, created_at_ms, updated_at_ms
                FROM threads ORDER BY updated_at_ms DESC
                """;

        try (Connection conn = createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                SessionInfo info = SessionInfo.builder()
                        .sessionId(rs.getString("id"))
                        .cliType(getCliType())
                        .projectPath(rs.getString("cwd"))
                        .title(rs.getString("title"))
                        .model(rs.getString("model") != null ? rs.getString("model") : rs.getString("model_provider"))
                        .gitBranch(rs.getString("git_branch"))
                        .createdAt(rs.getLong("created_at_ms"))
                        .updatedAt(rs.getLong("updated_at_ms"))
                        .build();
                sessions.add(info);
            }
        } catch (Exception e) {
            log.error("Failed to list Codex sessions from DB", e);
        }

        return sessions;
    }

    /**
     * 按项目路径筛选 Codex 会话列表。
     *
     * @param projectPath 项目路径关键词
     * @return 匹配的会话摘要信息列表
     */
    @Override
    public List<SessionInfo> listSessions(String projectPath) {
        return listSessions().stream()
                .filter(s -> s.projectPath() != null && s.projectPath().contains(projectPath))
                .collect(Collectors.toList());
    }

    /**
     * 根据会话 ID 查询指定会话的摘要信息。
     *
     * @param sessionId 会话唯一标识
     * @return 会话摘要信息，不存在时返回 null
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
     * @return 会话消息列表，按时间顺序排列
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
                    Map<String, Object> entry = GsonUtils.fromJson(line, MAP_TYPE);
                    if (entry == null) {
                        continue;
                    }
                    String type = (String) entry.get("type");
                    if ("response_item".equals(type)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = (Map<String, Object>) entry.get("payload");
                        if (payload != null) {
                            SessionMessage msg = parseResponseItem(payload, sessionId);
                            if (msg != null) {
                                messages.add(msg);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse Codex session line", e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read Codex session file: {}", sessionFile, e);
        }

        return messages;
    }

    /**
     * 解析 response_item 事件为 SessionMessage。
     *
     * @param payload   事件 payload 数据
     * @param sessionId 会话 ID
     * @return SessionMessage，不支持的 payload 类型返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private SessionMessage parseResponseItem(Map<String, Object> payload, String sessionId) {
        String type = stringValue(payload.get("type"));
        return switch (type) {
            case "message" -> parseMessagePayload(payload, sessionId);
            case "reasoning" -> parseReasoningPayload(payload, sessionId);
            case "function_call" -> parseFunctionCallPayload(payload, sessionId);
            case "function_call_output" -> parseFunctionCallOutputPayload(payload, sessionId);
            default -> null;
        };
    }

    /**
     * 解析 Codex message payload。
     *
     * @param payload  原始 payload 数据
     * @param sessionId 会话 ID
     * @return SessionMessage，缺少角色时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private SessionMessage parseMessagePayload(Map<String, Object> payload, String sessionId) {
        String role = stringValue(payload.get("role"));
        if (role == null) {
            return null;
        }

        List<ContentBlock> contents = parseContentBlocks(payload.get("content"));
        SessionRole sessionRole = SessionRole.fromValue(role);
        return SessionMessage.builder()
                .sessionId(sessionId)
                .role(sessionRole != null ? sessionRole : SessionRole.ASSISTANT)
                .contents(contents)
                .build();
    }

    /**
     * 解析 Codex reasoning payload。
     *
     * @param payload  原始 payload 数据
     * @param sessionId 会话 ID
     * @return 仅包含思考块的助手消息
     */
    private SessionMessage parseReasoningPayload(Map<String, Object> payload, String sessionId) {
        String thinking = extractSummaryText(payload.get("summary"), stringValue(payload.get("content")));
        List<ContentBlock> contents = new ArrayList<>();
        contents.add(ContentBlock.builder()
                .type(ContentBlockType.THINKING)
                .thinking(thinking)
                .text(thinking)
                .build());
        return SessionMessage.builder()
                .sessionId(sessionId)
                .role(SessionRole.ASSISTANT)
                .contents(contents)
                .build();
    }

    /**
     * 解析 Codex function_call payload。
     *
     * @param payload  原始 payload 数据
     * @param sessionId 会话 ID
     * @return 仅包含工具调用块的助手消息
     */
    private SessionMessage parseFunctionCallPayload(Map<String, Object> payload, String sessionId) {
        String toolName = stringValue(payload.get("name"));
        String arguments = stringifyValue(payload.get("arguments"));
        List<ContentBlock> contents = new ArrayList<>();
        contents.add(ContentBlock.builder()
                .type(ContentBlockType.TOOL_USE)
                .toolUseId(stringValue(payload.get("call_id")))
                .toolName(toolName)
                .toolInput(arguments)
                .historicalFileEditData(ToolMetadataSupport.resolveHistoricalFileEdit(toolName, arguments))
                .build());
        return SessionMessage.builder()
                .sessionId(sessionId)
                .role(SessionRole.ASSISTANT)
                .contents(contents)
                .build();
    }

    /**
     * 解析 Codex function_call_output payload。
     *
     * @param payload  原始 payload 数据
     * @param sessionId 会话 ID
     * @return 仅包含工具结果块的助手消息
     */
    private SessionMessage parseFunctionCallOutputPayload(Map<String, Object> payload, String sessionId) {
        List<ContentBlock> contents = new ArrayList<>();
        contents.add(ContentBlock.builder()
                .type(ContentBlockType.TOOL_RESULT)
                .toolUseId(stringValue(payload.get("call_id")))
                .toolOutput(stringifyValue(payload.get("output")))
                .build());
        return SessionMessage.builder()
                .sessionId(sessionId)
                .role(SessionRole.ASSISTANT)
                .contents(contents)
                .build();
    }

    /**
     * 解析 Codex 内容块列表。
     *
     * @param contentObj 原始内容
     * @return 统一内容块列表
     */
    @SuppressWarnings("unchecked")
    private List<ContentBlock> parseContentBlocks(Object contentObj) {
        List<ContentBlock> contents = new ArrayList<>();
        if (contentObj instanceof List<?> contentList) {
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> map) {
                    ContentBlock cb = parseCodexContentBlock((Map<String, Object>) map);
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
        return contents;
    }

    /**
     * 解析 Codex 内容块为统一的 ContentBlock。
     *
     * @param block 原始内容块数据
     * @return ContentBlock，type 为空时返回 null
     */
    @SuppressWarnings("unchecked")
    private ContentBlock parseCodexContentBlock(Map<String, Object> block) {
        String type = stringValue(block.get("type"));
        if (type == null) {
            return null;
        }

        return switch (type) {
            case "input_text", "output_text", "text" -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text(stringValue(block.get("text")))
                    .build();
            case "thinking", "reasoning" -> ContentBlock.builder()
                    .type(ContentBlockType.THINKING)
                    .thinking(extractSummaryText(block.get("summary"), stringValue(block.get("text"))))
                    .text(extractSummaryText(block.get("summary"), stringValue(block.get("text"))))
                    .build();
            case "tool_call", "function_call" -> {
                Map<String, Object> function = (Map<String, Object>) block.get("function");
                String toolName = function != null ? stringValue(function.get("name")) : stringValue(block.get("name"));
                Object arguments = function != null ? function.get("arguments") : block.get("arguments");
                String inputJson = stringifyValue(arguments);
                yield ContentBlock.builder()
                        .type(ContentBlockType.TOOL_USE)
                        .toolUseId(stringValue(block.get("id")) != null ? stringValue(block.get("id")) : stringValue(block.get("call_id")))
                        .toolName(toolName)
                        .toolInput(inputJson)
                        .historicalFileEditData(ToolMetadataSupport.resolveHistoricalFileEdit(toolName, inputJson))
                        .build();
            }
            case "tool_result", "function_call_output" -> ContentBlock.builder()
                    .type(ContentBlockType.TOOL_RESULT)
                    .toolUseId(stringValue(block.get("tool_call_id")) != null ? stringValue(block.get("tool_call_id")) : stringValue(block.get("call_id")))
                    .toolOutput(stringifyValue(block.get("result") != null ? block.get("result") : block.get("output")))
                    .isError(block.get("is_error") instanceof Boolean b && b)
                    .build();
            default -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text(GsonUtils.toJson(block))
                    .build();
        };
    }

    /**
     * 通过数据库查找会话对应的 JSONL 文件路径。
     *
     * @param sessionId 会话唯一标识
     * @return 会话文件路径，未找到时返回 null
     */
    private Path findSessionFile(String sessionId) {
        if (Files.exists(Paths.get(STATE_DB))) {
            String sql = "SELECT rollout_path FROM threads WHERE id = ?";
            try (Connection conn = createConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sessionId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String path = rs.getString("rollout_path");
                    if (path != null && Files.exists(Paths.get(path))) {
                        return Paths.get(path);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to find Codex session from DB", e);
            }
        }
        return null;
    }

    /**
     * 提取 reasoning 的可展示文本。
     *
     * @param summaryObj reasoning summary 原始数据
     * @param fallback   备用文本
     * @return 可展示文本
     */
    @SuppressWarnings("unchecked")
    private String extractSummaryText(Object summaryObj, String fallback) {
        if (summaryObj instanceof List<?> list && !list.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> summary = (Map<String, Object>) map;
                    String text = stringValue(summary.get("text"));
                    if (text == null) {
                        text = stringValue(summary.get("summary"));
                    }
                    if (text != null && !text.isBlank()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text.trim());
                    }
                } else if (item instanceof String text && !text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text.trim());
                }
            }
            if (builder.length() > 0) {
                return builder.toString();
            }
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "Thinking...";
    }

    /**
     * 将对象安全转换为字符串。
     *
     * @param value 待转换对象
     * @return 字符串值
     */
    private String stringifyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return GsonUtils.toJson(value);
    }

    /**
     * 将对象安全转换为字符串并去空。
     *
     * @param value 待转换对象
     * @return 字符串值，空值时返回 null
     */
    private String stringValue(Object value) {
        String text = stringifyValue(value);
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * 删除指定会话的数据库记录和关联的 JSONL 文件。
     *
     * @param sessionId 会话唯一标识
     * @return {@code true} 删除成功，{@code false} 会话不存在或删除失败
     */
    @Override
    public boolean deleteSession(String sessionId) {
        if (!Files.exists(Paths.get(STATE_DB))) {
            return false;
        }
        try (Connection conn = createConnection()) {
            Path sessionFile = findSessionFile(sessionId);
            String sql = "DELETE FROM threads WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sessionId);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    if (sessionFile != null) {
                        try {
                            Files.delete(sessionFile);
                        } catch (Exception e) {
                            log.debug("Failed to delete Codex session file: {}", sessionFile, e);
                        }
                    }
                    log.info("Deleted Codex session: {}", sessionId);
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            log.error("Failed to delete Codex session: {}", sessionId, e);
            return false;
        }
    }

    /**
     * 创建 SQLite 数据库连接。
     *
     * @return 数据库连接
     * @throws SQLException 数据库连接异常
     */
    private Connection createConnection() throws SQLException {
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + STATE_DB);
        } catch (SQLException e) {
            return new JDBC().connect("jdbc:sqlite:" + STATE_DB, new Properties());
        }
    }
}
