package io.github.easyagent.ai.provider;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

import java.io.File;
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
 * 封装通过 IntelliJ {@link GeneralCommandLine} 创建本地进程，
 * 使用标准 Java {@link BufferedReader} 逐行读取进程输出。
 * 子类只需实现 CLI 特定的命令构建和行解析逻辑。
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

    /** 当前 CLI 进程工作目录。 */
    private volatile String workingDirectory;

    /** 按会话 ID 存储的活跃进程。 */
    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    /** 按会话 ID 存储的重试中断标志。 */
    private final ConcurrentHashMap<String, Boolean> retryInterruptedFlags = new ConcurrentHashMap<>();

    /** 按会话 ID 存储的手动停止标记（stop() 调用时设置）。 */
    private final ConcurrentHashMap<String, Boolean> manuallyStoppedFlags = new ConcurrentHashMap<>();

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
     * 设置当前 CLI 调用使用的工作目录。
     *
     * @param workingDirectory 工作目录绝对路径；为空时清空
     */
    public void setWorkingDirectory(@Nullable String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * 更新活跃进程的会话 ID 映射。
     * <p>
     * 当 CLI 返回的真实 session ID 与初始传入的不同时，
     * 需要调用此方法同步更新进程映射表，以便后续 stop() 能正确销毁进程。
     * </p>
     *
     * @param oldSessionId 旧的会话 ID
     * @param newSessionId 新的会话 ID
     */
    public void remapSessionId(String oldSessionId, String newSessionId) {
        Process process = this.activeProcesses.remove(oldSessionId);
        if (process != null) {
            this.activeProcesses.put(newSessionId, process);
            log.info("[CLI] Remapped session: {} -> {}", oldSessionId, newSessionId);
        }
        Boolean stopped = this.manuallyStoppedFlags.remove(oldSessionId);
        if (stopped != null) {
            this.manuallyStoppedFlags.put(newSessionId, stopped);
        }
        Boolean retryFlag = this.retryInterruptedFlags.remove(oldSessionId);
        if (retryFlag != null) {
            this.retryInterruptedFlags.put(newSessionId, retryFlag);
        }
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
     * 通过虚拟线程池异步执行 CLI 命令。
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
        this.chat(prompt, sessionId, modelId, null, listener);
    }

    @Override
    public void chat(String prompt, String sessionId, String modelId, String reasoningLevel, StreamEventListener listener) {
        String trackId = sessionId != null ? sessionId : "anon-" + System.nanoTime();
        this.executor.submit(() -> this.executeWithRetry(prompt, trackId, modelId, reasoningLevel, listener));
    }

    /**
     * 带重试逻辑的 CLI 执行入口。
     *
     * @param prompt    用户提示内容
     * @param sessionId 会话 ID
     * @param modelId   可选的模型 ID
     * @param listener  流式事件监听器
     */
    private void executeWithRetry(String prompt, String sessionId, String modelId, StreamEventListener listener) {
        this.executeWithRetry(prompt, sessionId, modelId, null, listener);
    }

    /**
     * 带重试逻辑的 CLI 执行入口（含推理等级）。
     *
     * @param prompt         用户提示内容
     * @param sessionId      会话 ID
     * @param modelId        可选的模型 ID
     * @param reasoningLevel 可选的推理等级
     * @param listener       流式事件监听器
     */
    private void executeWithRetry(String prompt, String sessionId, String modelId, String reasoningLevel, StreamEventListener listener) {
        int maxAttempts = this.retryConfig.isEnabled() ? this.retryConfig.maxRetries() + 1 : 1;
        Exception lastException = null;
        this.retryInterruptedFlags.remove(sessionId);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (this.retryInterruptedFlags.containsKey(sessionId)) {
                log.debug("Retry interrupted by user for session: {}", sessionId);
                return;
            }
            try {
                GeneralCommandLine commandLine = this.buildCommandLine(prompt, sessionId, modelId, reasoningLevel);
                log.info("[CLI] Executing (attempt {}/{}): {}", attempt, maxAttempts, commandLine.getCommandLineString());
                this.executeProcess(commandLine, sessionId, listener);
                return;
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                boolean manuallyStopped = msg.contains("137") || msg.contains("143");
                if (manuallyStopped) {
                    log.info("[CLI] Process manually stopped for session: {}", sessionId);
                    return;
                }
                if (attempt < maxAttempts && !this.retryInterruptedFlags.containsKey(sessionId)) {
                    log.warn("[CLI] Failed (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                    listener.onResponse(this.createRetryStatus(attempt + 1, maxAttempts, e.getMessage()));
                    this.sleepBeforeRetry(attempt);
                } else {
                    log.error("[CLI] Failed after {} attempts", attempt, e);
                }
            }
        }

        listener.onError(lastException);
    }

    /**
     * 执行 CLI 进程并逐行读取输出。
     *
     * @param commandLine 命令行
     * @param sessionId   会话 ID
     * @param listener    流式事件监听器
     * @throws Exception 进程执行异常
     */
    private void executeProcess(GeneralCommandLine commandLine, String sessionId, StreamEventListener listener) throws Exception {
        Process process = commandLine.createProcess();
        this.activeProcesses.put(sessionId, process);
        process.getOutputStream().close();
        log.info("[CLI] Process started for session: {}", sessionId);
        String lastOutputLine = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (StringUtil.isEmpty(line)) {
                    continue;
                }
                String trimmed = line.trim();
                log.debug("[CLI] Read line: {}", trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
                lastOutputLine = trimmed;
                this.dispatchLine(trimmed, listener);
            }
        }

        this.activeProcesses.remove(sessionId);
        boolean wasManuallyStopped = this.manuallyStoppedFlags.remove(sessionId) != null;
        int exitCode = process.waitFor();
        if (wasManuallyStopped || exitCode == 137 || exitCode == 143) {
            log.info("[CLI] Process manually stopped (exit code: {}) for session: {}", exitCode, sessionId);
            return;
        }
        log.info("[CLI] Process exited with code: {} for session: {}", exitCode, sessionId);
        if (exitCode != 0) {
            throw new RuntimeException(this.buildProcessFailureMessage(exitCode, lastOutputLine));
        }
        listener.onComplete();
    }

    /**
     * 构造进程退出错误信息。
     *
     * @param exitCode        退出码
     * @param lastOutputLine   最后一条输出
     * @return 可读的错误信息
     */
    protected String buildProcessFailureMessage(int exitCode, @Nullable String lastOutputLine) {
        if (lastOutputLine != null && lastOutputLine.contains("Prompt exceeds max length")) {
            return "当前会话上下文超过 Claude 的长度限制，请新建会话或先压缩上下文后再继续。";
        }
        if (lastOutputLine != null && !lastOutputLine.isBlank()) {
            return "Process exited with code: " + exitCode + ". Last output: " + lastOutputLine;
        }
        return "Process exited with code: " + exitCode;
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
            log.warn("[CLI] Failed to parse line: {}", line, e);
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
        return this.buildCommandLine(prompt, sessionId, null, null);
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
        return this.buildCommandLine(prompt, sessionId, modelId, null);
    }

    /**
     * 构建 CLI 执行命令行（含推理等级）。
     * <p>
     * 子类覆盖此方法拼接 CLI 特定参数，默认设置 UTF-8 编码、合并错误流和 NO_COLOR。
     * </p>
     *
     * @param prompt         用户提示内容
     * @param sessionId      可选的会话 ID，为 null 时忽略
     * @param modelId        可选的模型 ID，为 null 时使用默认模型
     * @param reasoningLevel 可选的推理等级，为 null 时使用默认
     * @return 配置好的命令行对象
     */
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId, String reasoningLevel) {
        GeneralCommandLine cmd = new GeneralCommandLine();
        cmd.setExePath(this.getCommandPath());
        cmd.setCharset(StandardCharsets.UTF_8);
        cmd.setRedirectErrorStream(true);
        if (!StringUtil.isEmptyOrSpaces(this.workingDirectory)) {
            cmd.setWorkDirectory(new File(this.workingDirectory));
        }
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
        return this.createToolCall(sessionId, null, toolName, title, status, input, output);
    }

    /**
     * 创建工具调用响应。
     *
     * @param sessionId  会话 ID
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param title      调用标题
     * @param status     调用状态
     * @param input      输入参数
     * @param output     输出结果
     * @return AIResponse
     */
    protected AIResponse createToolCall(String sessionId, String toolCallId, String toolName, String title,
                                        ToolCallStatus status, String input, String output) {
        return AIResponse.builder()
                .type(ResponseType.TOOL_USE)
                .sessionId(sessionId)
                .toolCall(ToolCallContent.builder()
                        .toolCallId(toolCallId)
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
     *
     * @since 1.0.0
     */
    @Override
    public void stop() {
        this.stop(null);
    }

    @Override
    public void stop(@Nullable String sessionId) {
        if (sessionId != null) {
            this.retryInterruptedFlags.put(sessionId, true);
            this.manuallyStoppedFlags.put(sessionId, true);
            Process process = this.activeProcesses.remove(sessionId);
            if (process != null) {
                log.info("[CLI] Destroying process for session: {}", sessionId);
                process.destroyForcibly();
            }
            return;
        }

        this.retryInterruptedFlags.clear();
        this.manuallyStoppedFlags.clear();
        this.activeProcesses.forEach((sid, process) -> {
            log.info("[CLI] Destroying process for session: {}", sid);
            process.destroyForcibly();
        });
        this.activeProcesses.clear();
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
