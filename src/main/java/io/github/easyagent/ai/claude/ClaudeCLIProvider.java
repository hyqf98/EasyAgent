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

    /**
     * 使用默认命令路径构造（不重试）。
     */
    public ClaudeCLIProvider() {
        super(CLIType.CLAUDE);
    }

    /**
     * 使用自定义命令路径构造（不重试）。
     *
     * @param commandPath 自定义命令路径
     */
    public ClaudeCLIProvider(String commandPath) {
        super(CLIType.CLAUDE, commandPath);
    }

    /**
     * 使用自定义命令路径和重试配置构造。
     *
     * @param commandPath 自定义命令路径
     * @param retryConfig 重试配置
     */
    public ClaudeCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.CLAUDE, commandPath, retryConfig);
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
    @Override
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId, String reasoningLevel, boolean planMode) {
        GeneralCommandLine cmd = super.buildCommandLine(prompt, sessionId, modelId, reasoningLevel, planMode);
        if (planMode) {
            cmd.addParameters("-p", "--output-format", "stream-json", "--verbose", "--permission-mode", "plan");
        } else {
            cmd.addParameters("-p", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions");
        }
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
        }
        if (GsonUtils.isNotEmpty(reasoningLevel)) {
            cmd.addParameters("--effort", reasoningLevel);
        }
        if (GsonUtils.isNotEmpty(sessionId) && !sessionId.startsWith("new-")) {
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
            out.add(this.createCompactFromJson(sessionId, "context compact: " + subtype, obj));
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
                    out.add(this.createToolCall(sessionId, block.getId(), block.getName(), null, ToolCallStatus.CALLING,
                            block.getInput(), null));
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
                out.add(this.createToolCall(sessionId, block.getToolUseId(), null, null, status, null, block.getContent()));
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

    private AIResponse createCompactFromJson(String sessionId, String reason, JsonObject obj) {
        JsonObject meta = GsonUtils.getJsonObject(obj, "compactMetadata");
        if (meta == null) {
            meta = GsonUtils.getJsonObject(obj, "compact_metadata");
        }
        CompactContent.CompactContentBuilder builder = CompactContent.builder().reason(reason);
        if (meta != null) {
            String trigger = GsonUtils.getString(meta, "trigger");
            builder.trigger(trigger);
            this.extractLong(meta, "preTokens", builder::preTokens);
            this.extractLong(meta, "pre_tokens", builder::preTokens);
            this.extractLong(meta, "postTokens", builder::postTokens);
            this.extractLong(meta, "post_tokens", builder::postTokens);
            this.extractLong(meta, "durationMs", builder::durationMs);
            this.extractLong(meta, "duration_ms", builder::durationMs);
            log.debug("[CLI] Compact metadata | trigger={} preTokens field={} | raw meta={}",
                    trigger, meta.has("pre_tokens") ? meta.get("pre_tokens") : meta.has("preTokens") ? meta.get("preTokens") : "null",
                    meta);
        } else {
            log.debug("[CLI] Compact metadata | NO compact_metadata found in: {}", obj.keySet());
        }
        CompactContent compact = builder.build();
        log.debug("[CLI] Compact content | trigger={} preTokens={} postTokens={} durationMs={}",
                compact.trigger(), compact.preTokens(), compact.postTokens(), compact.durationMs());
        return AIResponse.builder()
                .type(ResponseType.COMPACT)
                .sessionId(sessionId)
                .compact(compact)
                .build();
    }

    private void extractLong(JsonObject obj, String key, java.util.function.LongConsumer setter) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            setter.accept(obj.get(key).getAsLong());
        }
    }
}
