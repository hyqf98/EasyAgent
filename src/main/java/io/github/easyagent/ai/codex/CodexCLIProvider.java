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

    private volatile String currentThreadId;

    static {
        GsonUtils.registerEnums(CodexItemType.class);
    }

    /**
     * 使用默认命令路径构造（不重试）。
     */
    public CodexCLIProvider() {
        super(CLIType.CODEX);
    }

    /**
     * 使用自定义命令路径构造（不重试）。
     *
     * @param commandPath 自定义命令路径
     */
    public CodexCLIProvider(String commandPath) {
        super(CLIType.CODEX, commandPath);
    }

    /**
     * 使用自定义命令路径和重试配置构造。
     *
     * @param commandPath 自定义命令路径
     * @param retryConfig 重试配置
     */
    public CodexCLIProvider(String commandPath, RetryConfig retryConfig) {
        super(CLIType.CODEX, commandPath, retryConfig);
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
    protected GeneralCommandLine buildCommandLine(String prompt, String sessionId, String modelId, String reasoningLevel) {
        GeneralCommandLine cmd = super.buildCommandLine(prompt, sessionId, modelId, reasoningLevel);
        cmd.addParameters("exec", "--json", "--skip-git-repo-check", "--sandbox", "workspace-write", "--ask-for-approval", "never");
        if (GsonUtils.isNotEmpty(modelId)) {
            cmd.addParameters("--model", modelId);
        }
        if (GsonUtils.isNotEmpty(reasoningLevel)) {
            cmd.addParameters("--config", "model_reasoning_effort=" + reasoningLevel);
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
                this.currentThreadId = threadId;
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
        String sid = this.currentThreadId;
        switch (item.getType()) {
            case AGENT_MESSAGE -> {
                if (item.getText() != null) {
                    out.add(this.createMessage(sid, MessageType.TEXT, item.getText()));
                }
            }
            case TOOL_CALL -> out.add(this.createToolCall(sid, item.getCallId(), item.getName(), null, ToolCallStatus.COMPLETED,
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
        out.add(this.createStepFinish(this.currentThreadId, "stop", tokenUsage));
    }
}
