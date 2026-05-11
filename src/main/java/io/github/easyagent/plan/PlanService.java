package io.github.easyagent.plan;

import com.intellij.openapi.project.Project;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.PlanStatus;
import io.github.easyagent.enums.TaskPriority;
import io.github.easyagent.enums.TaskStatus;
import io.github.easyagent.plan.entity.Plan;
import io.github.easyagent.plan.entity.PlanTask;
import io.github.easyagent.settings.EasyAgentPlanState;
import io.github.easyagent.util.GsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 计划模式管理服务。
 * <p>
 * 提供计划的 CRUD 操作、任务管理和状态转换等核心业务逻辑。
 * 所有数据通过 {@link EasyAgentPlanState} 持久化到项目级配置。
 * </p>
 *
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
public class PlanService {

    /** 单个计划最大任务数。 */
    private static final int MAX_TASKS_PER_PLAN = 50;

    private final EasyAgentPlanState planState;
    private final String projectId;

    /**
     * 构造计划服务。
     *
     * @param project 当前项目
     */
    public PlanService(Project project) {
        this.planState = EasyAgentPlanState.getInstance(project);
        this.projectId = project.getBasePath();
    }

    /**
     * 创建新计划。
     *
     * @param planName     计划名称
     * @param description  描述
     * @param cliType      CLI 类型
     * @param minTaskCount 最小任务数
     * @return 新创建的计划
     */
    public Plan createPlan(String planName, String description, CLIType cliType, int minTaskCount) {
        return this.planState.createPlan(this.projectId, planName, description, cliType, minTaskCount);
    }

    /**
     * 获取项目下的所有计划列表。
     *
     * @return 计划列表
     */
    public List<Plan> listPlans() {
        return this.planState.listPlans(this.projectId);
    }

    /**
     * 获取计划详情。
     *
     * @param planId 计划 ID
     * @return 计划对象
     */
    public Plan getPlan(String planId) {
        return this.planState.getPlan(planId);
    }

    /**
     * 获取计划的任务列表。
     *
     * @param planId 计划 ID
     * @return 任务列表
     */
    public List<PlanTask> getTasks(String planId) {
        return this.planState.getTasks(planId);
    }

    /**
     * 更新计划信息。
     *
     * @param plan 计划对象
     */
    public void updatePlan(Plan plan) {
        this.planState.updatePlan(plan);
    }

    /**
     * 删除计划。
     * <p>
     * 删除前会检查是否有执行中的任务，如果有则拒绝删除。
     * </p>
     *
     * @param planId 计划 ID
     * @return 是否删除成功
     */
    public boolean deletePlan(String planId) {
        if (this.planState.getRunningTaskCount(planId) > 0) {
            return false;
        }
        this.planState.deletePlan(planId);
        return true;
    }

    /**
     * 批量保存任务列表（任务拆分完成后调用）。
     *
     * @param planId 计划 ID
     * @param tasks  任务列表
     * @return 是否保存成功
     */
    public boolean saveTasks(String planId, List<PlanTask> tasks) {
        Plan plan = this.planState.getPlan(planId);
        if (plan == null) {
            return false;
        }
        if (tasks.size() > MAX_TASKS_PER_PLAN) {
            return false;
        }
        this.planState.saveTasks(planId, tasks);
        this.planState.updatePlan(Plan.builder()
                .planId(plan.planId())
                .projectId(plan.projectId())
                .planName(plan.planName())
                .description(plan.description())
                .cliType(plan.cliType())
                .sessionId(plan.sessionId())
                .minTaskCount(plan.minTaskCount())
                .status(PlanStatus.KANBAN)
                .createdAt(plan.createdAt())
                .updatedAt(System.currentTimeMillis())
                .build());
        return true;
    }

