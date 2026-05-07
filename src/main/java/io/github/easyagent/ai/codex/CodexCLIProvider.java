package io.github.easyagent.ai.codex;

import com.google.gson.JsonObject;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.containers.ContainerUtil;
import io.github.easyagent.ai.codex.entity.CodexItem;
import io.github.easyagent.ai.codex.entity.CodexUsage;
import io.github.easyagent.ai.codex.enums.CodexItemType;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.TokenUsageContext;
import io.github.easyagent.ai.provider.AbstractCLIProvider;
import io.github.easyagent.ai.provider.RetryConfig;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.MessageType;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Codex CLI 的 AI 服务提供者。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class CodexCLIProvider extends AbstractCLIProvider {

    static {
        GsonUtils.registerEnums(CodexItemType.class);
    }

    public CodexCLIProvider() {
        super(CLIType.CODEX);
    }

    public CodexCLIProvider(String commandPath) {
        super(CLIType.CODEX, commandPath);
    }

    public CodexCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.CODEX, commandPath, retryConfig);
    }

    @Override
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId) {
        GeneralCommandLine cmd = super.buildCommandLine(prompt, sessionId, modelId);
        cmd.addParameters("exec", "--json", "--skip-git-repo-check", "--sandbox", "workspace-write", "--ask-for-approval", "never");
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
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
        String type = GsonUtils.getString(obj, "type");

        List<AIResponse> responses = new ArrayList<>();
        this.convertEvent(type, obj, responses);
        return ContainerUtil.isEmpty(responses) ? null : responses;
    }

    private void convertEvent(String type, JsonObject obj, List<AIResponse> out) {
        switch (type) {
            case "thread.started" -> {
                String threadId = GsonUtils.getString(obj, "thread_id");
                out.add(this.createStepStart(threadId, null));
            }
            case "turn.started" -> {
            }
            case "item.completed" -> this.convertItemCompleted(obj, out);
            case "turn.completed" -> this.convertTurnCompleted(obj, out);
            default -> log.debug("Unknown codex event type: {}", type);
        }
    }

    private void convertItemCompleted(JsonObject obj, List<AIResponse> out) {
        JsonObject itemJson = GsonUtils.getJsonObject(obj, "item");
        if (itemJson == null) {
            return;
        }
        CodexItem item = GsonUtils.fromJson(itemJson, CodexItem.class);
        if (item.getType() == null) {
            return;
        }
        switch (item.getType()) {
            case AGENT_MESSAGE -> {
                if (item.getText() != null) {
                    out.add(this.createMessage(null, MessageType.TEXT, item.getText()));
                }
            }
            case TOOL_CALL -> out.add(this.createToolCall(null, item.getName(), null, ToolCallStatus.COMPLETED,
                    item.getArguments(), null));
        }
    }

    private void convertTurnCompleted(JsonObject obj, List<AIResponse> out) {
        TokenUsageContext tokenUsage = null;
        JsonObject usageJson = GsonUtils.getJsonObject(obj, "usage");
        if (usageJson != null) {
            CodexUsage usage = GsonUtils.fromJson(usageJson, CodexUsage.class);
            tokenUsage = TokenUsageContext.builder()
                    .total(usage.inputTokens() + usage.outputTokens())
                    .input(usage.inputTokens())
                    .output(usage.outputTokens())
                    .reasoning(usage.reasoningOutputTokens())
                    .build();
        }
        out.add(this.createStepFinish(null, "stop", tokenUsage));
    }
}
