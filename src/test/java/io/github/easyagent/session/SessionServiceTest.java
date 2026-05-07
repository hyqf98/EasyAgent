package io.github.easyagent.session;

import io.github.easyagent.enums.CLIType;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionInfo;
import io.github.easyagent.session.entity.SessionMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionService 单元测试。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@EnabledOnOs(OS.MAC)
class SessionServiceTest {

    private static SessionService service;

    @BeforeAll
    static void setUp() {
        service = new SessionService();
    }

    @Test
    void listAvailableCLIs() {
        List<SessionService.CLIDescriptor> clis = service.listAvailableCLIs();
        assertThat(clis).hasSize(3);
        System.out.println("=== Available CLIs ===");
        for (SessionService.CLIDescriptor cli : clis) {
            System.out.println(cli.type() + ": available=" + cli.available());
        }
    }

    @Test
    void listClaudeSessions() {
        List<SessionInfo> sessions = service.listSessions(CLIType.CLAUDE);
        System.out.println("=== Claude Sessions (" + sessions.size() + ") ===");
        sessions.stream().limit(5).forEach(s ->
                System.out.printf("  [%s] %s | project=%s | title=%s%n",
                        s.sessionId().substring(0, 8),
                        s.cliType(),
                        s.projectPath(),
                        s.title() != null ? s.title().substring(0, Math.min(50, s.title().length())) : "N/A")
        );
        assertThat(sessions).isNotEmpty();
    }

    @Test
    void listCodexSessions() {
        List<SessionInfo> sessions = service.listSessions(CLIType.CODEX);
        System.out.println("=== Codex Sessions (" + sessions.size() + ") ===");
        sessions.stream().limit(5).forEach(s ->
                System.out.printf("  [%s] %s | project=%s | title=%s | model=%s%n",
                        s.sessionId().substring(0, 8),
                        s.cliType(),
                        s.projectPath(),
                        s.title() != null ? s.title().substring(0, Math.min(50, s.title().length())) : "N/A",
                        s.model())
        );
        assertThat(sessions).isNotEmpty();
    }

    @Test
    void listOpenCodeSessions() {
        List<SessionInfo> sessions = service.listSessions(CLIType.OPENCODE);
        System.out.println("=== OpenCode Sessions (" + sessions.size() + ") ===");
        sessions.stream().limit(5).forEach(s ->
                System.out.printf("  [%s] %s | project=%s | title=%s%n",
                        s.sessionId().substring(0, Math.min(12, s.sessionId().length())),
                        s.cliType(),
                        s.projectPath(),
                        s.title())
        );
        assertThat(sessions).isNotEmpty();
    }

    @Test
    void listAllSessions() {
        List<SessionInfo> all = service.listAllSessions();
        System.out.println("=== All Sessions (" + all.size() + ") ===");
        all.stream().limit(10).forEach(s ->
                System.out.printf("  [%s] %s | %s | %s%n",
                        s.cliType(),
                        s.sessionId().substring(0, Math.min(12, s.sessionId().length())),
                        s.projectPath(),
                        s.title() != null ? s.title().substring(0, Math.min(40, s.title().length())) : "N/A")
        );
        assertThat(all).isNotEmpty();
    }

    @Test
    void readClaudeSessionMessages() {
        List<SessionInfo> sessions = service.listSessions(CLIType.CLAUDE);
        if (sessions.isEmpty()) {
            System.out.println("No Claude sessions found, skipping");
            return;
        }

        SessionInfo first = sessions.get(0);
        List<SessionMessage> messages = service.readMessages(CLIType.CLAUDE, first.sessionId());
        System.out.printf("%n=== Claude Session [%s] Messages (%d) ===%n", first.sessionId().substring(0, 8), messages.size());
        for (SessionMessage msg : messages) {
            System.out.printf("  [%s] role=%s, contents=%d%n", msg.uuid() != null ? msg.uuid().substring(0, 8) : "N/A", msg.role(), msg.contents() != null ? msg.contents().size() : 0);
            if (msg.contents() != null) {
                for (ContentBlock cb : msg.contents()) {
                    System.out.printf("    - %s: %s%n", cb.type(), cb.text() != null ? cb.text().substring(0, Math.min(80, cb.text().length())) : (cb.toolName() != null ? cb.toolName() : "N/A"));
                }
            }
        }
        assertThat(messages).isNotEmpty();
    }

