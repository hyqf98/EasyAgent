package io.github.easyagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * EasyAgent 应用级别持久化状态。
 * <p>
 * 保存跨项目共享的全局配置：CLI 类型、AI 重试策略、模型配置、CLI 配置档案。
 * 使用 IntelliJ {@link PersistentStateComponent} 机制自动持久化到应用级存储。
 * 所有 IDEA 窗口和项目共享同一份配置，无需在每个项目中重复配置。
 * </p>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Data
@State(name = "EasyAgentApp", storages = @Storage("easyagent-app.xml"))
public class EasyAgentAppState implements PersistentStateComponent<EasyAgentAppState> {

    /** 当前活跃的 CLI 类型名称。 */
    private String currentCliType;

    /** AI 重试最大次数，默认 5 次。 */
    private int retryMaxCount = 5;

    /** AI 单次执行超时时间（毫秒），默认 10 分钟，0 表示不超时。 */
    private long retryTimeoutMs = 600000;

    /** 模型配置 JSON 字符串，用于持久化。 */
    private String modelsJson;

    /** CLI 配置档案：cliType -> JSON 数组字符串（List<CliProfile>）。 */
    private Map<String, String> cliProfiles = new HashMap<>();

    /**
     * 获取应用级别的 EasyAgentAppState 实例。
     *
     * @return 应用级别的全局状态实例
     */
    public static EasyAgentAppState getInstance() {
        return ApplicationManager.getApplication().getService(EasyAgentAppState.class);
    }

    @Override
    public @Nullable EasyAgentAppState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull EasyAgentAppState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    /**
     * 判断重试是否启用。
     *
     * @return 最大重试次数大于 0 时返回 true
     */
    public boolean isRetryEnabled() {
        return this.retryMaxCount > 0;
    }
}
