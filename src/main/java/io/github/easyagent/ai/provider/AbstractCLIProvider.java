package io.github.easyagent.ai.provider;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.BaseOutputReader;
import io.github.easyagent.ai.AIProvider;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.ErrorContent;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.RetryStatusContent;
import io.github.easyagent.ai.entity.StepFinishContent;
import io.github.easyagent.ai.entity.StepStartContent;
import io.github.easyagent.ai.entity.TokenUsageContext;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.MessageType;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CLI 类型 AI 提供者通用抽象基类。
 * <p>
 * 封装通过 IntelliJ {@link GeneralCommandLine} 和 {@link OSProcessHandler}
 * 执行本地 CLI 命令的通用流程。子类只需实现 CLI 特定的命令构建和行解析逻辑。
 * </p>
 * <p>
 * 默认使用 {@link OSProcessHandler} 管理进程（插件运行环境），
 * 单元测试环境可覆盖 {@link #useDirectProcess()} 返回 {@code true} 切换为
 * 标准 Java {@link Process} + {@link BufferedReader} 方式执行。
 * </p>
 * <p>
 * 支持通过 {@link RetryConfig} 配置重试策略：当 CLI 进程执行异常时自动重试，
 * 重试间隔采用指数退避策略，可配置超时时间。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractCLIProvider implements AIProvider {

    /** CLI 类型。 */
    private final CLIType cliType;

    /** 自定义命令路径，覆盖枚举默认值。 */
    private final String customCommandPath;

    /** 异步执行线程池（虚拟线程）。 */
    private final ExecutorService executor;

    /** 重试配置。 */
    private final RetryConfig retryConfig;

    /** 按会话 ID 存储的活跃进程处理器。 */
    private final ConcurrentHashMap<String, OSProcessHandler> activeProcessHandlers = new ConcurrentHashMap<>();

    /** 按会话 ID 存储的活跃直接进程（单元测试环境）。 */
    private final ConcurrentHashMap<String, Process> activeDirectProcesses = new ConcurrentHashMap<>();

    /** 按会话 ID 存储的重试中断标志。 */
    private final ConcurrentHashMap<String, Boolean> retryInterruptedFlags = new ConcurrentHashMap<>();

    /**
     * 使用指定的 CLI 类型构造（不重试）。
     *
     * @param cliType CLI 类型枚举
     */
    protected AbstractCLIProvider(CLIType cliType) {
        this(cliType, null, RetryConfig.NO_RETRY);
    }

    /**
     * 使用指定的 CLI 类型和自定义命令路径构造（不重试）。
     *
     * @param cliType           CLI 类型枚举
     * @param customCommandPath 自定义命令路径，为 null 时使用枚举默认值
     */
    protected AbstractCLIProvider(CLIType cliType, String customCommandPath) {
        this(cliType, customCommandPath, RetryConfig.NO_RETRY);
    }

    /**
     * 使用指定的 CLI 类型、自定义命令路径和重试配置构造。
     *
     * @param cliType           CLI 类型枚举
     * @param customCommandPath 自定义命令路径，为 null 时使用枚举默认值
     * @param retryConfig       重试配置，为 null 时使用 {@link RetryConfig#NO_RETRY}
     */
    protected AbstractCLIProvider(CLIType cliType, String customCommandPath, RetryConfig retryConfig) {
        this.cliType = cliType;
        this.customCommandPath = customCommandPath;
        this.retryConfig = retryConfig != null ? retryConfig : RetryConfig.NO_RETRY;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 获取 CLI 类型。
     *
     * @return CLI 类型枚举
     */
    protected CLIType getCliType() {
        return this.cliType;
    }

    /**
     * 获取实际使用的命令路径。
     *
     * @return 命令路径
     */
    protected String getCommandPath() {
        return this.customCommandPath != null ? this.customCommandPath : this.cliType.getCommandPath();
    }

    /**
     * 获取提供者名称。
     *
     * @return CLI 类型名称
     */
    @Override
    public String name() {
        return this.cliType.getName();
    }

    /**
     * 是否使用直接进程方式执行。
     * <p>
     * 默认返回 {@code false}，使用 IntelliJ {@link OSProcessHandler} 管理进程。
     * 单元测试环境覆盖此方法返回 {@code true}，使用标准 Java {@link Process}。
     * </p>
     *
     * @return 是否使用直接进程方式
     */
    protected boolean useDirectProcess() {
        return false;
    }

    /**
     * 发送提示消息并流式监听响应（无会话 ID）。
     *
     * @param prompt   用户提示内容
     * @param listener 流式事件监听器
     */
    @Override
    public void chat(String prompt, StreamEventListener listener) {
        this.chat(prompt, null, null, listener);
    }

    /**
     * 发送提示消息并流式监听响应（无模型 ID）。
     *
     * @param prompt    用户提示内容
     * @param sessionId 可选的会话 ID
     * @param listener  流式事件监听器
     */
    @Override
    public void chat(String prompt, String sessionId, StreamEventListener listener) {
        this.chat(prompt, sessionId, null, listener);
    }

    /**
     * 发送提示消息并流式监听响应。
     * <p>
     * 通过虚拟线程池异步执行 CLI 命令，根据 {@link #useDirectProcess()} 选择进程执行方式。
     * 当配置了 {@link RetryConfig} 时，执行失败会自动重试。
     * </p>
     *
     * @param prompt    用户提示内容
     * @param sessionId 可选的会话 ID
     * @param modelId   可选的模型 ID
     * @param listener  流式事件监听器
     */
    @Override
    public void chat(String prompt, String sessionId, String modelId, StreamEventListener listener) {
        String trackId = sessionId != null ? sessionId : "anon-" + System.nanoTime();
        this.executor.submit(() -> this.executeWithRetry(prompt, trackId, modelId, listener));
    }

    /**
     * 带重试逻辑的 CLI 执行入口。
     * <p>
     * 根据 {@link RetryConfig} 配置的最大重试次数进行重试，
     * 重试间隔采用指数退避策略（1s、2s、4s...，上限 10s）。
     * </p>
     *
     * @param prompt    用户提示内容
     * @param sessionId 可选的会话 ID
     * @param modelId   可选的模型 ID
     * @param listener  流式事件监听器
     */
    private void executeWithRetry(String prompt, String sessionId, String modelId, StreamEventListener listener) {
        int maxAttempts = this.retryConfig.isEnabled() ? this.retryConfig.maxRetries() + 1 : 1;
        Exception lastException = null;
        this.retryInterruptedFlags.remove(sessionId);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (this.retryInterruptedFlags.containsKey(sessionId)) {
                log.debug("Retry interrupted by user for session: {}", sessionId);
                return;
            }
            try {
                GeneralCommandLine commandLine = this.buildCommandLine(prompt, sessionId, modelId);
                log.debug("Executing command (attempt {}/{}): {}", attempt, maxAttempts, commandLine.getCommandLineString());

                if (this.useDirectProcess()) {
                    this.executeWithDirectProcess(commandLine, sessionId, listener);
                } else {
                    this.executeWithOSProcessHandler(commandLine, sessionId, listener);
                }
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts && !this.retryInterruptedFlags.containsKey(sessionId)) {
                    log.warn("CLI execution failed (attempt {}/{}), retrying: {}", attempt, maxAttempts, e.getMessage());
                    listener.onResponse(this.createRetryStatus(attempt + 1, maxAttempts, e.getMessage()));
                    this.sleepBeforeRetry(attempt);
                } else {
                    log.error("CLI execution failed after {} attempts", attempt, e);
                }
            }
        }

        listener.onError(lastException);
    }

    /**
     * 重试前的指数退避等待。
     *
     * @param attempt 当前重试次数（从 1 开始）
     */
    private void sleepBeforeRetry(int attempt) {
        try {
            long delay = Math.min(1000L * (1L << (attempt - 1)), 10_000L);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 使用 IntelliJ {@link OSProcessHandler} 执行进程（插件运行环境）。
     * <p>
     * 当配置了超时时间时，会限制进程执行时间，超时后销毁进程。
     * </p>
     *
     * @param commandLine 命令行
     * @param listener    流式事件监听器
     * @throws Exception 进程执行异常
     */
    private void executeWithOSProcessHandler(GeneralCommandLine commandLine, String sessionId, StreamEventListener listener) throws Exception {
        OSProcessHandler processHandler = new OSProcessHandler(commandLine) {
            @Override
            protected BaseOutputReader.Options readerOptions() {
                return BaseOutputReader.Options.BLOCKING;
            }
        };
        this.activeProcessHandlers.put(sessionId, processHandler);
        processHandler.addProcessListener(new ProcessListener() {
            @Override
            public void startNotified(@NotNull ProcessEvent event) {
            }

            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                String line = event.getText();
                if (StringUtil.isEmpty(line)) {
                    return;
                }
                AbstractCLIProvider.this.dispatchLine(line.trim(), listener);
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                AbstractCLIProvider.this.activeProcessHandlers.remove(sessionId);
                int exitCode = event.getExitCode();
                log.debug("Process exited with code: {} for session: {}", exitCode, sessionId);
                if (exitCode != 0) {
                    listener.onError(new RuntimeException("Process exited with code: " + event.getExitCode()));
                } else {
                    listener.onComplete();
                }
            }

            @Override
            public void processWillTerminate(@NotNull ProcessEvent event, boolean willBeDestroyed) {
            }
        });
        processHandler.startNotify();
        processHandler.waitFor();
    }

    /**
     * 使用标准 Java {@link Process} + {@link BufferedReader} 执行进程（单元测试环境）。
     *
     * @param commandLine 命令行
     * @param sessionId   会话 ID
     * @param listener    流式事件监听器
     * @throws Exception 进程执行异常
     */
    private void executeWithDirectProcess(GeneralCommandLine commandLine, String sessionId, StreamEventListener listener) throws Exception {
        Process process = commandLine.createProcess();
        this.activeDirectProcesses.put(sessionId, process);
        process.getOutputStream().close();
        log.debug("Direct process started, reading stdout...");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("Read raw line: {}", line);
                if (StringUtil.isEmpty(line)) {
                    continue;
                }
                this.dispatchLine(line.trim(), listener);
            }
        }

        this.activeDirectProcesses.remove(sessionId);
        int exitCode = process.waitFor();
        log.debug("Process exited with code: {} for session: {}", exitCode, sessionId);
        if (exitCode != 0) {
            throw new RuntimeException("Process exited with code: " + exitCode);
        }
        listener.onComplete();
    }

    /**
     * 解析单行输出并分发给监听器。
     *
     * @param line     原始输出行
     * @param listener 流式事件监听器
     */
    private void dispatchLine(String line, StreamEventListener listener) {
        try {
            List<AIResponse> responses = this.parseLine(line);
            if (!ContainerUtil.isEmpty(responses)) {
                for (AIResponse response : responses) {
                    if (response != null) {
                        listener.onResponse(response);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse line: {}", line, e);
        }
    }

    /**
     * 构建 CLI 执行命令行（无模型 ID）。
     *
     * @param prompt    用户提示内容
     * @param sessionId 可选的会话 ID
     * @return 配置好的命令行对象
     */
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId) {
        return this.buildCommandLine(prompt, sessionId, null);
    }

    /**
     * 构建 CLI 执行命令行。
     * <p>
     * 子类覆盖此方法拼接 CLI 特定参数，默认设置 UTF-8 编码、合并错误流和 NO_COLOR。
     * </p>
     *
     * @param prompt    用户提示内容
     * @param sessionId 可选的会话 ID，为 null 时忽略
     * @param modelId   可选的模型 ID，为 null 时使用默认模型
     * @return 配置好的命令行对象
     */
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId) {
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(this.getCommandPath());
        cmd.setCharset(StandardCharsets.UTF_8);
        cmd.setRedirectErrorStream(true);
        cmd.withEnvironment("NO_COLOR", "1");
        return cmd;
    }

    /**
     * 解析 CLI 输出的单行文本并转换为统一响应列表。
     *
     * @param line 原始输出行
     * @return 统一响应列表，无法解析时返回 null 或空列表
     */
    protected abstract List<AIResponse> parseLine(String line);

    /**
     * 创建步骤开始响应。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @return AIResponse
     */
    protected AIResponse createStepStart(String sessionId, String messageId) {
        return AIResponse.builder()
                .type(ResponseType.STEP_START)
                .sessionId(sessionId)
                .stepStart(StepStartContent.builder().messageId(messageId).build())
                .build();
    }

    /**
     * 创建消息响应（思考或文本）。
     *
     * @param sessionId   会话 ID
     * @param messageType 消息类型
     * @param text        消息文本
     * @return AIResponse
     */
    protected AIResponse createMessage(String sessionId, MessageType messageType, String text) {
        return AIResponse.builder()
                .type(ResponseType.MESSAGE)
                .sessionId(sessionId)
                .message(MessageContent.builder().messageType(messageType).text(text).build())
                .build();
    }

    /**
     * 创建工具调用响应。
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名称
     * @param title     调用标题
     * @param status    调用状态
     * @param input     输入参数
     * @param output    输出结果
     * @return AIResponse
     */
    protected AIResponse createToolCall(String sessionId, String toolName, String title,
                                        ToolCallStatus status,
                                        String input, String output) {
        return AIResponse.builder()
                .type(ResponseType.TOOL_USE)
                .sessionId(sessionId)
                .toolCall(ToolCallContent.builder()
                        .toolName(toolName)
                        .title(title)
                        .status(status)
                        .input(input)
                        .output(output)
                        .build())
                .build();
    }

    /**
     * 创建步骤结束响应。
     *
     * @param sessionId  会话 ID
     * @param reason     完成原因
     * @param tokenUsage 令牌使用统计
     * @return AIResponse
     */
    protected AIResponse createStepFinish(String sessionId, String reason, TokenUsageContext tokenUsage) {
        return AIResponse.builder()
                .type(ResponseType.STEP_FINISH)
                .sessionId(sessionId)
                .stepFinish(StepFinishContent.builder()
                        .reason(reason)
                        .tokenUsage(tokenUsage)
                        .build())
                .build();
    }

    /**
     * 创建错误响应。
     *
     * @param sessionId 会话 ID
     * @param message   错误消息
     * @return AIResponse
     */
    protected AIResponse createError(String sessionId, String message) {
        return AIResponse.builder()
                .type(ResponseType.ERROR)
                .sessionId(sessionId)
                .error(ErrorContent.builder().message(message).build())
                .build();
    }

    /**
     * 创建重试状态响应。
     *
     * @param currentAttempt 当前尝试次数
     * @param maxAttempts    最大尝试次数
     * @param reason         重试原因
     * @return AIResponse
     */
    protected AIResponse createRetryStatus(int currentAttempt, int maxAttempts, String reason) {
        return AIResponse.builder()
                .type(ResponseType.RETRY_STATUS)
                .retryStatus(RetryStatusContent.builder()
                        .currentAttempt(currentAttempt)
                        .maxAttempts(maxAttempts)
                        .reason(reason)
                        .build())
                .build();
    }

    /**
     * 停止当前正在运行的 CLI 进程。
     * <p>
     * 销毁活跃的 {@link OSProcessHandler} 或直接 {@link Process}，
     * 触发监听器的 {@code processTerminated} 回调。
     * </p>
     *
     * @since 1.0.0
     */
    @Override
    public void stop() {
        this.stop(null);
    }

    @Override
    public void stop(String sessionId) {
        if (sessionId != null) {
            this.retryInterruptedFlags.put(sessionId, true);
            OSProcessHandler handler = this.activeProcessHandlers.remove(sessionId);
            if (handler != null) {
                log.debug("Destroying process for session: {}", sessionId);
                handler.destroyProcess();
            }
            Process direct = this.activeDirectProcesses.remove(sessionId);
            if (direct != null) {
                log.debug("Destroying direct process for session: {}", sessionId);
                direct.destroyForcibly();
            }
            return;
        }

        this.retryInterruptedFlags.clear();
        this.activeProcessHandlers.forEach((sid, handler) -> {
            log.debug("Destroying process for session: {}", sid);
            handler.destroyProcess();
        });
        this.activeProcessHandlers.clear();
        this.activeDirectProcesses.forEach((sid, process) -> {
            log.debug("Destroying direct process for session: {}", sid);
            process.destroyForcibly();
        });
        this.activeDirectProcesses.clear();
    }

    /**
     * 关闭提供者，释放线程池资源。
     */
    @Override
    public void shutdown() {
        this.stop();
        this.executor.shutdownNow();
    }
}