    @Test
    void readOpenCodeSessionMessages() {
        List<SessionInfo> sessions = service.listSessions(CLIType.OPENCODE);
        if (sessions.isEmpty()) {
            System.out.println("No OpenCode sessions found, skipping");
            return;
        }

        SessionInfo first = sessions.get(0);
        List<SessionMessage> messages = service.readMessages(CLIType.OPENCODE, first.sessionId());
        System.out.printf("%n=== OpenCode Session [%s] Messages (%d) ===%n", first.sessionId().substring(0, 12), messages.size());
        for (SessionMessage msg : messages) {
            System.out.printf("  [%s] role=%s, model=%s, contents=%d%n",
                    msg.uuid() != null ? msg.uuid().substring(0, 12) : "N/A",
                    msg.role(),
                    msg.model(),
                    msg.contents() != null ? msg.contents().size() : 0);
            if (msg.contents() != null) {
                for (ContentBlock cb : msg.contents()) {
                    String preview = switch (cb.type()) {
                        case TEXT, STEP_FINISH -> cb.text() != null ? cb.text().substring(0, Math.min(80, cb.text().length())) : "";
                        case THINKING -> cb.thinking() != null ? cb.thinking().substring(0, Math.min(80, cb.thinking().length())) : "";
                        case TOOL_USE -> cb.toolName() != null ? cb.toolName() : "";
                        default -> "";
                    };
                    System.out.printf("    - %s: %s%n", cb.type(), preview);
                }
            }
            if (msg.tokenUsage() != null) {
                System.out.printf("    tokens: in=%d out=%d total=%d%n",
                        msg.tokenUsage().inputTokens(),
                        msg.tokenUsage().outputTokens(),
                        msg.tokenUsage().totalTokens());
            }
        }
        assertThat(messages).isNotEmpty();
    }

    @Test
    void findSessionAcrossCLIs() {
        List<SessionInfo> codexSessions = service.listSessions(CLIType.CODEX);
        if (!codexSessions.isEmpty()) {
            String codexId = codexSessions.get(0).sessionId();
            SessionInfo found = service.findSession(codexId);
            assertThat(found).isNotNull();
            assertThat(found.cliType()).isEqualTo(CLIType.CODEX);
            System.out.println("Found Codex session: " + found.title());
        }

        List<SessionInfo> openCodeSessions = service.listSessions(CLIType.OPENCODE);
        if (!openCodeSessions.isEmpty()) {
            String ocId = openCodeSessions.get(0).sessionId();
            SessionInfo found = service.findSession(ocId);
            assertThat(found).isNotNull();
            assertThat(found.cliType()).isEqualTo(CLIType.OPENCODE);
            System.out.println("Found OpenCode session: " + found.title());
        }
    }

    @Test
    void queryAllSessionsAndPrintDetails() {
        List<SessionInfo> allSessions = service.listAllSessions();
        System.out.printf("%n=== Query All Sessions (%d total) ===%n", allSessions.size());

        for (SessionInfo session : allSessions) {
            System.out.printf("%n--- Session: %s ---%n", session.sessionId().substring(0, Math.min(12, session.sessionId().length())));
            System.out.printf("  CLI Type    : %s%n", session.cliType());
            System.out.printf("  Project     : %s%n", session.projectPath());
            System.out.printf("  Title       : %s%n", session.title());
            System.out.printf("  Model       : %s%n", session.model());
            System.out.printf("  Git Branch  : %s%n", session.gitBranch());
            System.out.printf("  Messages    : %d%n", session.messageCount());
            System.out.printf("  Created At  : %d%n", session.createdAt());
            System.out.printf("  Updated At  : %d%n", session.updatedAt());
        }

        assertThat(allSessions).isNotEmpty();
    }

    @Test
    void querySessionMessagesAndPrintDetails() {
        List<SessionInfo> allSessions = service.listAllSessions();
        assertThat(allSessions).isNotEmpty();

        int limit = Math.min(3, allSessions.size());
        List<SessionInfo> toRead = allSessions.subList(0, limit);

        for (SessionInfo session : toRead) {
            List<SessionMessage> messages = service.readMessages(session.cliType(), session.sessionId());
            System.out.printf("%n=== Session [%s] Messages (%d) ===%n",
                    session.sessionId().substring(0, Math.min(12, session.sessionId().length())),
                    messages.size());

            for (SessionMessage msg : messages) {
                System.out.printf("%n  [Message] uuid=%s, role=%s, model=%s%n",
                        msg.uuid() != null ? msg.uuid().substring(0, Math.min(12, msg.uuid().length())) : "N/A",
                        msg.role(),
                        msg.model());

                if (msg.contents() != null) {
                    for (ContentBlock cb : msg.contents()) {
                        System.out.printf("    [%s] ", cb.type());
                        switch (cb.type()) {
                            case TEXT -> System.out.println(cb.text() != null
                                    ? cb.text().substring(0, Math.min(100, cb.text().length()))
                                    : "");
                            case THINKING -> System.out.println("(thinking) " + (cb.thinking() != null
                                    ? cb.thinking().substring(0, Math.min(100, cb.thinking().length()))
                                    : ""));
                            case TOOL_USE -> System.out.printf("tool=%s%n", cb.toolName());
                            case TOOL_RESULT -> System.out.println(cb.toolOutput() != null
                                    ? cb.toolOutput().substring(0, Math.min(100, cb.toolOutput().length()))
                                    : "");
                            case STEP_START -> System.out.println("(step start)");
                            case STEP_FINISH -> System.out.println("reason=" + cb.text());
                            default -> System.out.println("(unknown)");
                        }
                    }
                }

                if (msg.tokenUsage() != null) {
                    System.out.printf("    [Tokens] input=%d, output=%d, total=%d%n",
                            msg.tokenUsage().inputTokens(),
                            msg.tokenUsage().outputTokens(),
                            msg.tokenUsage().totalTokens());
                }
            }
        }
    }
}
