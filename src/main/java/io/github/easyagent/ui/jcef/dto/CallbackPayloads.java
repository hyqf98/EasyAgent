package io.github.easyagent.ui.jcef.dto;

import io.github.easyagent.settings.config.CliConfigs;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.config.CliProfile;
import io.github.easyagent.settings.mcp.McpTestService;
import io.github.easyagent.ui.service.entity.FileReferenceCandidatePayload;

import java.util.List;
import java.util.Map;

/**
 * Bridge 推送到前端的回调载荷 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class CallbackPayloads {

    private CallbackPayloads() {
    }

    public record ThemePayload(boolean isDark, Map<String, String> colors) {
    }

    public record StreamCompletePayload(String sessionId) {
    }

    public record RetryConfigPayload(int retryMaxCount, long retryTimeoutMs) {
    }

    public record PendingQueueStatePayload(String sessionId, String pendingQueue) {
    }

    public record RestoredStatePayload(String currentSessionId, String currentCliType,
                                        List<PendingQueueStatePayload> pendingQueues,
                                        int retryMaxCount, long retryTimeoutMs,
                                        String paneLayoutJson) {
    }

    public record FileReferenceCandidatesPayload(String requestId,
                                                   List<FileReferenceCandidatePayload> results) {
    }

    public record SessionsDeletedPayload(int deletedCount, List<String> sessionIds) {
    }

    public record CliConfigsPayload(CliConfigs configs,
                                      List<CliConfigService.ProviderInfo> providers,
                                      Map<String, List<CliProfile>> profiles,
                                      Map<String, String> resolvedCommandPaths) {
    }

    public record CliConfigsSavedPayload(boolean success, String cliType, String message) {
    }

    public record McpSavedPayload(boolean success, String cliType, String message) {
    }

    public record McpTestConnectedPayload(boolean success, String connectionId,
                                            String serverName, String serverInfo,
                                            List<McpTestService.ToolInfo> tools,
                                            String transportType,
                                            Map<String, String> env) {
    }

    public record McpToolsPayload(String connectionId,
                                    List<McpTestService.ToolInfo> tools) {
    }

    public record McpToolResultPayload(String connectionId, String toolName,
                                         McpTestService.ToolCallResult result) {
    }

    public record SkillActionPayload(boolean success, String cliType, String message) {
    }

    public record SkillContentPayload(String skillPath, String content) {
    }

    public record PluginActionPayload(boolean success, String cliType, String message) {
    }

    public record PluginContentPayload(String installPath, String content) {
    }

    public record PluginCommandsPayload(String installPath, List<Map<String, String>> commands) {
    }

    public record SaveContentResultPayload(boolean success, String path) {
    }
}
