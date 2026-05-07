package io.github.easyagent.session;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.session.reader.ClaudeSessionReader;
import io.github.easyagent.session.reader.CodexSessionReader;
import io.github.easyagent.session.reader.OpenCodeSessionReader;
import io.github.easyagent.session.reader.SessionReader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CLI 会话管理服务。
 * <p>
 * 聚合管理 Claude、OpenCode、Codex 三种 CLI 工具的会话读取器，
 * 提供统一的会话查询和消息读取接口。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 * @see SessionReader
 */
@Slf4j
public class SessionService {

    private final Map<CLIType, SessionReader> readers;
    private final List<SessionReader> readerList;

    /**
     * 初始化会话服务，注册所有 CLI 读取器。
     */
    public SessionService() {
        this.readerList = List.of(
                new ClaudeSessionReader(),
                new CodexSessionReader(),
                new OpenCodeSessionReader()
        );
        Map<CLIType, SessionReader> map = new EnumMap<>(CLIType.class);
        for (SessionReader reader : this.readerList) {
            map.put(reader.getCliType(), reader);
        }
        this.readers = map;
    }

    /**
     * 列出所有可用的 CLI 工具及其可用状态。
     *
     * @return CLI 描述符列表
     */
    public List<CLIDescriptor> listAvailableCLIs() {
        List<CLIDescriptor> result = new ArrayList<>();
        for (SessionReader reader : this.readerList) {
            result.add(new CLIDescriptor(reader.getCliType(), reader.isAvailable()));
        }
        return result;
    }

    /**
     * 列出指定 CLI 类型的所有会话。
     *
     * @param cliType CLI 类型
     * @return 会话摘要列表，不可用时返回空列表
     */
    public List<SessionInfo> listSessions(CLIType cliType) {
        SessionReader reader = this.readers.get(cliType);
        if (reader == null || !reader.isAvailable()) {
            return Collections.emptyList();
        }
        return reader.listSessions();
    }

    /**
     * 列出所有可用 CLI 工具的会话，按更新时间倒序排列。
     *
     * @return 所有会话摘要列表
     */
    public List<SessionInfo> listAllSessions() {
        return this.listAllSessions(null);
    }

    /**
     * 列出所有可用 CLI 工具中匹配项目路径的会话，按更新时间倒序排列。
     *
     * @param projectPath 项目路径，为 {@code null} 时不做过滤
     * @return 会话摘要列表
     */
    public List<SessionInfo> listAllSessions(String projectPath) {
        List<SessionInfo> all = new ArrayList<>();
        for (SessionReader reader : this.readerList) {
            if (!reader.isAvailable()) {
                continue;
            }
            try {
                List<SessionInfo> sessions = projectPath != null
                        ? reader.listSessions(projectPath)
                        : reader.listSessions();
                all.addAll(sessions);
            } catch (Exception e) {
                log.warn("Failed to list sessions for {}", reader.getCliType(), e);
            }
        }
        all.sort(Comparator.comparing(SessionInfo::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /**
     * 列出指定 CLI 类型和项目路径下的所有会话。
     *
     * @param cliType     CLI 类型
     * @param projectPath 项目路径
     * @return 会话摘要列表
     */
    public List<SessionInfo> listSessions(CLIType cliType, String projectPath) {
        SessionReader reader = this.readers.get(cliType);
        if (reader == null || !reader.isAvailable()) {
            return Collections.emptyList();
        }
        return reader.listSessions(projectPath);
    }

    /**
     * 获取指定 CLI 类型下某个会话的摘要信息。
     *
     * @param cliType   CLI 类型
     * @param sessionId 会话唯一标识
     * @return 会话摘要，不存在则返回 {@code null}
     */
    public SessionInfo getSession(CLIType cliType, String sessionId) {
        SessionReader reader = this.readers.get(cliType);
        if (reader == null || !reader.isAvailable()) {
            return null;
        }
        return reader.getSession(sessionId);
    }

    /**
     * 在所有 CLI 类型中查找指定会话 ID 的摘要信息。
     *
     * @param sessionId 会话唯一标识
     * @return 会话摘要，不存在则返回 {@code null}
     */
    public SessionInfo findSession(String sessionId) {
        for (SessionReader reader : this.readerList) {
            if (!reader.isAvailable()) {
                continue;
            }
            try {
                SessionInfo info = reader.getSession(sessionId);
                if (info != null) {
                    return info;
                }
            } catch (Exception e) {
                log.debug("Session {} not found in {}", sessionId, reader.getCliType());
            }
        }
        return null;
    }

    /**
     * 读取指定 CLI 类型的会话消息列表。
     *
     * @param cliType   CLI 类型
     * @param sessionId 会话唯一标识
     * @return 消息列表
     */
    public List<SessionMessage> readMessages(CLIType cliType, String sessionId) {
        SessionReader reader = this.readers.get(cliType);
        if (reader == null || !reader.isAvailable()) {
            return Collections.emptyList();
        }
        return reader.readMessages(sessionId);
    }

    /**
     * 根据会话 ID 自动查找所属 CLI 类型并读取消息。
     *
     * @param sessionId 会话唯一标识
     * @return 消息列表
     */
    public List<SessionMessage> readMessages(String sessionId) {
        SessionInfo info = this.findSession(sessionId);
        if (info == null) {
            return Collections.emptyList();
        }
        return this.readMessages(info.cliType(), sessionId);
    }

    /**
     * 批量删除指定会话，自动根据会话 ID 查找所属 CLI 读取器执行删除。
     *
     * @param sessionIds 要删除的会话 ID 列表
     * @return 成功删除的会话数量
     */
    public int deleteSessions(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String sessionId : sessionIds) {
                if (sessionId == null || sessionId.isBlank()) {
                    continue;
                }
                futures.add(executor.submit(() -> this.deleteSession(sessionId)));
            }
            for (Future<Integer> future : futures) {
                try {
                    deleted += future.get();
                } catch (Exception e) {
                    log.warn("Failed to delete session batch item", e);
                }
            }
        }
        return deleted;
    }

    /**
     * 删除单个会话。
     *
     * @param sessionId 会话 ID
     * @return 成功删除的数量
     */
    private int deleteSession(String sessionId) {
        for (SessionReader reader : this.readerList) {
            if (!reader.isAvailable()) {
                continue;
            }
            try {
                if (reader.getSession(sessionId) != null) {
                    return reader.deleteSession(sessionId) ? 1 : 0;
                }
            } catch (Exception e) {
                log.debug("Session {} not found in {}", sessionId, reader.getCliType());
            }
        }
        return 0;
    }

    /**
     * CLI 工具描述符，包含类型和可用状态。
     *
     * @param type      CLI 类型
     * @param available 是否可用
     */
    public record CLIDescriptor(CLIType type, boolean available) {}
}
