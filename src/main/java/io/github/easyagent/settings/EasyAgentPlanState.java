package io.github.easyagent.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.PlanStatus;
import io.github.easyagent.enums.TaskPriority;
import io.github.easyagent.enums.TaskStatus;
import io.github.easyagent.plan.entity.Plan;
import io.github.easyagent.plan.entity.PlanTask;
import io.github.easyagent.util.GsonUtils;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 计划模式项目级持久化状态。
 * <p>
 * 存储当前项目下的所有计划和任务数据，通过 IntelliJ 持久化机制自动保存。
 * </p>
 *
 * @author haijun
 * @date 2026/5/11
 * @since 1.0.0
 */
@Data
@State(name = "EasyAgentPlans", storages = @Storage("easyagent-plans.xml"))
public class EasyAgentPlanState implements PersistentStateComponent<EasyAgentPlanState> {

    /** 按 planId 存储计划 JSON：planId -> Plan JSON。 */
    private Map<String, String> plans = new HashMap<>();

    /** 按 planId 存储任务列表 JSON：planId -> List&lt;PlanTask&gt; JSON。 */
    private Map<String, String> planTasks = new HashMap<>();

    /**
     * 获取项目级实例。
     *
     * @param project 当前项目
     * @return 计划状态实例
     */
    public static EasyAgentPlanState getInstance(@NotNull Project project) {
        return project.getService(EasyAgentPlanState.class);
    }

    @Override
    public @Nullable EasyAgentPlanState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull EasyAgentPlanState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    /**
     * 创建新计划。
     *
     * @param projectId    项目路径
     * @param planName     计划名称
     * @param description  描述
     * @param cliType      CLI 类型
     * @param minTaskCount 最小任务数
     * @return 新创建的计划
     */
    public Plan createPlan(String projectId, String planName, String description,
                           CLIType cliType, int minTaskCount) {
        long now = System.currentTimeMillis();
        Plan plan = Plan.builder()
                .planId(UUID.randomUUID().toString())
                .projectId(projectId)
                .planName(planName)
                .description(description)
                .cliType(cliType)
                .minTaskCount(minTaskCount)
                .status(PlanStatus.DRAFT)
                .createdAt(now)
                .updatedAt(now)
                .build();
        this.plans.put(plan.planId(), GsonUtils.toJson(plan));
        this.planTasks.put(plan.planId(), "[]");
        return plan;
    }

    /**
     * 获取指定计划。
     *
     * @param planId 计划 ID
     * @return 计划对象，不存在返回 null
     */
    public Plan getPlan(String planId) {
        String json = this.plans.get(planId);
        if (json == null) {
            return null;
        }
        return GsonUtils.fromJson(json, Plan.class);
    }

    /**
     * 获取项目下所有计划列表。
     *
     * @param projectId 项目路径
     * @return 计划列表
     */
    public List<Plan> listPlans(String projectId) {
        return this.plans.values().stream()
                .map(json -> GsonUtils.fromJson(json, Plan.class))
                .filter(p -> p != null && p.projectId().equals(projectId))
                .sorted((a, b) -> Long.compare(
                        b.updatedAt() != null ? b.updatedAt() : 0,
                        a.updatedAt() != null ? a.updatedAt() : 0))
                .collect(Collectors.toList());
    }

    /**
     * 更新计划。
     *
     * @param plan 计划对象
     */
    public void updatePlan(Plan plan) {
        if (plan == null || plan.planId() == null) {
            return;
        }
        Plan updated = Plan.builder()
                .planId(plan.planId())
                .projectId(plan.projectId())
                .planName(plan.planName())
                .description(plan.description())
                .cliType(plan.cliType())
                .sessionId(plan.sessionId())
                .minTaskCount(plan.minTaskCount())
                .status(plan.status())
                .createdAt(plan.createdAt())
                .updatedAt(System.currentTimeMillis())
                .build();
        this.plans.put(plan.planId(), GsonUtils.toJson(updated));
    }

    /**
     * 删除计划及其所有任务。
     *
     * @param planId 计划 ID
     */
    public void deletePlan(String planId) {
        this.plans.remove(planId);
        this.planTasks.remove(planId);
    }

    /**
     * 获取计划的任务列表。
     *
     * @param planId 计划 ID
     * @return 任务列表
     */
    public List<PlanTask> getTasks(String planId) {
        String json = this.planTasks.get(planId);
        if (json == null) {
            return new ArrayList<>();
        }
        return GsonUtils.listFromJson(json, PlanTask.class);
    }

    /**
     * 保存计划的任务列表。
     *
     * @param planId 计划 ID
     * @param tasks  任务列表
     */
    public void saveTasks(String planId, List<PlanTask> tasks) {
        this.planTasks.put(planId, GsonUtils.toJson(tasks));
    }

    /**
     * 获取指定计划中状态为 RUNNING 的任务数。
     *
     * @param planId 计划 ID
     * @return 执行中任务数量
     */
    public int getRunningTaskCount(String planId) {
        List<PlanTask> tasks = this.getTasks(planId);
        return (int) tasks.stream()
                .filter(t -> t.status() == TaskStatus.RUNNING)
                .count();
    }

    /**
     * 获取指定计划的任务进度统计。
     *
     * @param planId 计划 ID
     * @return 包含 total、completed、running、failed、pending 的统计映射
     */
    public Map<String, Integer> getTaskStats(String planId) {
        List<PlanTask> tasks = this.getTasks(planId);
        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", tasks.size());
        stats.put("completed", (int) tasks.stream().filter(t -> t.status() == TaskStatus.COMPLETED).count());
        stats.put("running", (int) tasks.stream().filter(t -> t.status() == TaskStatus.RUNNING).count());
        stats.put("failed", (int) tasks.stream().filter(t -> t.status() == TaskStatus.FAILED).count());
        stats.put("pending", (int) tasks.stream().filter(t -> t.status() == TaskStatus.PENDING).count());
        return stats;
    }
}
