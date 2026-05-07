package io.github.easyagent.ai.claude;

import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.containers.ContainerUtil;
import io.github.easyagent.ai.claude.entity.ClaudeContentBlock;
import io.github.easyagent.ai.claude.entity.ClaudeMessage;
import io.github.easyagent.ai.claude.entity.ClaudeResultUsage;
import io.github.easyagent.ai.claude.enums.ClaudeContentType;
import io.github.easyagent.ai.claude.enums.ClaudeEventType;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.CompactContent;
import io.github.easyagent.ai.entity.TokenUsageContext;
import io.github.easyagent.ai.provider.AbstractCLIProvider;
import io.github.easyagent.ai.provider.RetryConfig;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.MessageType;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Claude Code CLI 的 AI 服务提供者。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class ClaudeCLIProvider extends AbstractCLIProvider {

    static {
        GsonUtils.registerEnums(ClaudeEventType.class, ClaudeContentType.class);
    }

    public ClaudeCLIProvider() {
        super(CLIType.CLAUDE);
    }

    public ClaudeCLIProvider(String commandPath) {
        super(CLIType.CLAUDE, commandPath);
    }

    public ClaudeCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.CLAUDE, commandPath, retryConfig);
    }

    @Override
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId) {
        GeneralCommandLine cmd = super.buildCommandLine(prompt, sessionId, modelId);
        cmd.addParameters("-p", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions");
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
        }
        if (GsonUtils.isNotEmpty(sessionId)) {
            cmd.addParameters("--resume", sessionId);
        }
        cmd.addParameters("--", prompt);
        return cmd;
    }

    @Override
    protected List<AIResponse> parseLine(String line) {
        if (!GsonUtils.isJsonObject(line)) {
            return null;
        }

        JsonObject obj = GsonUtils.parseObject(line);
        String typeStr = GsonUtils.getString(obj, "type");
        String sessionId = GsonUtils.getString(obj, "session_id");

        List<AIResponse> responses = new ArrayList<>();
        this.convertEvent(typeStr, sessionId, obj, responses);
        return ContainerUtil.isEmpty(responses) ? null : responses;
    }

    private void convertEvent(String typeStr, String sessionId, JsonObject obj, List<AIResponse> out) {
        if (typeStr == null) {
            return;
        }
        switch (typeStr) {
            case "system" -> this.convertSystem(sessionId, obj, out);
            case "assistant" -> this.convertAssistant(sessionId, obj, out);
            case "user" -> this.convertUser(sessionId, obj, out);
            case "result" -> this.convertResult(sessionId, obj, out);
            case "stream_event" -> this.convertStreamEvent(sessionId, obj, out);
            default -> log.debug("Unknown claude event type: {}", typeStr);
        }
    }

    private void convertSystem(String sessionId, JsonObject obj, List<AIResponse> out) {
        String subtype = GsonUtils.getString(obj, "subtype");
        if ("init".equals(subtype)) {
            out.add(this.createStepStart(sessionId, null));
        } else if (subtype != null && subtype.startsWith("compact")) {
            out.add(this.createCompact(sessionId, "context compact: " + subtype));
        }
    }

    private void convertStreamEvent(String sessionId, JsonObject obj, List<AIResponse> out) {
        JsonObject event = GsonUtils.getJsonObject(obj, "event");
        if (event == null) {
            return;
        }
        String eventType = GsonUtils.getString(event, "type");
        if (!"content_block_delta".equals(eventType)) {
            return;
        }
        JsonObject delta = GsonUtils.getJsonObject(event, "delta");
        if (delta == null) {
            return;
        }
        String deltaType = GsonUtils.getString(delta, "type");
        if ("text_delta".equals(deltaType)) {
            String text = GsonUtils.getString(delta, "text");
            if (text != null) {
                out.add(this.createMessage(sessionId, MessageType.TEXT, text));
            }
        } else if ("thinking_delta".equals(deltaType)) {
            String thinking = GsonUtils.getString(delta, "thinking");
            if (thinking != null) {
                out.add(this.createMessage(sessionId, MessageType.THINKING, thinking));
            }
        }
    }

    private void convertAssistant(String sessionId, JsonObject obj, List<AIResponse> out) {
        JsonObject msgJson = GsonUtils.getJsonObject(obj, "message");
        if (msgJson == null) {
            return;
        }
        ClaudeMessage message = GsonUtils.fromJson(msgJson, ClaudeMessage.class);
        if (message.content() == null) {
            return;
        }
        for (ClaudeContentBlock block : message.content()) {
            if (block.getType() == null) {
                continue;
            }
            switch (block.getType()) {
                case THINKING -> {
                    if (block.getThinking() != null) {
                        out.add(this.createMessage(sessionId, MessageType.THINKING, block.getThinking()));
                    }
                }
                case TEXT -> {
                    if (block.getText() != null) {
                        out.add(this.createMessage(sessionId, MessageType.TEXT, block.getText()));
                    }
                }
                case TOOL_USE -> {
                    String input = block.getInput() != null ? GsonUtils.toJson(block.getInput()) : null;
                    out.add(this.createToolCall(sessionId, block.getName(), null, ToolCallStatus.CALLING,
                            input, null));
                }
                default -> {
                }
            }
        }
    }

    private void convertUser(String sessionId, JsonObject obj, List<AIResponse> out) {
        JsonObject msgJson = GsonUtils.getJsonObject(obj, "message");
        if (msgJson == null) {
            return;
        }
        ClaudeMessage message = GsonUtils.fromJson(msgJson, ClaudeMessage.class);
        if (message.content() == null) {
            return;
        }
        for (ClaudeContentBlock block : message.content()) {
            if (block.getType() == ClaudeContentType.TOOL_RESULT) {
                boolean isError = block.getIsError() != null && block.getIsError();
                ToolCallStatus status = isError ? ToolCallStatus.FAILED : ToolCallStatus.COMPLETED;
                out.add(this.createToolCall(sessionId, null, null, status, null, block.getContent()));
            }
        }
    }

    private void convertResult(String sessionId, JsonObject obj, List<AIResponse> out) {
        String subtype = GsonUtils.getString(obj, "subtype");
        String reason = "success".equals(subtype) ? "stop" : subtype;

        TokenUsageContext tokenUsage = null;
        JsonObject usageJson = GsonUtils.getJsonObject(obj, "usage");
        if (usageJson != null) {
            ClaudeResultUsage usage = GsonUtils.fromJson(usageJson, ClaudeResultUsage.class);
            tokenUsage = TokenUsageContext.builder()
                    .total(usage.getInputTokens() + usage.getOutputTokens())
                    .input(usage.getInputTokens())
                    .output(usage.getOutputTokens())
                    .cacheWrite(usage.getCacheCreationInputTokens())
                    .cacheRead(usage.getCacheReadInputTokens())
                    .build();
        }

        out.add(this.createStepFinish(sessionId, reason, tokenUsage));
    }

    private AIResponse createCompact(String sessionId, String reason) {
        return AIResponse.builder()
                .type(ResponseType.COMPACT)
                .sessionId(sessionId)
                .compact(CompactContent.builder().reason(reason).build())
                .build();
    }
}
