package io.github.easyagent.ui.jcef.handler;

import com.google.gson.reflect.TypeToken;
import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.PlanStatus;
import io.github.easyagent.enums.ValueEnum;
import io.github.easyagent.plan.entity.Plan;
import io.github.easyagent.plan.entity.PlanTask;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.CommonRequests;
import io.github.easyagent.ui.jcef.dto.PlanRequests;
import io.github.easyagent.ui.service.MessageConverter;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan 看板 handler，负责计划 CRUD、任务执行、任务拆分等所有计划相关操作。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
@Slf4j
public class PlanHandler implements MessageHandler {

    private static final String JS_EMPTY_ARRAY = "[]";

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.CREATE_PLAN, PlanRequests.CreatePlanRequest.class,
                request -> this.handleCreatePlan(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_PLANS, CommonRequests.ActionRequest.class,
                request -> this.handleListPlans(ctx));
        ctx.registerHandler(handlers, JsAction.GET_PLAN_DETAIL, PlanRequests.PlanIdRequest.class,
                request -> this.handleGetPlanDetail(ctx, request));
        ctx.registerHandler(handlers, JsAction.UPDATE_PLAN, PlanRequests.UpdatePlanRequest.class,
                request -> this.handleUpdatePlan(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_PLAN, PlanRequests.PlanIdRequest.class,
                request -> this.handleDeletePlan(ctx, request));
        ctx.registerHandler(handlers, JsAction.UPDATE_PLAN_TASK, PlanRequests.UpdatePlanTaskRequest.class,
                request -> this.handleUpdatePlanTask(ctx, request));
        ctx.registerHandler(handlers, JsAction.EXECUTE_PLAN_TASK, PlanRequests.ExecutePlanTaskRequest.class,
                request -> this.handleExecutePlanTask(ctx, request));
        ctx.registerHandler(handlers, JsAction.STOP_PLAN_TASK, PlanRequests.StopPlanTaskRequest.class,
                request -> this.handleStopPlanTask(ctx, request));
        ctx.registerHandler(handlers, JsAction.AI_EDIT_TASKS, PlanRequests.AiEditTasksRequest.class,
                request -> this.handleAiEditTasks(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_PLAN_TASKS, PlanRequests.SavePlanTasksRequest.class,
                request -> this.handleSavePlanTasks(ctx, request));
        ctx.registerHandler(handlers, JsAction.GET_PLAN_CONFIG, CommonRequests.ActionRequest.class,
                request -> this.handleGetPlanConfig(ctx));
        ctx.registerHandler(handlers, JsAction.SAVE_PLAN_CONFIG, PlanRequests.SavePlanConfigRequest.class,
                request -> this.handleSavePlanConfig(ctx, request));
        ctx.registerHandler(handlers, JsAction.START_PLAN_SPLIT, PlanRequests.PlanIdRequest.class,
                request -> this.handleStartPlanSplit(ctx, request));
    }

    private void handleCreatePlan(BridgeContext ctx, PlanRequests.CreatePlanRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                CLIType cliType = ValueEnum.fromValue(CLIType.class, request.cliType());
                if (cliType == null) {
                    log.warn("[PLAN] Unknown cliType '{}', falling back to CLAUDE", request.cliType());
                    cliType = CLIType.CLAUDE;
                }
                Plan plan = ctx.planService().createPlan(
                        request.planName(), request.description(), cliType, request.minTaskCount());
                ctx.invokeJSCallback(JsCallback.PLAN_CREATED, GsonUtils.toJson(plan));
            } catch (Exception e) {
                log.warn("Failed to create plan", e);
            }
        });
    }

    private void handleListPlans(BridgeContext ctx) {
        if (ctx.planService() == null) {
            ctx.invokeJSCallback(JsCallback.PLAN_LIST, JS_EMPTY_ARRAY);
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                List<Plan> plans = ctx.planService().listPlans();
                ctx.invokeJSCallback(JsCallback.PLAN_LIST, GsonUtils.toJson(plans));
            } catch (Exception e) {
                log.warn("Failed to list plans", e);
                ctx.invokeJSCallback(JsCallback.PLAN_LIST, JS_EMPTY_ARRAY);
            }
        });
    }

    private void handleGetPlanDetail(BridgeContext ctx, PlanRequests.PlanIdRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                Plan plan = ctx.planService().getPlan(request.planId());
                log.info("[PLAN-DEBUG] getPlanDetail: planId={}, status={}, sessionId={}",
                        request.planId(),
                        plan != null ? plan.status() : "NULL",
                        plan != null ? plan.sessionId() : "NULL");
                List<PlanTask> tasks = ctx.planService().getTasks(request.planId());
                Map<String, Integer> stats = ctx.planService().getTaskStats(request.planId());
                Map<String, Object> detail = Map.of(
                        "plan", plan != null ? GsonUtils.toJsonTree(plan) : "",
                        "tasks", GsonUtils.toJsonTree(tasks),
                        "stats", stats
                );
                ctx.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(detail));
            } catch (Exception e) {
                log.warn("Failed to get plan detail", e);
            }
        });
    }

    private void handleUpdatePlan(BridgeContext ctx, PlanRequests.UpdatePlanRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                Plan existing = ctx.planService().getPlan(request.planId());
                if (existing == null) {
                    return;
                }
                Plan updated = Plan.builder()
                        .planId(existing.planId())
                        .projectId(existing.projectId())
                        .planName(request.planName() != null ? request.planName() : existing.planName())
                        .description(request.description() != null ? request.description() : existing.description())
                        .cliType(existing.cliType())
                        .sessionId(existing.sessionId())
                        .minTaskCount(existing.minTaskCount())
                        .executionOverview(existing.executionOverview())
                        .status(existing.status())
                        .createdAt(existing.createdAt())
                        .updatedAt(System.currentTimeMillis())
                        .build();
                ctx.planService().updatePlan(updated);
                ctx.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(updated),
                        "tasks", GsonUtils.toJsonTree(ctx.planService().getTasks(request.planId())),
                        "stats", ctx.planService().getTaskStats(request.planId())
                )));
            } catch (Exception e) {
                log.warn("Failed to update plan", e);
            }
        });
    }

    private void handleDeletePlan(BridgeContext ctx, PlanRequests.PlanIdRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                boolean success = ctx.planService().deletePlan(request.planId());
                ctx.invokeJSCallback(JsCallback.PLAN_DELETED, GsonUtils.toJson(Map.of(
                        "planId", request.planId(), "success", success
                )));
            } catch (Exception e) {
                log.warn("Failed to delete plan", e);
            }
        });
    }

    private void handleUpdatePlanTask(BridgeContext ctx, PlanRequests.UpdatePlanTaskRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                PlanTask existing = null;
                List<PlanTask> tasks = ctx.planService().getTasks(request.planId());
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        existing = t;
                        break;
                    }
                }
                if (existing == null) {
                    return;
                }
                io.github.easyagent.enums.TaskStatus newStatus = request.status() != null
                        ? ValueEnum.fromValue(io.github.easyagent.enums.TaskStatus.class, request.status())
                        : existing.status();
                boolean statusChanged = newStatus != existing.status();
                long startedAt = existing.startedAt() != null ? existing.startedAt() : 0L;
                long completedAt = existing.completedAt() != null ? existing.completedAt() : 0L;
                if (statusChanged && newStatus == io.github.easyagent.enums.TaskStatus.RUNNING) {
                    startedAt = System.currentTimeMillis();
                }
                if (statusChanged && (newStatus == io.github.easyagent.enums.TaskStatus.COMPLETED
                        || newStatus == io.github.easyagent.enums.TaskStatus.FAILED)) {
                    completedAt = request.completedAt() != null ? request.completedAt() : System.currentTimeMillis();
                }
                if (statusChanged && (newStatus == io.github.easyagent.enums.TaskStatus.PENDING
                        || newStatus == io.github.easyagent.enums.TaskStatus.STOPPED)) {
                    completedAt = 0L;
                }
                PlanTask updated = PlanTask.builder()
                        .taskId(existing.taskId())
                        .planId(existing.planId())
                        .title(request.title() != null ? request.title() : existing.title())
                        .description(request.description() != null ? request.description() : existing.description())
                        .priority(request.priority() != null ? ValueEnum.fromValue(
                                io.github.easyagent.enums.TaskPriority.class, request.priority()) : existing.priority())
                        .status(newStatus)
                        .cliType(request.cliType() != null ? ValueEnum.fromValue(
                                CLIType.class, request.cliType()) : existing.cliType())
                        .modelId(request.modelId() != null ? request.modelId() : existing.modelId())
                        .executeSessionId(existing.executeSessionId())
                        .executePrompt(existing.executePrompt())
                        .sortOrder(request.sortOrder() != null ? request.sortOrder().intValue() : existing.sortOrder())
                        .startedAt(startedAt)
                        .completedAt(completedAt)
                        .build();
                ctx.planService().updateTask(request.planId(), updated);
                ctx.invokeJSCallback(JsCallback.PLAN_TASK_UPDATED, GsonUtils.toJson(updated));
            } catch (Exception e) {
                log.warn("Failed to update plan task", e);
            }
        });
    }

    private void handleExecutePlanTask(BridgeContext ctx, PlanRequests.ExecutePlanTaskRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                log.info("[PLAN-DEBUG] executePlanTask: planId={}, taskId={}", request.planId(), request.taskId());
                int maxConcurrent = ctx.getAppState().getPlanConcurrentTasks();

                PlanTask targetTask = null;
                List<PlanTask> tasks = ctx.planService().getTasks(request.planId());
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        targetTask = t;
                        break;
                    }
                }
                if (targetTask == null) {
                    log.warn("[PLAN-DEBUG] executePlanTask: task not found, taskId={}", request.taskId());
                    return;
                }

                int runningCount = (int) tasks.stream()
                        .filter(t -> t.status() == io.github.easyagent.enums.TaskStatus.RUNNING
                                && !t.taskId().equals(request.taskId()))
                        .count();
                if (runningCount >= maxConcurrent) {
                    ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(Map.of(
                            "taskId", request.taskId(),
                            "status", "REJECTED",
                            "reason", "concurrent_limit"
                    )));
                    return;
                }

                Plan plan = ctx.planService().getPlan(request.planId());
                CLIType cliType = targetTask.cliType() != null ? targetTask.cliType() :
                        (plan != null ? plan.cliType() : CLIType.CLAUDE);

                boolean hasExistingSession = targetTask.executeSessionId() != null;
                String sessionId = hasExistingSession
                        ? targetTask.executeSessionId()
                        : "new-" + System.currentTimeMillis();
                String executionOverview = plan != null ? plan.executionOverview() : null;
                String prompt;
                if (hasExistingSession) {
                    prompt = "请重新执行以下任务：\n\n"
                            + "## 任务\n- 标题：" + targetTask.title() + "\n"
                            + (targetTask.description() != null && !targetTask.description().isBlank()
                            ? "- 描述：" + targetTask.description() + "\n" : "")
                            + "\n请严格按照任务描述执行，完成后汇报执行结果。";
                } else {
                    prompt = ctx.planService().buildTaskExecutionPrompt(targetTask, executionOverview);
                }

                PlanTask updated = ctx.planService().updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.RUNNING);
                if (updated != null) {
                    PlanTask withSession = PlanTask.builder()
                            .taskId(updated.taskId())
                            .planId(updated.planId())
                            .title(updated.title())
                            .description(updated.description())
                            .priority(updated.priority())
                            .status(updated.status())
                            .cliType(cliType)
                            .modelId(updated.modelId())
                            .executeSessionId(sessionId)
                            .executePrompt(prompt)
                            .executionSummary(updated.executionSummary())
                            .sortOrder(updated.sortOrder())
                            .startedAt(updated.startedAt())
                            .completedAt(updated.completedAt())
                            .build();
                    ctx.planService().updateTask(request.planId(), withSession);
                    ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(withSession));
                } else {
                    log.warn("[PLAN-DEBUG] executePlanTask: updateTaskStatus returned null, taskId={}", request.taskId());
                }

                String planId = request.planId();
                String taskId = request.taskId();
                final PlanTask taskRef = targetTask;
                final StringBuilder taskOutputBuilder = new StringBuilder();

                log.info("[PLAN-DEBUG] executePlanTask: calling sendMessage, planId={}, taskId={}, sessionId={}, cliType={}",
                        planId, taskId, sessionId, cliType);
                ctx.chatManager().sendMessage(prompt, sessionId, cliType, ctx.getProjectPath(),
                        targetTask.modelId(), null, false, new StreamEventListener() {
                            private String resolvedSid = sessionId;

                            @Override
                            public void onResponse(AIResponse response) {
                                if (response.sessionId() != null) {
                                    String newSid = response.sessionId();
                                    if (!newSid.equals(resolvedSid)) {
                                        String oldSid = resolvedSid;
                                        resolvedSid = newSid;
                                        ctx.chatManager().remapProviderSessionId(cliType, oldSid, newSid);
                                        ctx.planService().updateTask(planId, PlanTask.builder()
                                                .taskId(taskRef.taskId()).planId(taskRef.planId())
                                                .title(taskRef.title()).description(taskRef.description())
                                                .priority(taskRef.priority())
                                                .status(io.github.easyagent.enums.TaskStatus.RUNNING)
                                                .cliType(cliType).modelId(taskRef.modelId())
                                                .executeSessionId(resolvedSid)
                                                .executePrompt(taskRef.executePrompt())
                                                .executionSummary(taskRef.executionSummary())
                                                .sortOrder(taskRef.sortOrder())
                                                .startedAt(taskRef.startedAt())
                                                .completedAt(taskRef.completedAt())
                                                .build());
                                        log.info("[PLAN-DEBUG] SessionId remapped: {} -> {} for taskId={}", oldSid, newSid, taskId);
                                    }
                                }
                                if (response.message() != null && response.message().text() != null) {
                                    taskOutputBuilder.append(response.message().text());
                                }
                                Map<String, Object> eventMap = new HashMap<>();
                                eventMap.put("type", response.type().getValue());
                                eventMap.put("planId", planId);
                                eventMap.put("taskId", taskId);
                                if (response.message() != null && response.message().text() != null) {
                                    eventMap.put("text", response.message().text());
                                }
                                if (response.message() != null && response.message().messageType() != null) {
                                    eventMap.put("messageType", response.message().messageType().getValue());
                                }
                                ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(eventMap));
                            }

                            @Override
                            public void onComplete() {
                                List<PlanTask> currentTasks = ctx.planService().getTasks(planId);
                                for (PlanTask t : currentTasks) {
                                    if (t.taskId().equals(taskId)) {
                                        if (t.status() == io.github.easyagent.enums.TaskStatus.STOPPED) {
                                            log.info("[PLAN-STOP] Task {} already stopped, skipping onComplete", taskId);
                                            return;
                                        }
                                        break;
                                    }
                                }
                                PlanTask completed = ctx.planService().updateTaskStatus(
                                        planId, taskId, io.github.easyagent.enums.TaskStatus.COMPLETED);
                                if (completed != null) {
                                    ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(completed));
                                }
                                PlanHandler.this.generateTaskOverview(ctx, planId, taskRef, taskOutputBuilder.toString(), true);
                            }

                            @Override
                            public void onError(Exception e) {
                                List<PlanTask> currentTasks = ctx.planService().getTasks(planId);
                                for (PlanTask t : currentTasks) {
                                    if (t.taskId().equals(taskId)) {
                                        if (t.status() == io.github.easyagent.enums.TaskStatus.STOPPED) {
                                            return;
                                        }
                                        break;
                                    }
                                }
                                ctx.planService().updateTaskStatus(
                                        planId, taskId, io.github.easyagent.enums.TaskStatus.FAILED);
                                ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS,
                                        GsonUtils.toJson(Map.of("taskId", taskId, "status", "FAILED",
                                                "error", e.getMessage() != null ? e.getMessage() : "Unknown error")));
                                PlanHandler.this.generateTaskOverview(ctx, planId, taskRef, taskOutputBuilder.toString(), false);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to execute plan task", e);
                ctx.planService().updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.FAILED);
                ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(Map.of(
                        "taskId", request.taskId(), "status", "FAILED",
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                )));
            }
        });
    }

    private void handleStopPlanTask(BridgeContext ctx, PlanRequests.StopPlanTaskRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                List<PlanTask> tasks = ctx.planService().getTasks(request.planId());
                String executeSessionId = null;
                for (PlanTask t : tasks) {
                    if (t.taskId().equals(request.taskId())) {
                        executeSessionId = t.executeSessionId();
                        break;
                    }
                }
                log.info("[PLAN-STOP] stopPlanTask: planId={}, taskId={}, executeSessionId={}",
                        request.planId(), request.taskId(), executeSessionId);

                if (executeSessionId != null) {
                    ctx.chatManager().stopGeneration(executeSessionId);
                    log.info("[PLAN-STOP] Called stopGeneration for session: {}", executeSessionId);
                }

                PlanTask updated = ctx.planService().updateTaskStatus(
                        request.planId(), request.taskId(), io.github.easyagent.enums.TaskStatus.STOPPED);
                if (updated != null) {
                    ctx.invokeJSCallback(JsCallback.PLAN_TASK_STATUS, GsonUtils.toJson(updated));
                }
            } catch (Exception e) {
                log.warn("[PLAN-STOP] Failed to stop plan task: planId={}, taskId={}",
                        request.planId(), request.taskId(), e);
            }
        });
    }

    private void generateTaskOverview(BridgeContext ctx, String planId, PlanTask task, String taskOutput, boolean success) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                Plan plan = ctx.planService().getPlan(planId);
                if (plan == null || plan.sessionId() == null) {
                    String fallbackSummary = PlanHandler.this.buildFallbackSummary(task, taskOutput, success);
                    PlanHandler.this.appendOverview(ctx, planId, plan, fallbackSummary);
                    return;
                }

                String overviewPrompt = ctx.planService().buildOverviewGenerationPrompt(task, taskOutput, success);
                String overviewSessionId = "overview-" + planId + "-" + task.taskId();
                CLIType cliType = plan.cliType();
                StringBuilder overviewBuilder = new StringBuilder();

                ctx.chatManager().sendMessage(overviewPrompt, overviewSessionId, cliType, ctx.getProjectPath(),
                        null, null, false, new StreamEventListener() {
                            @Override
                            public void onResponse(AIResponse response) {
                                if (response.message() != null && response.message().text() != null) {
                                    overviewBuilder.append(response.message().text());
                                }
                            }

                            @Override
                            public void onComplete() {
                                String summary = overviewBuilder.toString().trim();
                                if (summary.isEmpty()) {
                                    summary = PlanHandler.this.buildFallbackSummary(task, taskOutput, success);
                                }
                                PlanHandler.this.appendOverview(ctx, planId, plan, summary);
                            }

                            @Override
                            public void onError(Exception e) {
                                log.warn("Failed to generate task overview, using fallback", e);
                                String fallbackSummary = PlanHandler.this.buildFallbackSummary(task, taskOutput, success);
                                PlanHandler.this.appendOverview(ctx, planId, plan, fallbackSummary);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to generate task overview", e);
            }
        });
    }

    private String buildFallbackSummary(PlanTask task, String taskOutput, boolean success) {
        long duration = 0;
        if (task.startedAt() != null && task.startedAt() > 0) {
            duration = (System.currentTimeMillis() - task.startedAt()) / 1000;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(success ? "✅" : "❌").append(" ").append(task.title()).append("\n");
        sb.append("- 状态: ").append(success ? "成功" : "失败").append("\n");
        sb.append("- 耗时: ").append(duration).append("s\n");
        if (!success && taskOutput != null && !taskOutput.isEmpty()) {
            String snippet = taskOutput.length() > 200 ? taskOutput.substring(taskOutput.length() - 200) : taskOutput;
            sb.append("- 原因: ").append(snippet.replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    private void appendOverview(BridgeContext ctx, String planId, Plan plan, String summary) {
        String existing = plan != null ? plan.executionOverview() : null;
        String newOverview;
        if (existing != null && !existing.isBlank()) {
            newOverview = existing + "\n" + summary;
        } else {
            newOverview = summary;
        }
        if (newOverview.length() > 10000) {
            newOverview = newOverview.substring(newOverview.length() - 10000);
        }
        Plan updatedPlan = Plan.builder()
                .planId(plan.planId())
                .projectId(plan.projectId())
                .planName(plan.planName())
                .description(plan.description())
                .cliType(plan.cliType())
                .sessionId(plan.sessionId())
                .minTaskCount(plan.minTaskCount())
                .executionOverview(newOverview)
                .status(plan.status())
                .createdAt(plan.createdAt())
                .updatedAt(System.currentTimeMillis())
                .build();
        ctx.planService().updatePlan(updatedPlan);
        ctx.invokeJSCallback(JsCallback.PLAN_OVERVIEW_UPDATED, GsonUtils.toJson(Map.of(
                "planId", planId,
                "executionOverview", newOverview
        )));
    }

    private void handleAiEditTasks(BridgeContext ctx, PlanRequests.AiEditTasksRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                List<PlanTask> currentTasks = ctx.planService().getTasks(request.planId());
                String currentJson = GsonUtils.toJson(currentTasks);
                String prompt = ctx.planService().buildTaskEditPrompt(currentJson, request.instruction());

                Plan plan = ctx.planService().getPlan(request.planId());
                CLIType cliType = plan != null ? plan.cliType() : CLIType.CLAUDE;
                String sessionId = "plan-edit-" + request.planId();
                String planId = request.planId();

                StringBuilder responseBuilder = new StringBuilder();
                ctx.chatManager().sendMessage(prompt, sessionId, cliType, ctx.getProjectPath(),
                        null, null, false, new StreamEventListener() {
                            @Override
                            public void onResponse(AIResponse response) {
                                if (response.message() != null && response.message().text() != null) {
                                    responseBuilder.append(response.message().text());
                                }
                            }

                            @Override
                            public void onComplete() {
                                String fullResponse = responseBuilder.toString();
                                int startIdx = fullResponse.indexOf("[");
                                int endIdx = fullResponse.lastIndexOf("]");
                                if (startIdx >= 0 && endIdx > startIdx) {
                                    String tasksJson = fullResponse.substring(startIdx, endIdx + 1);
                                    List<PlanTask> parsed = ctx.planService().parseAndCreateTasks(planId, tasksJson);
                                    ctx.invokeJSCallback(JsCallback.PLAN_TASK_UPDATED, GsonUtils.toJson(Map.of(
                                            "planId", planId, "tasks", parsed
                                    )));
                                }
                            }

                            @Override
                            public void onError(Exception e) {
                                log.warn("Failed to AI edit tasks", e);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to AI edit tasks", e);
            }
        });
    }

    private void handleSavePlanTasks(BridgeContext ctx, PlanRequests.SavePlanTasksRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                List<PlanTask> tasks = GsonUtils.fromJson(request.tasksJson(),
                        new TypeToken<List<PlanTask>>() {}.getType());
                ctx.planService().saveTasks(request.planId(), tasks);
                ctx.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(ctx.planService().getPlan(request.planId())),
                        "tasks", GsonUtils.toJsonTree(ctx.planService().getTasks(request.planId())),
                        "stats", ctx.planService().getTaskStats(request.planId())
                )));
            } catch (Exception e) {
                log.warn("Failed to save plan tasks", e);
            }
        });
    }

    private void handleGetPlanConfig(BridgeContext ctx) {
        ctx.invokeJSCallback(JsCallback.PLAN_CONFIG, GsonUtils.toJson(Map.of(
                "planConcurrentTasks", ctx.getAppState().getPlanConcurrentTasks()
        )));
    }

    private void handleSavePlanConfig(BridgeContext ctx, PlanRequests.SavePlanConfigRequest request) {
        int value = Math.max(1, Math.min(5, request.planConcurrentTasks()));
        ctx.getAppState().setPlanConcurrentTasks(value);
        ctx.invokeJSCallback(JsCallback.PLAN_CONFIG_SAVED, GsonUtils.toJson(Map.of(
                "success", true, "planConcurrentTasks", value
        )));
    }

    private void handleStartPlanSplit(BridgeContext ctx, PlanRequests.PlanIdRequest request) {
        if (ctx.planService() == null) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            try {
                Plan plan = ctx.planService().getPlan(request.planId());
                if (plan == null) {
                    log.warn("[PLAN] startPlanSplit: plan not found, planId={}", request.planId());
                    return;
                }
                log.info("[PLAN] startPlanSplit: planId={}, name={}, cliType={}", request.planId(), plan.planName(), plan.cliType());

                Plan updated = Plan.builder()
                        .planId(plan.planId())
                        .projectId(plan.projectId())
                        .planName(plan.planName())
                        .description(plan.description())
                        .cliType(plan.cliType())
                        .sessionId(plan.sessionId())
                        .minTaskCount(plan.minTaskCount())
                        .executionOverview(plan.executionOverview())
                        .status(PlanStatus.TASK_SPLITTING)
                        .createdAt(plan.createdAt())
                        .updatedAt(System.currentTimeMillis())
                        .build();
                ctx.planService().updatePlan(updated);

                ctx.invokeJSCallback(JsCallback.PLAN_DETAIL, GsonUtils.toJson(Map.of(
                        "plan", GsonUtils.toJsonTree(updated),
                        "tasks", GsonUtils.toJsonTree(ctx.planService().getTasks(request.planId())),
                        "stats", ctx.planService().getTaskStats(request.planId())
                )));

                String prompt = ctx.planService().buildRequirementPrompt(plan);
                String effectiveSessionId = "new-" + System.currentTimeMillis();
                String planId = plan.planId();
                StringBuilder responseBuilder = new StringBuilder();
                String[] resolvedSessionIdHolder = {null};

                ctx.chatManager().sendMessage(prompt, effectiveSessionId, plan.cliType(),
                        ctx.getProjectPath(), null, null, false, new StreamEventListener() {
                            @Override
                            public void onResponse(AIResponse response) {
                                String resolvedSessionId = response.sessionId() != null
                                        ? response.sessionId() : effectiveSessionId;
                                resolvedSessionIdHolder[0] = resolvedSessionId;
                                if (resolvedSessionId != null
                                        && (plan.sessionId() == null || plan.sessionId().startsWith("plan-"))) {
                                    Plan withSession = Plan.builder()
                                            .planId(plan.planId())
                                            .projectId(plan.projectId())
                                            .planName(plan.planName())
                                            .description(plan.description())
                                            .cliType(plan.cliType())
                                            .sessionId(resolvedSessionId)
                                            .minTaskCount(plan.minTaskCount())
                                            .executionOverview(plan.executionOverview())
                                            .status(PlanStatus.TASK_SPLITTING)
                                            .createdAt(plan.createdAt())
                                            .updatedAt(System.currentTimeMillis())
                                            .build();
                                    ctx.planService().updatePlan(withSession);
                                }
                                if (response.message() != null && response.message().text() != null) {
                                    responseBuilder.append(response.message().text());
                                }
                                if (response.toolCall() != null) {
                                    ctx.fileEditService().trackToolCall(resolvedSessionId, response.toolCall());
                                }
                                String eventJson = MessageConverter.toStreamEventJson(response, resolvedSessionId,
                                        ctx.getProjectPath());
                                ctx.invokeJSCallback(JsCallback.STREAM_EVENT, eventJson);
                            }

                            @Override
                            public void onComplete() {
                                String fullResponse = responseBuilder.toString();
                                String finalSessionId = resolvedSessionIdHolder[0] != null
                                        ? resolvedSessionIdHolder[0] : plan.sessionId();
                                String tasksJson = PlanHandler.this.extractTaskListJson(fullResponse);
                                if (tasksJson != null) {
                                    log.info("[PLAN] extracted tasks from split response, planId={}", planId);
                                    List<PlanTask> parsed = ctx.planService().parseAndCreateTasks(planId, tasksJson);
                                    if (parsed != null && !parsed.isEmpty()) {
                                        ctx.invokeJSCallback(JsCallback.PLAN_SPLIT_RESULT, GsonUtils.toJson(Map.of(
                                                "planId", planId, "tasks", parsed
                                        )));
                                    }
                                }
                                String completeSid = finalSessionId != null ? finalSessionId : effectiveSessionId;
                                ctx.invokeJSCallback(JsCallback.STREAM_COMPLETE,
                                        new CallbackPayloads.StreamCompletePayload(completeSid));
                            }

                            @Override
                            public void onError(Exception e) {
                                String errJson = MessageConverter.toErrorJson(e.getMessage(), effectiveSessionId);
                                ctx.invokeJSCallback(JsCallback.STREAM_EVENT, errJson);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to start plan split", e);
            }
        });
    }

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