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
 * @email "mailto:haijun@email.com"
 * @version 1.0.0
 */
@Slf4j
public class OpenCodeCLIProvider extends AbstractCLIProvider {

    /**
     * stream event type.
     */
    private static final Type STREAM_EVENT_TYPE = new TypeToken<StreamEvent<StreamPart>>() {}.getType();

    static {
        GsonUtils.registerEnums(OpenCodeEventType.class);
    }

    /**
     * Open Code Cli Provider
     *
     * @since 1.0.0
     */
    public OpenCodeCLIProvider() {
        super(CLIType.OPENCODE);
    }

    /**
     * Open Code Cli Provider
     *
     * @param commandPath command path
     * @since 1.0.0
     */
    public OpenCodeCLIProvider(String commandPath) {
        super(CLIType.OPENCODE, commandPath);
    }

    /**
     * Open Code Cli Provider
     *
     * @param commandPath command path
     * @param retryConfig retry config
     * @since 1.0.0
     */
    public OpenCodeCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.OPENCODE, commandPath, retryConfig);
    }

    /**
     * Build Command Line
     *
     * @param prompt prompt
     * @param sessionId session id
     * @param modelId model id
     * @return general command line
     * @since 1.0.0
     */
    @Override
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId) {
        GeneralCommandLine cmd = super.buildCommandLine(prompt, sessionId, modelId);
        cmd.addParameters("run", "--format", "json", "--dangerously-skip-permissions");
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
        }
        if (GsonUtils.isNotEmpty(sessionId) && !sessionId.startsWith("new-")) {
            cmd.addParameters("--session", sessionId);
        }
        cmd.addParameters("--", prompt);
        return cmd;
    }

    /**
     * Parse Line
     *
     * @param line line
     * @return list
     * @since 1.0.0
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
     * Convert To Response
     *
     * @param type type
     * @param sessionId session id
     * @param part part
     * @param rawLine raw line
     * @return i response
     * @since 1.0.0
     */
    private AIResponse convertToResponse(OpenCodeEventType type, String sessionId, StreamPart part, String rawLine) {
        OpenCodePartType partType = part != null ? part.type() : null;
        if (partType == OpenCodePartType.STEP_START || type == OpenCodeEventType.STEP_START) {
            return this.createStepStart(sessionId, part != null ? part.messageId() : null);
        }
        if (partType == OpenCodePartType.REASONING || type == OpenCodeEventType.REASONING) {
            return this.createMessage(sessionId, MessageType.THINKING, part != null ? part.text() : null);
        }
        if (partType == OpenCodePartType.TOOL || type == OpenCodeEventType.TOOL_USE) {
            return this.convertToolUse(sessionId, part);
        }
        if (partType == OpenCodePartType.STEP_FINISH || type == OpenCodeEventType.STEP_FINISH) {
            return this.convertStepFinish(sessionId, part, rawLine);
        }
        return this.createMessage(sessionId, MessageType.TEXT, part != null ? part.text() : null);
    }

    /**
     * Convert Tool Use
     *
     * @param sessionId session id
     * @param part part
     * @return i response
     * @since 1.0.0
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
     * Convert Tool Status
     *
     * @param state state
     * @return tool call status
     * @since 1.0.0
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
     * Convert Step Finish
     *
     * @param sessionId session id
     * @param part part
     * @param rawLine raw line
     * @return i response
     * @since 1.0.0
     */
    private AIResponse convertStepFinish(String sessionId, StreamPart part, String rawLine) {
        String reason = part != null ? part.reason() : null;
        TokenUsageContext tokenUsage = null;

        JsonObject obj = GsonUtils.parseObject(rawLine);
        JsonObject partJson = GsonUtils.getJsonObject(obj, "part");
        JsonObject tokensJson = GsonUtils.getJsonObject(partJson, "tokens");
        if (tokensJson != null) {
            TokenUsage tokens = GsonUtils.fromJson(tokensJson, TokenUsage.class);
            tokenUsage = TokenUsageContext.builder()
                    .total(tokens.total())
                    .input(tokens.input())
                    .output(tokens.output())
                    .reasoning(tokens.reasoning())
                    .cacheWrite(tokens.cache() != null ? tokens.cache().write() : 0)
                    .cacheRead(tokens.cache() != null ? tokens.cache().read() : 0)
                    .build();
        }

        return this.createStepFinish(sessionId, reason, tokenUsage);
    }
}
