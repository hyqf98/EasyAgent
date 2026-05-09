package io.github.easyagent.ui.service;

import io.github.easyagent.ai.AIProvider;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.claude.ClaudeProviderFactory;
import io.github.easyagent.ai.codex.CodexProviderFactory;
import io.github.easyagent.ai.opencode.OpenCodeProviderFactory;
import io.github.easyagent.ai.provider.AbstractCLIProvider;
import io.github.easyagent.ai.provider.RetryConfig;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.session.entity.SessionMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 对话管理器。
 * <p>
 * 管理 {@link AIProvider} 实例的生命周期和对话会话状态。
 * 按 CLI 类型缓存 Provider 实例，支持发送消息、停止生成和关闭资源。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class ChatManager {

    /** 会话服务，负责读取各 CLI 的历史会话数据。 */
    private final SessionService sessionService;

    /** CLI 类型与 AI 提供者的缓存映射。 */
    private final Map<CLIType, AIProvider> providers;

    /** 当前活跃的 CLI 类型。 */
    @Getter
    private CLIType currentCLIType = CLIType.CLAUDE;

    /** 当前重试配置。 */
    private volatile RetryConfig currentRetryConfig = RetryConfig.NO_RETRY;

    /**
     * 构造对话管理器。
     */
    public ChatManager() {
        this.sessionService = new SessionService();
        this.providers = new EnumMap<>(CLIType.class);
    }

    /**
     * 加载历史会话消息并转换为前端 JSON。
     *
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型
     * @param projectPath 当前项目路径
     * @return 包含历史消息的前端 JSON 字符串
     */
    public String loadHistory(@NotNull String sessionId, @NotNull CLIType cliType, String projectPath) {
        this.currentCLIType = cliType;
        List<SessionMessage> messages = this.sessionService.readMessages(cliType, sessionId);
        if (messages == null) {
            messages = List.of();
        }
        return MessageConverter.toHistoryJson(sessionId, messages, projectPath);
    }

    /**
     * 发送用户消息到 AI Provider 进行流式对话。
     *
     * @param text      用户输入文本
     * @param sessionId 会话 ID，为 {@code null} 时创建新会话
     * @param cliType   目标 CLI 类型
     * @param projectPath 当前项目路径
     * @param modelId   模型 ID，为 {@code null} 时使用默认模型
     * @param reasoningLevel 推理等级，为 {@code null} 时使用默认
     * @param listener  流式事件监听器
     */
    public void sendMessage(@NotNull String text, @Nullable String sessionId,
                            @NotNull CLIType cliType, @Nullable String projectPath,
                            @Nullable String modelId, @Nullable String reasoningLevel,
                            @NotNull StreamEventListener listener) {
        this.currentCLIType = cliType;
        AIProvider provider = this.getOrCreateProvider(cliType);
        if (provider instanceof AbstractCLIProvider cliProvider) {
            cliProvider.setWorkingDirectory(projectPath);
        }
        provider.chat(text, sessionId, modelId, reasoningLevel, listener);
    }

    /**
     * 停止指定会话的 AI 生成。
     *
     * @param sessionId 会话 ID
     */
    public void stopGeneration(String sessionId) {
        for (AIProvider provider : this.providers.values()) {
            log.debug("Stopping generation for session: {}", sessionId);
            provider.stop(sessionId);
        }
    }

    /**
     * 停止当前正在进行的 AI 生成。
     * <p>
     * 通过 {@link AIProvider#stop()} 销毁当前活跃的 CLI 进程。
     * </p>
     */
    public void stopCurrentGeneration() {
        for (AIProvider provider : this.providers.values()) {
            provider.stop();
        }
    }

    /**
     * 获取会话服务实例。
     *
     * @return 会话服务
     */
    public SessionService getSessionService() {
        return this.sessionService;
    }

    /**
     * 更新 AI 重试策略配置，重新创建缓存的 Provider。
     *
     * @param maxRetries 最大重试次数
     * @param timeoutMs  单次执行超时时间（毫秒）
     */
    public void updateRetryConfig(int maxRetries, long timeoutMs) {
        this.currentRetryConfig = RetryConfig.builder()
                .maxRetries(maxRetries)
                .timeoutMs(timeoutMs)
                .build();
        this.providers.clear();
    }

    /**
     * 关闭所有缓存的 AI Provider，释放资源。
     */
    public void shutdown() {
        for (AIProvider provider : this.providers.values()) {
            try {
                provider.shutdown();
            } catch (Exception e) {
                log.warn("Failed to shutdown provider", e);
            }
        }
        this.providers.clear();
    }

    /**
     * 获取或创建指定 CLI 类型的 AI Provider。
     *
     * @param cliType CLI 类型
     * @return AI Provider 实例
     */
    private AIProvider getOrCreateProvider(CLIType cliType) {
        return this.providers.computeIfAbsent(cliType, this::createProvider);
    }

    /**
     * 创建指定 CLI 类型的 AI Provider。
     *
     * @param cliType CLI 类型
     * @return 新创建的 AI Provider 实例
     */
    private AIProvider createProvider(CLIType cliType) {
        if (this.currentRetryConfig.isEnabled()) {
            return switch (cliType) {
                case CLAUDE -> ClaudeProviderFactory.create(null, this.currentRetryConfig);
                case OPENCODE -> OpenCodeProviderFactory.create(null, this.currentRetryConfig);
                case CODEX -> CodexProviderFactory.create(null, this.currentRetryConfig);
            };
        }
        return switch (cliType) {
            case CLAUDE -> ClaudeProviderFactory.create();
            case OPENCODE -> OpenCodeProviderFactory.create();
            case CODEX -> CodexProviderFactory.create();
        };
    }
}
