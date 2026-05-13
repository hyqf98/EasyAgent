package io.github.easyagent.ui.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * JS 桥回调名称枚举。
 * <p>
 * 定义 Java 端通过 {@code window.__ea_on{callbackName}} 推送到前端的
 * 所有回调函数名称，用于 {@code JCEFMessageBridge} 中的事件推送。
 * </p>
 *
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Getter
public enum JsCallback implements ValueEnum<String> {

    /** 主题变更通知。 */
    THEME_CHANGED("ThemeChanged"),

    /** 可用 CLI 列表推送。 */
    AVAILABLE_CLIS("AvailableCLIs"),

    /** 会话列表推送。 */
    SESSION_LIST("SessionList"),

    /** 历史消息加载完成。 */
    HISTORY_LOADED("HistoryLoaded"),

    /** 流式事件推送。 */
    STREAM_EVENT("StreamEvent"),

    /** 流式完成通知。 */
    STREAM_COMPLETE("StreamComplete"),

    /** 持久化状态恢复通知。 */
    STATE_RESTORED("StateRestored"),

    /** 重试策略配置推送。 */
    RETRY_CONFIG("RetryConfig"),

    /** 模型配置列表推送。 */
    MODELS("Models"),

    /** CLI 模型查询结果推送。 */
    CLI_MODELS("CliModels"),

    /** 向输入框插入文件引用。 */
    INSERT_REFERENCES("InsertReferences"),

    /** 文件引用搜索结果。 */
    FILE_REFERENCE_CANDIDATES("FileReferenceCandidates"),

    /** 会话删除完成通知。 */
    SESSIONS_DELETED("SessionsDeleted"),

    /** 斜杠命令列表推送。 */
    SLASH_COMMANDS("SlashCommands"),

    /** 斜杠命令执行结果推送。 */
    SLASH_COMMAND_EXECUTED("SlashCommandExecuted"),

    /** CLI 配置数据推送。 */
    CLI_CONFIGS("CliConfigs"),

    /** CLI 配置保存结果推送。 */
    CLI_CONFIGS_SAVED("CliConfigsSaved"),

    /** MCP 配置列表推送。 */
    MCP_CONFIGS("McpConfigs"),

    /** MCP 配置保存结果推送。 */
    MCP_SAVED("McpSaved"),

    /** MCP 测试连接结果推送。 */
    MCP_TEST_CONNECTED("McpTestConnected"),

    /** MCP 工具列表推送。 */
    MCP_TOOLS("McpTools"),

    /** MCP 工具调用结果推送。 */
    MCP_TOOL_RESULT("McpToolResult"),

    /** Skills 技能列表推送。 */
    SKILLS("Skills"),

    /** Skill 安装结果推送。 */
    SKILL_INSTALLED("SkillInstalled"),

    /** Skill 删除结果推送。 */
    SKILL_DELETED("SkillDeleted"),

    /** Skill 详情内容推送。 */
    SKILL_CONTENT("SkillContent"),

    /** 已知 GitHub 仓库列表推送（Skills）。 */
    KNOWN_REPOS("KnownRepos"),

    /** 远程 Skills 列表推送。 */
    REMOTE_SKILLS("RemoteSkills"),

    /** Plugins 插件列表推送。 */
    PLUGINS("Plugins"),

    /** Plugin 安装结果推送。 */
    PLUGIN_INSTALLED("PluginInstalled"),

    /** Plugin 删除结果推送。 */
    PLUGIN_DELETED("PluginDeleted"),

    /** Plugin 详情内容推送。 */
    PLUGIN_CONTENT("PluginContent"),

    /** 已知 GitHub 仓库列表推送（Plugins）。 */
    KNOWN_PLUGIN_REPOS("KnownPluginRepos"),

    /** 远程 Plugins 列表推送。 */
    REMOTE_PLUGINS("RemotePlugins"),

    /** Plugin 命令列表推送。 */
    PLUGIN_COMMANDS("PluginCommands"),

    /** Skill 内容保存结果推送。 */
    SKILL_CONTENT_SAVED("SkillContentSaved"),

    /** Plugin 内容保存结果推送。 */
    PLUGIN_CONTENT_SAVED("PluginContentSaved"),

    // ========== 计划模式 ==========

    /** 计划创建成功推送。 */
    PLAN_CREATED("PlanCreated"),

    /** 计划列表推送。 */
    PLAN_LIST("PlanList"),

    /** 计划详情推送。 */
    PLAN_DETAIL("PlanDetail"),

    /** 单个计划任务更新推送。 */
    PLAN_TASK_UPDATED("PlanTaskUpdated"),

    /** 计划任务执行状态变更推送。 */
    PLAN_TASK_STATUS("PlanTaskStatus"),

    /** 计划删除推送。 */
    PLAN_DELETED("PlanDeleted"),

    /** 计划配置推送。 */
    PLAN_CONFIG("PlanConfig"),

    /** 计划配置保存结果推送。 */
    PLAN_CONFIG_SAVED("PlanConfigSaved"),

    /** 计划执行概览更新推送。 */
    PLAN_OVERVIEW_UPDATED("PlanOverviewUpdated"),

    /** 计划拆分结果预览推送（不保存，仅前端预览）。 */
    PLAN_SPLIT_RESULT("PlanSplitResult");

    /** 回调名称。 */
    private final String value;

    JsCallback(String value) {
        this.value = value;
    }
}
