package io.github.easyagent.ui.jcef.handler;

import com.intellij.openapi.util.text.StringUtil;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.enums.PlanStatus;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.plan.entity.Plan;
import io.github.easyagent.plan.entity.PlanTask;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.ChatRequests;
import io.github.easyagent.ui.jcef.dto.CommonRequests;
import io.github.easyagent.ui.service.MessageConverter;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 聊天消息 handler，负责发送用户消息、处理 AI 流式响应和停止生成。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
@Slf4j
public class ChatHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.SEND_MESSAGE, ChatRequests.SendMessageRequest.class,
                request -> this.sendUserMessage(ctx, request.text(), request.cliType(), request.sessionId(),
                        request.modelId(), request.reasoningLevel(), request.planMode(), request.fileReferences()));
        ctx.registerHandler(handlers, JsAction.STOP_GENERATION, CommonRequests.StopGenerationRequest.class,
                request -> this.handleStopGeneration(ctx, request));
    }

    /**
     * 发送用户消息到 AI Provider 并通过流式监听器将响应事件推送到前端。
     *
     * @param ctx            共享上下文
     * @param text           用户输入文本
     * @param cliType        CLI 类型名称
     * @param sessionId      会话 ID
     * @param modelId        模型 ID，可为 null
     * @param reasoningLevel 推理级别，可为 null
     * @param planMode       是否计划模式
     * @param fileReferences 文件引用列表
     */
    public void sendUserMessage(BridgeContext ctx, String text, String cliType, String sessionId,
                                String modelId, String reasoningLevel, Boolean planMode,
                                List<FileReferencePayload> fileReferences) {
        try {
            CLIType type = CLIType.valueOf(cliType);
            String effectiveSessionId = this.resolveEffectiveSessionId(ctx, sessionId);
            String prompt = ctx.fileReferenceService() != null
                    ? ctx.fileReferenceService().enrichPrompt(text, fileReferences)
                    : text;

            ctx.setCurrentSessionId(effectiveSessionId);

            Plan splittingPlan = this.findPlanBySessionId(ctx, sessionId, effectiveSessionId);
            StringBuilder planResponseBuilder = splittingPlan != null ? new StringBuilder() : null;

            ctx.chatManager().sendMessage(prompt, effectiveSessionId, type,
                    ctx.getProjectPath(), modelId, reasoningLevel, planMode != null && planMode, new StreamEventListener() {
                        @Override
                        public void onResponse(AIResponse response) {
                            ChatHandler.this.logAIResponse(response);
                            String resolvedSessionId = response.sessionId() != null
                                    ? response.sessionId()
                                    : effectiveSessionId;
                            if (response.toolCall() != null) {
                                ctx.fileEditService().trackToolCall(resolvedSessionId, response.toolCall());
                            }
                            if (planResponseBuilder != null && response.message() != null && response.message().text() != null) {
                                planResponseBuilder.append(response.message().text());
                            }
                            String eventJson = MessageConverter.toStreamEventJson(response, resolvedSessionId,
                                    ctx.getProjectPath());
                            ctx.invokeJSCallback(JsCallback.STREAM_EVENT, eventJson);
                            ctx.setCurrentSessionId(resolvedSessionId);
                            ChatHandler.this.persistCurrentSession(ctx, resolvedSessionId, cliType);
                        }

                        @Override
                        public void onComplete() {
                            String resolvedSessionId = ctx.getCurrentSessionId() != null
                                    ? ctx.getCurrentSessionId()
                                    : effectiveSessionId;

                            if (splittingPlan != null && planResponseBuilder != null) {
                                ChatHandler.this.tryExtractAndCreateTasks(ctx, splittingPlan, planResponseBuilder.toString(), resolvedSessionId);
                            }

                            ctx.invokeJSCallback(JsCallback.STREAM_COMPLETE,
                                    new CallbackPayloads.StreamCompletePayload(resolvedSessionId));
                        }

                        @Override
                        public void onError(Exception e) {
                            String errJson = MessageConverter.toErrorJson(e.getMessage(), effectiveSessionId);
                            ctx.invokeJSCallback(JsCallback.STREAM_EVENT, errJson);
                            String resolvedSid = ctx.getCurrentSessionId() != null
                                    ? ctx.getCurrentSessionId()
                                    : effectiveSessionId;
                            ctx.invokeJSCallback(JsCallback.STREAM_COMPLETE,
                                    new CallbackPayloads.StreamCompletePayload(resolvedSid));
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to send message", e);
        }
    }

    /**
     * 处理停止生成请求。
     *
     * @param ctx     共享上下文
     * @param request 停止生成请求
     */
    public void handleStopGeneration(BridgeContext ctx, CommonRequests.StopGenerationRequest request) {
        ctx.chatManager().stopGeneration(request.sessionId());
    }

    /**
     * 解析有效的会话 ID。对于以 "plan-" 前缀开头的会话 ID，从计划数据中查找真实的 CLI 会话 ID。
     *
     * @param ctx       共享上下文
     * @param sessionId 前端传入的会话 ID
     * @return 可直接传递给 CLI 的会话 ID
     */
    private String resolveEffectiveSessionId(BridgeContext ctx, String sessionId) {
        if (StringUtil.isEmpty(sessionId)) {
            return "new-" + System.currentTimeMillis();
        }
        if (sessionId.startsWith("plan-")) {
            String planId = sessionId.substring("plan-".length());
            Plan plan = ctx.planService() != null ? ctx.planService().getPlan(planId) : null;
            if (plan != null && plan.sessionId() != null) {
                return plan.sessionId();
            }
            return "new-" + System.currentTimeMillis();
        }
        return sessionId;
    }

    /**
     * 查找与指定会话 ID 关联的且处于任务拆分状态的计划。
     *
     * @param ctx                共享上下文
     * @param rawSessionId       前端传入的原始会话 ID
     * @param effectiveSessionId 解析后的有效会话 ID
     * @return 匹配的计划，未找到则返回 null
     */
    private Plan findPlanBySessionId(BridgeContext ctx, String rawSessionId, String effectiveSessionId) {
        if (ctx.planService() == null) {
            return null;
        }
        if (rawSessionId != null && rawSessionId.startsWith("plan-")) {
            String planId = rawSessionId.substring("plan-".length());
            Plan plan = ctx.planService().getPlan(planId);
            if (plan != null && plan.status() == PlanStatus.TASK_SPLITTING) {
                return plan;
            }
        }
        if (effectiveSessionId != null) {
            List<Plan> plans = ctx.planService().listPlans();
            for (Plan p : plans) {
                if (p.status() == PlanStatus.TASK_SPLITTING && effectiveSessionId.equals(p.sessionId())) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 尝试从 AI 响应中提取任务列表并创建计划任务。
     *
     * @param ctx               共享上下文
     * @param plan              关联的计划
     * @param fullResponse      AI 完整响应文本
     * @param resolvedSessionId 解析后的会话 ID
     */
    private void tryExtractAndCreateTasks(BridgeContext ctx, Plan plan, String fullResponse, String resolvedSessionId) {
        String tasksJson = this.extractTaskListJson(fullResponse);
        if (tasksJson == null) {
            return;
        }
        log.info("[PLAN] extracted tasks from response, planId={}", plan.planId());
        List<PlanTask> parsed = ctx.planService().parseAndCreateTasks(plan.planId(), tasksJson);
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        ctx.invokeJSCallback(JsCallback.PLAN_SPLIT_RESULT, GsonUtils.toJson(Map.of(
                "planId", plan.planId(),
                "tasks", parsed
        )));
    }

    /**
     * 打印 AI 响应事件的日志摘要。
     *
     * @param response AI 响应事件
     */
    private void logAIResponse(AIResponse response) {
        if (!log.isDebugEnabled()) {
            return;
        }
        ResponseType type = response.type();
        String sid = response.sessionId();
        switch (type) {
            case STEP_START -> log.debug("[AI] STEP_START  | session: {}", this.shortSid(sid));
            case MESSAGE -> {
                MessageContent msg = response.message();
                if (msg != null) {
                    log.debug("[AI] MESSAGE      | {} | {}", msg.messageType(), this.truncate(msg.text(), 80));
                }
            }
            case TOOL_USE -> {
                ToolCallContent tool = response.toolCall();
                if (tool != null) {
                    log.debug("[AI] TOOL_USE     | {} | {} | {}", tool.toolName(), tool.status(), this.truncate(tool.input(), 60));
                }
            }
            case STEP_FINISH -> log.debug("[AI] STEP_FINISH  | session: {}", this.shortSid(sid));
            case ERROR -> log.debug("[AI] ERROR        | {}", this.truncate(response.error() != null ? response.error().message() : "unknown", 120));
            case RETRY_STATUS -> log.debug("[AI] RETRY_STATUS | attempt: {}/{}", response.retryStatus().currentAttempt(), response.retryStatus().maxAttempts());
            case COMPACT -> {
                String compactReason = response.compact() != null ? response.compact().reason() : "unknown";
                log.debug("[AI] COMPACT      | session: {} | reason: {}", this.shortSid(sid), this.truncate(compactReason, 80));
            }
        }
    }

    /**
     * 截断过长的文本用于日志输出。
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String flattened = text.replace('\n', ' ').replace('\r', ' ');
        return flattened.length() > maxLength ? flattened.substring(0, maxLength) + "..." : flattened;
    }

    /**
     * 截断会话 ID 用于日志输出。
     *
     * @param sessionId 完整会话 ID
     * @return 截断后的会话 ID
     */
    private String shortSid(String sessionId) {
        if (sessionId == null) {
            return "N/A";
        }
        return sessionId.length() > 12 ? sessionId.substring(0, 12) + "..." : sessionId;
    }

    /**
     * 持久化当前会话 ID 和 CLI 类型。
     *
     * @param ctx       共享上下文
     * @param sessionId 会话 ID
     * @param cliType   CLI 类型名称
     */
    private void persistCurrentSession(BridgeContext ctx, String sessionId, String cliType) {
        ctx.setCurrentSessionId(sessionId);
        ctx.getAppState().setCurrentCliType(cliType);
    }

    /**
     * 从 AI 响应文本中提取任务列表 JSON。
     *
     * @param text AI 响应文本
     * @return 提取到的 JSON 字符串，未找到则返回 null
     */
    private String extractTaskListJson(String text) {
        if (text == null) {
            return null;
        }
        int startMarker = text.indexOf("---TASK_LIST_START---");
        int endMarker = text.indexOf("---TASK_LIST_END---");
        if (startMarker < 0 || endMarker < 0 || endMarker <= startMarker) {
            int startBracket = text.indexOf("[");
            int endBracket = text.lastIndexOf("]");
            if (startBracket >= 0 && endBracket > startBracket) {
                return text.substring(startBracket, endBracket + 1);
            }
            return null;
        }
        String json = text.substring(startMarker + "---TASK_LIST_START---".length(), endMarker).trim();
        if (json.startsWith("[") && json.endsWith("]")) {
            return json;
        }
        int startBracket = json.indexOf("[");
        int endBracket = json.lastIndexOf("]");
        if (startBracket >= 0 && endBracket > startBracket) {
            return json.substring(startBracket, endBracket + 1);
        }
        return null;
    }
}
