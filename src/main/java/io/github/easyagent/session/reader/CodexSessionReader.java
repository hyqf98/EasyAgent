package io.github.easyagent.session.reader;

import com.google.gson.reflect.TypeToken;
import io.github.easyagent.enums.CLIType;
import org.sqlite.JDBC;
import io.github.easyagent.enums.ContentBlockType;
import io.github.easyagent.enums.SessionRole;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.entity.TokenUsage;
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
     * @return SessionMessage，role 为空时返回 null
     */
    @SuppressWarnings("unchecked")
    private SessionMessage parseResponseItem(Map<String, Object> payload, String sessionId) {
        String role = (String) payload.get("role");
        if (role == null) {
            return null;
        }

        List<ContentBlock> contents = new ArrayList<>();
        Object contentObj = payload.get("content");
        if (contentObj instanceof List<?> contentList) {
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> block = (Map<String, Object>) map;
                    ContentBlock cb = parseCodexContentBlock(block);
                    if (cb != null) {
                        contents.add(cb);
                    }
                }
            }
        }

        return SessionMessage.builder()
                .sessionId(sessionId)
                .role(SessionRole.fromValue(role))
                .contents(contents)
                .build();
    }

    /**
     * 解析 Codex 内容块为统一的 ContentBlock。
     *
     * @param block 原始内容块数据
     * @return ContentBlock，type 为空时返回 null
     */
    @SuppressWarnings("unchecked")
    private ContentBlock parseCodexContentBlock(Map<String, Object> block) {
        String type = (String) block.get("type");
        if (type == null) {
            return null;
        }

        return switch (type) {
            case "input_text", "output_text" -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text((String) block.get("text"))
                    .build();
            case "tool_call" -> {
                Map<String, Object> function = (Map<String, Object>) block.get("function");
                if (function != null) {
                    yield ContentBlock.builder()
                            .type(ContentBlockType.TOOL_USE)
                            .toolName((String) function.get("name"))
                            .toolInput(parseJsonToMap((String) function.get("arguments")))
                            .build();
                }
                yield null;
            }
            case "tool_result" -> ContentBlock.builder()
                    .type(ContentBlockType.TOOL_RESULT)
                    .toolOutput(block.get("result") instanceof String s ? s : String.valueOf(block.get("result")))
                    .build();
            case "reasoning" -> ContentBlock.builder()
                    .type(ContentBlockType.THINKING)
                    .thinking((String) block.get("summary"))
                    .build();
            default -> ContentBlock.builder()
                    .type(ContentBlockType.TEXT)
                    .text(GsonUtils.toJson(block))
                    .build();
        };
    }

    /**
     * 将 JSON 字符串解析为 Map。
     *
     * @param json JSON 字符串
     * @return 解析后的 Map，解析失败或输入为空时返回 null
     */
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return GsonUtils.fromJson(json, MAP_TYPE);
        } catch (Exception e) {
            return null;
        }
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
