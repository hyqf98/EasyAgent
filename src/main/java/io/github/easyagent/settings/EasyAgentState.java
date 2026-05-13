package io.github.easyagent.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * EasyAgent 项目级别持久化状态。
 * <p>
 * 仅保存项目特定的运行时数据：当前会话 ID、待发送队列和文件编辑快照。
 * 全局配置（CLI 类型、重试策略、模型、配置档案）已迁移到 {@link EasyAgentAppState}。
 * </p>
 *
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Data
@State(name = "EasyAgent", storages = @Storage("easyagent.xml"))
public class EasyAgentState implements PersistentStateComponent<EasyAgentState> {

    /** 当前活跃的会话 ID。 */
    private String currentSessionId;

    /** 按会话 ID 存储的待发送队列：sessionId -> JSON 数组字符串。 */
    private Map<String, String> pendingQueues = new HashMap<>();

    /** 按编辑 ID 存储的 AI 文件编辑快照：editId -> JSON 字符串。 */
    private Map<String, String> fileEditSnapshots = new HashMap<>();

    /** 面板布局 JSON 字符串，持久化多面板分屏状态。 */
    private String paneLayoutJson;

    /**
     * 获取项目级别的 EasyAgentState 实例。
     *
     * @param project 当前 IDEA 项目
     * @return 项目级别的状态实例
     */
    public static EasyAgentState getInstance(@NotNull Project project) {
        return project.getService(EasyAgentState.class);
    }

    @Override
    public @Nullable EasyAgentState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull EasyAgentState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    /**
     * 保存指定会话的待发送队列。
     *
     * @param sessionId        会话 ID
     * @param pendingQueueJson 待发送队列的 JSON 数组字符串
     */
    public void savePendingQueue(String sessionId,
                                 String pendingQueueJson) {
        if (sessionId == null) {
            return;
        }
        if (pendingQueueJson == null || "[]".equals(pendingQueueJson)) {
            this.pendingQueues.remove(sessionId);
        } else {
            this.pendingQueues.put(sessionId, pendingQueueJson);
        }
    }

    /**
     * 获取指定会话的待发送队列 JSON。
     *
     * @param sessionId 会话 ID
     * @return 待发送队列的 JSON 数组字符串，不存在时返回 null
     */
    public String getPendingQueue(String sessionId) {
        return sessionId != null ? this.pendingQueues.get(sessionId) : null;
    }

    /**
     * 保存文件编辑快照。
     *
     * @param editId       编辑 ID
     * @param snapshotJson 快照 JSON 字符串
     */
    public synchronized void saveFileEditSnapshot(String editId,
                                                  String snapshotJson) {
        if (editId == null || editId.isBlank()) {
            return;
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            this.fileEditSnapshots.remove(editId);
        } else {
            this.fileEditSnapshots.put(editId, snapshotJson);
        }
    }

    /**
     * 获取文件编辑快照 JSON。
     *
     * @param editId 编辑 ID
     * @return 快照 JSON；不存在时返回 {@code null}
     */
    public synchronized String getFileEditSnapshot(String editId) {
        return editId != null ? this.fileEditSnapshots.get(editId) : null;
    }

    /**
     * 获取全部文件编辑快照。
     *
     * @return 文件编辑快照映射
     */
    public synchronized Map<String, String> getFileEditSnapshots() {
        return this.fileEditSnapshots;
    }
}
