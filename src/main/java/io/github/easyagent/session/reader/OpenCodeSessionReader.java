package io.github.easyagent.session.reader;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.ContentBlockType;
import io.github.easyagent.enums.SessionRole;
import io.github.easyagent.enums.ValueEnum;
import org.sqlite.JDBC;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.entity.TokenUsage;
import io.github.easyagent.ui.service.ToolMetadataSupport;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
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
 * OpenCode CLI 会话读取器。
 * <p>
 * 从本机 OpenCode CLI 的 SQLite 数据库（{@code ~/.local/share/opencode/opencode.db}）中
 * 读取会话和消息数据，解析会话元信息和消息详情，
 * 统一转换为 {@link SessionInfo} 和 {@link SessionMessage}。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class OpenCodeSessionReader implements SessionReader {

    private static final String DB_PATH = System.getProperty("user.home")
            + File.separator + ".local" + File.separator + "share"
            + File.separator + "opencode" + File.separator + "opencode.db";

    /**
     * 获取此读取器对应的 CLI 类型。
     *
     * @return {@link CLIType#OPENCODE}
     */
    @Override
    public CLIType getCliType() {
        return CLIType.OPENCODE;
    }

    /**
     * 判断 OpenCode CLI 在本机是否可用。
     *
     * @return 数据库文件是否存在
     */
    @Override
    public boolean isAvailable() {
        return Files.exists(Paths.get(DB_PATH));
    }

    /**
     * 列出 OpenCode 所有可用的会话，从 SQLite 数据库读取。
     *
     * @return 会话摘要信息列表，按更新时间倒序排列
     */
    @Override
    public List<SessionInfo> listSessions() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        List<SessionInfo> sessions = new ArrayList<>();
        String sql = """
                SELECT s.id, s.slug, s.directory, s.title, s.version, s.time_created, s.time_updated,
                       COUNT(DISTINCT m.id) as message_count
                FROM session s LEFT JOIN message m ON s.id = m.session_id
                GROUP BY s.id ORDER BY s.time_updated DESC
                """;

        try (Connection conn = createConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                SessionInfo info = SessionInfo.builder()
                        .sessionId(rs.getString("id"))
                        .cliType(getCliType())
                        .projectPath(rs.getString("directory"))
                        .title(rs.getString("title"))
                        .createdAt(rs.getLong("time_created"))
                        .updatedAt(rs.getLong("time_updated"))
                        .messageCount(rs.getInt("message_count"))
                        .build();
                sessions.add(info);
            }
        } catch (Exception e) {
            log.error("Failed to list OpenCode sessions", e);
        }

        return sessions;
    }

    /**
     * 按项目路径筛选 OpenCode 会话列表。
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
     * @return 会话摘要信息，不存在时返回 null
     */
    @Override
    public SessionInfo getSession(String sessionId) {
        if (!isAvailable()) {
            return null;
        }

        String sql = """
                SELECT s.id, s.slug, s.directory, s.title, s.version, s.time_created, s.time_updated,
                       COUNT(DISTINCT m.id) as message_count
                FROM session s LEFT JOIN message m ON s.id = m.session_id
                WHERE s.id = ? GROUP BY s.id
                """;

        try (Connection conn = createConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return SessionInfo.builder()
                        .sessionId(rs.getString("id"))
                        .cliType(getCliType())
                        .projectPath(rs.getString("directory"))
                        .title(rs.getString("title"))
                        .createdAt(rs.getLong("time_created"))
                        .updatedAt(rs.getLong("time_updated"))
                        .messageCount(rs.getInt("message_count"))
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to get OpenCode session: {}", sessionId, e);
        }
        return null;
    }

    /**
     * 读取指定会话的完整消息列表。
     *
     * @param sessionId 会话唯一标识
     * @return 会话消息列表，按时间顺序排列
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<SessionMessage> readMessages(String sessionId) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }

        List<SessionMessage> messages = new ArrayList<>();
        String sql = "SELECT id, time_created, data FROM message WHERE session_id = ? ORDER BY time_created, id";

        try (Connection conn = createConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String messageId = rs.getString("id");
                long timeCreated = rs.getLong("time_created");
                String dataJson = rs.getString("data");
                Map<String, Object> data = GsonUtils.fromJson(dataJson, GsonUtils.MAP_TYPE);

                if (data == null) {
                    continue;
                }

                List<ContentBlock> parts = readParts(conn, messageId);

                String role = (String) data.get("role");
                String model = (String) data.get("modelID");

                TokenUsage tokenUsage = null;
                Map<String, Object> tokens = (Map<String, Object>) data.get("tokens");
                if (tokens != null) {
                    Map<String, Object> cache = (Map<String, Object>) tokens.get("cache");
                    int total = GsonUtils.toInt(tokens.get("total"));
                    int cacheRead = cache != null ? GsonUtils.toInt(cache.get("read")) : 0;
                    int cacheWrite = cache != null ? GsonUtils.toInt(cache.get("write")) : 0;
                    tokenUsage = TokenUsage.builder()
                            .totalTokens(total)
                            .inputTokens(total)
                            .outputTokens(GsonUtils.toInt(tokens.get("output")))
                            .reasoningTokens(GsonUtils.toInt(tokens.get("reasoning")))
                            .cacheCreationInputTokens(cacheWrite)
                            .cacheReadInputTokens(cacheRead)
                            .build();
                }

                String parentId = (String) data.get("parentID");

                messages.add(SessionMessage.builder()
                        .uuid(messageId)
                        .parentUuid(parentId)
                        .sessionId(sessionId)
                        .role(ValueEnum.fromValue(SessionRole.class, role))
                        .model(model)
                        .timestamp(timeCreated)
                        .contents(parts)
                        .tokenUsage(tokenUsage)
                        .build());
            }
        } catch (Exception e) {
            log.error("Failed to read OpenCode messages for session: {}", sessionId, e);
        }

        return messages;
    }

    /**
     * 读取指定消息的所有 Part 内容块。
     *
     * @param conn      数据库连接
     * @param messageId 消息 ID
     * @return 内容块列表
     * @throws SQLException 数据库查询异常
     */
    @SuppressWarnings("unchecked")
    private List<ContentBlock> readParts(Connection conn, String messageId) throws SQLException {
        List<ContentBlock> blocks = new ArrayList<>();
        String sql = "SELECT data FROM part WHERE message_id = ? ORDER BY time_created, id";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, messageId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String dataJson = rs.getString("data");
                Map<String, Object> data = GsonUtils.fromJson(dataJson, GsonUtils.MAP_TYPE);
                if (data == null) {
                    continue;
                }

                String type = (String) data.get("type");
                ContentBlock block = null;

                if ("text".equals(type)) {
                    block = ContentBlock.builder()
                            .type(ContentBlockType.TEXT)
                            .text((String) data.get("text"))
                            .build();
                } else if ("reasoning".equals(type)) {
                    block = ContentBlock.builder()
                            .type(ContentBlockType.THINKING)
                            .thinking((String) data.get("text"))
                            .build();
                } else if ("step-start".equals(type)) {
                    block = ContentBlock.builder()
                            .type(ContentBlockType.STEP_START)
                            .build();
                } else if ("step-finish".equals(type)) {
                    String reason = (String) data.get("reason");
                    block = ContentBlock.builder()
                            .type(ContentBlockType.STEP_FINISH)
                            .text(reason)
                            .build();
                } else if ("tool".equals(type)) {
                    String toolName = (String) data.get("tool");
                    String toolUseId = (String) data.get("callID");
                    Map<String, Object> state = (Map<String, Object>) data.get("state");
                    String toolOutput = null;
                    String title = null;

                    String toolInputJson = null;
                    if (state != null) {
                        Object inputObj = state.get("input");
                        if (inputObj != null) {
                            toolInputJson = GsonUtils.toJson(inputObj);
                        }
                        Object outputObj = state.get("output");
                        if (outputObj != null) {
                            toolOutput = outputObj instanceof String s ? s : GsonUtils.toJson(outputObj);
                        }
                        title = (String) state.get("title");
                    }

                    block = ContentBlock.builder()
                            .type(ContentBlockType.TOOL_USE)
                            .toolUseId(toolUseId)
                            .toolName(toolName)
                            .toolInput(toolInputJson)
                            .toolOutput(toolOutput)
                            .text(title)
                            .historicalFileEditData(ToolMetadataSupport.resolveHistoricalFileEdit(toolName, toolInputJson))
                            .build();
                }

                if (block != null) {
                    blocks.add(block);
                }
            }
        }

        return blocks;
    }

    /**
     * 删除指定会话及其关联的消息数据。
     * <p>
     * 从 SQLite 数据库中删除 session 和关联的 message 记录。
     * </p>
     *
     * @param sessionId 会话唯一标识
     * @return {@code true} 删除成功，{@code false} 会话不存在或删除失败
     */
    @Override
    public boolean deleteSession(String sessionId) {
        if (!isAvailable()) {
            return false;
        }
        try (Connection conn = createConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delMsg = conn.prepareStatement("DELETE FROM message WHERE session_id = ?");
                 PreparedStatement delSession = conn.prepareStatement("DELETE FROM session WHERE id = ?")) {
                delMsg.setString(1, sessionId);
                delMsg.executeUpdate();
                delSession.setString(1, sessionId);
                int rows = delSession.executeUpdate();
                conn.commit();
                if (rows > 0) {
                    log.info("Deleted OpenCode session: {}", sessionId);
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            log.error("Failed to delete OpenCode session: {}", sessionId, e);
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
            return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        } catch (SQLException e) {
            return new JDBC().connect("jdbc:sqlite:" + DB_PATH, new Properties());
        }
    }

}