    /**
     * 更新单个任务。
     *
     * @param planId 计划 ID
     * @param task   任务对象
     * @return 是否更新成功
     */
    public boolean updateTask(String planId, PlanTask task) {
        List<PlanTask> tasks = this.planState.getTasks(planId);
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).taskId().equals(task.taskId())) {
                tasks.set(i, task);
                this.planState.saveTasks(planId, tasks);
                return true;
            }
        }
        return false;
    }

    /**
     * 更新任务状态。
     *
     * @param planId   计划 ID
     * @param taskId   任务 ID
     * @param newStatus 新状态
     * @return 更新后的任务，失败返回 null
     */
    public PlanTask updateTaskStatus(String planId, String taskId, TaskStatus newStatus) {
        List<PlanTask> tasks = this.planState.getTasks(planId);
        for (int i = 0; i < tasks.size(); i++) {
            PlanTask existing = tasks.get(i);
            if (existing.taskId().equals(taskId)) {
                PlanTask updated = PlanTask.builder()
                        .taskId(existing.taskId())
                        .planId(existing.planId())
                        .title(existing.title())
                        .description(existing.description())
                        .priority(existing.priority())
                        .status(newStatus)
                        .cliType(existing.cliType())
                        .modelId(existing.modelId())
                        .executeSessionId(existing.executeSessionId())
                        .executePrompt(existing.executePrompt())
                        .sortOrder(existing.sortOrder())
                        .startedAt(newStatus == TaskStatus.RUNNING ? System.currentTimeMillis() : (existing.startedAt() != null ? existing.startedAt() : 0L))
                        .completedAt(isTerminalStatus(newStatus) ? System.currentTimeMillis() : (existing.completedAt() != null ? existing.completedAt() : 0L))
                        .build();
                tasks.set(i, updated);
                this.planState.saveTasks(planId, tasks);
                return updated;
            }
        }
        return null;
    }

    private boolean isTerminalStatus(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    /**
     * 检查是否可以启动新任务（并发数限制）。
     *
     * @param planId        计划 ID
     * @param maxConcurrent 最大并发数
     * @return 是否可以启动
     */
    public boolean canStartTask(String planId, int maxConcurrent) {
        return this.planState.getRunningTaskCount(planId) < maxConcurrent;
    }

    /**
     * 获取任务进度统计。
     *
     * @param planId 计划 ID
     * @return 统计映射
     */
    public Map<String, Integer> getTaskStats(String planId) {
        return this.planState.getTaskStats(planId);
    }

    /**
     * 从 AI 输出的 JSON 数组中解析并创建任务列表。
     *
     * @param planId 计划 ID
     * @param tasksJson AI 输出的任务 JSON 数组
     * @return 创建的任务列表
     */
    public List<PlanTask> parseAndCreateTasks(String planId, String tasksJson) {
        List<Map<String, Object>> rawTasks = GsonUtils.fromJson(tasksJson,
                new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>() {}.getType());
        if (rawTasks == null) {
            return new ArrayList<>();
        }

        Plan plan = this.planState.getPlan(planId);
        CLIType defaultCli = plan != null ? plan.cliType() : CLIType.CLAUDE;

        List<PlanTask> tasks = new ArrayList<>();
        for (int i = 0; i < rawTasks.size() && i < MAX_TASKS_PER_PLAN; i++) {
            Map<String, Object> raw = rawTasks.get(i);
            String priorityStr = raw.get("priority") != null ? raw.get("priority").toString().toUpperCase() : "MEDIUM";
            TaskPriority priority;
            try {
                priority = TaskPriority.valueOf(priorityStr);
            } catch (IllegalArgumentException e) {
                priority = TaskPriority.MEDIUM;
            }

            tasks.add(PlanTask.builder()
                    .taskId(UUID.randomUUID().toString())
                    .planId(planId)
                    .title(raw.get("title") != null ? raw.get("title").toString() : "Task " + (i + 1))
                    .description(raw.get("description") != null ? raw.get("description").toString() : "")
                    .priority(priority)
                    .status(TaskStatus.PENDING)
                    .cliType(defaultCli)
                    .modelId(null)
                    .executeSessionId(null)
                    .executePrompt(null)
                    .sortOrder(i)
                    .startedAt(null)
                    .completedAt(null)
                    .build());
        }

        this.planState.saveTasks(planId, tasks);
        return tasks;
    }

    /**
     * 构建需求收集阶段的系统提示词。
     *
     * @param plan 计划对象
     * @return 系统提示词
     */
    public String buildRequirementPrompt(Plan plan) {
        return """
                你是一个项目规划专家。用户想创建一个开发计划，请通过对话深入了解需求。

                ## 用户计划
                名称：%s
                描述：%s
                期望拆分任务数：%d

                ## 你的任务
                1. 分析用户描述，识别不明确的地方
                2. 主动提问收集缺失信息（技术栈、约束条件、优先级、验收标准等）
                3. 当你认为需求已经足够清晰时，输出以下格式的任务列表：

                ---TASK_LIST_START---
                [{"title":"任务标题","description":"详细描述","priority":"high|medium|low"}]
                ---TASK_LIST_END---

                注意：只有在你认为需求已完全明确后才输出任务列表，确保至少拆分 %d 个任务。
                """.formatted(plan.planName(), plan.description(), plan.minTaskCount(), plan.minTaskCount());
    }

    /**
     * 构建任务执行提示词。
     *
     * @param task 任务对象
     * @return 执行提示词
     */
    public String buildTaskExecutionPrompt(PlanTask task) {
        return """
                你是一个任务执行专家。请执行以下任务：

                ## 任务信息
                - 标题：%s
                - 描述：%s
                - 优先级：%s

                ## 执行要求
                1. 严格按照任务描述执行
                2. 完成后汇报执行结果
                3. 如果遇到问题，说明失败原因
                """.formatted(task.title(), task.description(), task.priority().getValue());
    }

    /**
     * 构建 AI 编辑任务的提示词。
     *
     * @param currentTasks 当前任务列表 JSON
     * @param userInstruction 用户编辑指令
     * @return 编辑提示词
     */
    public String buildTaskEditPrompt(String currentTasks, String userInstruction) {
        return """
                你是一个任务管理专家。用户希望修改任务列表，请根据用户指令进行调整。

                ## 当前任务列表
                %s

                ## 用户指令
                %s

                请输出修改后的完整任务列表（JSON 数组格式），保持原有任务 ID 不变（如果有）：
                [{"title":"任务标题","description":"详细描述","priority":"high|medium|low"}]
                """.formatted(currentTasks, userInstruction);
    }
}
