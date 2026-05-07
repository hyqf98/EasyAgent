package io.github.easyagent.session.reader;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;

import java.util.List;

/**
 * CLI 会话读取器接口。
 * <p>
 * 定义不同 CLI 工具（Claude、OpenCode、Codex）的会话数据读取抽象，
 * 各实现类负责从特定数据源（JSONL 文件、SQLite 数据库等）解析会话数据。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 * @see ClaudeSessionReader
 * @see OpenCodeSessionReader
 * @see CodexSessionReader
 */
public interface SessionReader {

    /**
     * 获取该读取器对应的 CLI 类型。
     *
     * @return CLI 类型枚举值
     */
    CLIType getCliType();

    /**
     * 检查该 CLI 工具是否在本地可用（数据目录是否存在）。
     *
     * @return {@code true} 表示可用，{@code false} 表示不可用
     */
    boolean isAvailable();

    /**
     * 列出该 CLI 工具的所有会话摘要信息。
     *
     * @return 会话摘要列表，不可用时返回空列表
     */
    List<SessionInfo> listSessions();

    /**
     * 列出指定项目路径下的所有会话摘要信息。
     *
     * @param projectPath 项目路径
     * @return 会话摘要列表
     */
    List<SessionInfo> listSessions(String projectPath);

    /**
     * 获取指定会话 ID 的摘要信息。
     *
     * @param sessionId 会话唯一标识
     * @return 会话摘要，不存在则返回 {@code null}
     */
    SessionInfo getSession(String sessionId);

    /**
     * 读取指定会话的完整消息列表。
     *
     * @param sessionId 会话唯一标识
     * @return 消息列表，按时间顺序排列
     */
    List<SessionMessage> readMessages(String sessionId);

    /**
     * 删除指定会话及其所有消息数据。
     *
     * @param sessionId 会话唯一标识
     * @return {@code true} 删除成功，{@code false} 会话不存在或删除失败
     */
    boolean deleteSession(String sessionId);
}
