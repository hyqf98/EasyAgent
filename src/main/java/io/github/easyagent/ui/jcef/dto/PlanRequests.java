package io.github.easyagent.ui.jcef.dto;

/**
 * Plan 看板相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class PlanRequests {

    private PlanRequests() {
    }

    /**
     * 创建计划请求。
     *
     * @param action      动作名称
     * @param planName    计划名称
     * @param description 计划描述
     * @param cliType     CLI 类型
     * @param minTaskCount 最小任务数
     */
    public record CreatePlanRequest(String action, String planName, String description,
                                      String cliType, int minTaskCount) implements JsRequest {
    }

    /**
     * Plan ID 请求。
     *
     * @param action 动作名称
     * @param planId 计划 ID
     */
    public record PlanIdRequest(String action, String planId) implements JsRequest {
    }

    /**
     * 更新计划请求。
     *
     * @param action      动作名称
     * @param planId      计划 ID
     * @param planName    计划名称
     * @param description 计划描述
     */
    public record UpdatePlanRequest(String action, String planId,
                                      String planName, String description) implements JsRequest {
    }

    /**
     * 更新计划任务请求。
     *
     * @param action      动作名称
     * @param planId      计划 ID
     * @param taskId      任务 ID
     * @param title       任务标题
     * @param description 任务描述
     * @param priority    优先级
     * @param cliType     CLI 类型
     * @param modelId     模型 ID
     * @param status      状态
     * @param completedAt 完成时间戳
     * @param sortOrder   排序序号
     */
    public record UpdatePlanTaskRequest(String action, String planId, String taskId,
                                          String title, String description, String priority,
                                          String cliType, String modelId, String status,
                                          Long completedAt, Long sortOrder) implements JsRequest {
    }

    /**
     * 执行计划任务请求。
     *
     * @param action 动作名称
     * @param planId 计划 ID
     * @param taskId 任务 ID
     */
    public record ExecutePlanTaskRequest(String action, String planId,
                                           String taskId) implements JsRequest {
    }

    /**
     * 停止计划任务请求。
     *
     * @param action 动作名称
     * @param planId 计划 ID
     * @param taskId 任务 ID
     */
    public record StopPlanTaskRequest(String action, String planId,
                                        String taskId) implements JsRequest {
    }

    /**
     * AI 编辑任务请求。
     *
     * @param action     动作名称
     * @param planId     计划 ID
     * @param instruction 编辑指令
     */
    public record AiEditTasksRequest(String action, String planId,
                                       String instruction) implements JsRequest {
    }

    /**
     * 保存计划任务请求。
     *
     * @param action   动作名称
     * @param planId   计划 ID
     * @param tasksJson 任务列表 JSON
     */
    public record SavePlanTasksRequest(String action, String planId,
                                         String tasksJson) implements JsRequest {
    }

    /**
     * 保存计划配置请求。
     *
     * @param action             动作名称
     * @param planConcurrentTasks 并发任务数
     */
    public record SavePlanConfigRequest(String action, int planConcurrentTasks) implements JsRequest {
    }
}
