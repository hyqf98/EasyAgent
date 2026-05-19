package io.github.easyagent.ai.opencode;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.intellij.execution.configurations.GeneralCommandLine;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.TokenUsageContext;
import io.github.easyagent.ai.opencode.entity.StreamEvent;
import io.github.easyagent.ai.opencode.entity.StreamPart;
import io.github.easyagent.ai.opencode.entity.TokenUsage;
import io.github.easyagent.ai.opencode.entity.ToolState;
import io.github.easyagent.ai.opencode.enums.OpenCodeEventType;
import io.github.easyagent.ai.opencode.enums.OpenCodePartType;
import io.github.easyagent.ai.provider.AbstractCLIProvider;
import io.github.easyagent.ai.provider.RetryConfig;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.MessageType;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 基于 OpenCode CLI 的 AI 服务提供者。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class OpenCodeCLIProvider extends AbstractCLIProvider {

    /** 流式事件泛型类型。 */
    private static final Type STREAM_EVENT_TYPE = new TypeToken<StreamEvent<StreamPart>>() {}.getType();

    static {
        GsonUtils.registerEnums(OpenCodeEventType.class, OpenCodePartType.class);
    }

    /**
     * 使用默认命令路径构造（不重试）。
     */
    public OpenCodeCLIProvider() {
        super(CLIType.OPENCODE);
    }

    /**
     * 使用自定义命令路径构造（不重试）。
     *
     * @param commandPath 自定义命令路径
     */
    public OpenCodeCLIProvider(String commandPath) {
        super(CLIType.OPENCODE, commandPath);
    }

    /**
     * 使用自定义命令路径和重试配置构造。
     *
     * @param commandPath 自定义命令路径
     * @param retryConfig 重试配置
     */
    public OpenCodeCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.OPENCODE, commandPath, retryConfig);
    }

    /**
     * 构建 CLI 执行命令行。
     *
     * @param prompt         用户提示内容
     * @param sessionId      可选的会话 ID
     * @param modelId        可选的模型 ID
     * @param reasoningLevel 可选的推理等级
     * @return 配置好的命令行对象
     */
    private static final String PLAN_MODE_PREFIX = "[PLAN MODE - READ ONLY] You are in plan mode. "
            + "You may read files, search code, and analyze the project. "
            + "You MUST NOT edit, create, delete, or modify any files. "
            + "You MUST NOT execute shell commands that modify the filesystem. "
            + "Provide a detailed, actionable plan instead of making changes.\n\n";

    @Override
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId, String reasoningLevel, boolean planMode) {
        String effectivePrompt = planMode ? PLAN_MODE_PREFIX + prompt : prompt;
        GeneralCommandLine cmd = super.buildCommandLine(effectivePrompt, sessionId, modelId, reasoningLevel, planMode);
        cmd.addParameters("run", "--format", "json", "--dangerously-skip-permissions");
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
        }
        if (GsonUtils.isNotEmpty(reasoningLevel)) {
            cmd.addParameters("--variant", reasoningLevel);
        }
        if (GsonUtils.isNotEmpty(sessionId) && !sessionId.startsWith("new-")) {
            cmd.addParameters("--session", sessionId);
        }
        cmd.addParameters("--", prompt);
        return cmd;
    }

    /**
     * 解析 CLI 输出的单行文本并转换为统一响应列表。
     *
     * @param line 原始输出行
     * @return 统一响应列表，无法解析时返回 null 或空列表
     */
    @Override
    protected List<AIResponse> parseLine(String line) {
        if (!GsonUtils.isJsonObject(line)) {
            return null;
        }

        StreamEvent<StreamPart> event = GsonUtils.fromJson(line, STREAM_EVENT_TYPE);
        if (event == null || event.type() == null) {
            return null;
        }

        String sessionId = event.sessionId();
        StreamPart part = event.part();

        AIResponse response = this.convertToResponse(event.type(), sessionId, part, line);
        return response != null ? Collections.singletonList(response) : null;
    }

    /**
     * 将 OpenCode 事件转换为统一 AI 响应。
     *
     * @param type      事件类型
     * @param sessionId 会话 ID
     * @param part      流式分片数据
     * @param rawLine   原始行文本
     * @return 统一 AI 响应
     */
    private AIResponse convertToResponse(OpenCodeEventType type, String sessionId, StreamPart part, String rawLine) {
        OpenCodePartType partType = part != null ? part.type() : null;
        if (partType == OpenCodePartType.STEP_START || type == OpenCodeEventType.STEP_START) {
            return this.createStepStart(sessionId, part != null ? part.messageId() : null);
        }
        if (partType == OpenCodePartType.REASONING || type == OpenCodeEventType.REASONING) {
            String text = part != null ? part.text() : null;
            log.debug("[OpenCode] REASONING | eventType={} | partType={} | partNull={} | textLen={}", type, partType, part == null, text != null ? text.length() : -1);
            if (text == null || text.isEmpty()) {
                log.debug("[OpenCode] REASONING skipped: text empty | rawLine={}", rawLine.length() > 200 ? rawLine.substring(0, 200) + "..." : rawLine);
                return null;
            }
            return this.createMessage(sessionId, MessageType.THINKING, text);
        }
        if (partType == OpenCodePartType.TOOL || type == OpenCodeEventType.TOOL_USE) {
            return this.convertToolUse(sessionId, part);
        }
        if (partType == OpenCodePartType.STEP_FINISH || type == OpenCodeEventType.STEP_FINISH) {
            return this.convertStepFinish(sessionId, part, rawLine);
        }
        String text = part != null ? part.text() : null;
        if (text == null || text.isEmpty()) {
            log.debug("[OpenCode] UNKNOWN | eventType={} | partType={} | rawLine={}", type, partType, rawLine.length() > 200 ? rawLine.substring(0, 200) + "..." : rawLine);
            return null;
        }
        return this.createMessage(sessionId, MessageType.TEXT, text);
    }

    /**
     * 转换工具调用事件。
     *
     * @param sessionId 会话 ID
     * @param part      流式分片数据
     * @return 统一 AI 响应
     */
    private AIResponse convertToolUse(String sessionId, StreamPart part) {
        if (part == null) {
            return null;
        }
        ToolState state = part.state();
        String toolName = part.tool();
        String title = state != null ? state.title() : null;
        String input = state != null ? state.input() : null;
        String output = state != null ? state.output() : null;
        ToolCallStatus status = this.convertToolStatus(state);

        return this.createToolCall(sessionId, part.callId(), toolName, title, status, input, output);
    }

    /**
     * 将 OpenCode 工具状态映射为统一调用状态。
     *
     * @param state 工具状态数据
     * @return 统一工具调用状态
     */
    private ToolCallStatus convertToolStatus(ToolState state) {
        if (state == null) {
            return ToolCallStatus.CALLING;
        }
        if ("completed".equals(state.status())) {
            if (state.metadata() != null && state.metadata().exit() != 0) {
                return ToolCallStatus.FAILED;
            }
            return ToolCallStatus.COMPLETED;
        }
        if ("running".equals(state.status())) {
            return ToolCallStatus.CALLING;
        }
        return ToolCallStatus.FAILED;
    }

    /**
     * 转换步骤结束事件。
     *
     * @param sessionId 会话 ID
     * @param part      流式分片数据
     * @param rawLine   原始行文本
     * @return 统一 AI 响应
     */
    private AIResponse convertStepFinish(String sessionId, StreamPart part, String rawLine) {
        String reason = part != null ? part.reason() : null;
        TokenUsageContext tokenUsage = null;

        JsonObject obj = GsonUtils.parseObject(rawLine);
        JsonObject partJson = GsonUtils.getJsonObject(obj, "part");
        JsonObject tokensJson = GsonUtils.getJsonObject(partJson, "tokens");
        if (tokensJson != null) {
            TokenUsage tokens = GsonUtils.fromJson(tokensJson, TokenUsage.class);
            long cacheRead = tokens.cache() != null ? tokens.cache().read() : 0;
            long cacheWrite = tokens.cache() != null ? tokens.cache().write() : 0;
            tokenUsage = TokenUsageContext.builder()
                    .total(tokens.total())
                    .input(tokens.total())
                    .output(tokens.output())
                    .reasoning(tokens.reasoning())
                    .cacheWrite(cacheWrite)
                    .cacheRead(cacheRead)
                    .build();
        }

        return this.createStepFinish(sessionId, reason, tokenUsage);
    }
}
