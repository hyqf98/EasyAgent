package io.github.easyagent.ui.enums;

import io.github.easyagent.enums.ValueEnum;
import lombok.Getter;

/**
 * JS 桥通信动作枚举。
 * <p>
 * 定义前端通过 {@code cefQuery} 发送给 Java 端的所有动作标识符，
 * 用于 {@code JCEFMessageBridge} 中的请求分发。
 * </p>
 *
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Getter
public enum JsAction implements ValueEnum<String> {

    /** 列出所有 CLI 类型的会话。 */
    LIST_ALL_SESSIONS("listAllSessions"),

    /** 列出指定 CLI 类型的会话。 */
    LIST_SESSIONS("listSessions"),

    /** 加载历史消息。 */
    LOAD_HISTORY("loadHistory"),

    /** 发送用户消息。 */
    SEND_MESSAGE("sendMessage"),

    /** 停止 AI 生成。 */
    STOP_GENERATION("stopGeneration"),

    /** 获取 IDE 主题。 */
    GET_THEME("getTheme"),

    /** 获取可用 CLI 工具列表。 */
    GET_AVAILABLE_CLIS("getAvailableCLIs"),

    /** 前端页面就绪通知。 */
    PAGE_READY("pageReady"),

    /** 批量删除会话。 */
    DELETE_SESSIONS("deleteSessions"),

    /** 保存指定会话的待发送队列。 */
    SAVE_PENDING_QUEUE("savePendingQueue"),

    /** 获取 AI 重试策略设置。 */
    GET_RETRY_CONFIG("getRetryConfig"),

    /** 保存 AI 重试策略设置。 */
    SAVE_RETRY_CONFIG("saveRetryConfig"),

    /** 获取模型配置列表。 */
    GET_MODELS("getModels"),

    /** 从远程同步模型配置。 */
    SYNC_MODELS("syncModels"),

    /** 保存编辑后的模型配置。 */
    SAVE_MODELS("saveModels"),

    /** 查询 CLI 可用模型列表。 */
    QUERY_CLI_MODELS("queryCliModels"),

    /** 保存 CLI 配置档案。 */
    SAVE_CLI_PROFILE("saveCliProfile"),

    /** 删除 CLI 配置档案。 */
    DELETE_CLI_PROFILE("deleteCliProfile"),

    /** 应用 CLI 配置档案（切换到指定档案）。 */
    APPLY_CLI_PROFILE("applyCliProfile"),

    /** 搜索项目文件引用候选。 */
    SEARCH_FILE_REFERENCES("searchFileReferences"),

    /** 根据路径生成文件引用。 */
    RESOLVE_FILE_REFERENCE("resolveFileReference"),

    /** 保存剪贴板图片并生成图片引用。 */
    SAVE_CLIPBOARD_IMAGE("saveClipboardImage"),

    /** 打开 AI 文件编辑 diff。 */
    OPEN_FILE_EDIT_DIFF("openFileEditDiff"),

    /** 回撤 AI 文件编辑。 */
    REVERT_FILE_EDIT("revertFileEdit"),

    /** 获取当前 CLI 可用的斜杠命令列表。 */
    GET_SLASH_COMMANDS("getSlashCommands"),

    /** 执行一个斜杠命令。 */
    EXECUTE_SLASH_COMMAND("executeSlashCommand"),

    /** 获取 CLI 配置管理数据。 */
    GET_CLI_CONFIGS("getCliConfigs"),

    /** 保存 CLI 配置。 */
    SAVE_CLI_CONFIGS("saveCliConfigs"),

    /** 获取 MCP 服务器配置列表。 */
    GET_MCP_CONFIGS("getMcpConfigs"),

    /** 保存（新增/编辑）MCP 服务器配置。 */
    SAVE_MCP_SERVER("saveMcpServer"),

    /** 删除 MCP 服务器配置。 */
    DELETE_MCP_SERVER("deleteMcpServer"),

    /** 测试连接 MCP 服务器。 */
    TEST_MCP_CONNECT("testMcpConnect"),

    /** 列出 MCP 服务器工具。 */
    LIST_MCP_TOOLS("listMcpTools"),

    /** 调用 MCP 工具。 */
    CALL_MCP_TOOL("callMcpTool"),

    /** 获取 Skills 技能列表。 */
    GET_SKILLS("getSkills"),

    /** 从 GitHub 安装 Skill。 */
    INSTALL_SKILL("installSkill"),

    /** 删除 Skill。 */
    DELETE_SKILL("deleteSkill"),

    /** 读取 Skill 详情内容。 */
    READ_SKILL_CONTENT("readSkillContent"),

    /** 获取已知的 GitHub 仓库列表（Skills 安装下拉）。 */
    LIST_KNOWN_REPOS("listKnownRepos"),

    /** 浏览指定仓库的远程 Skills 列表。 */
    LIST_REMOTE_SKILLS("listRemoteSkills"),

    /** 获取 Plugins 插件列表。 */
    GET_PLUGINS("getPlugins"),

    /** 从 GitHub 安装 Plugin。 */
    INSTALL_PLUGIN("installPlugin"),

    /** 删除 Plugin。 */
    DELETE_PLUGIN("deletePlugin"),

    /** 读取 Plugin 详情内容。 */
    READ_PLUGIN_CONTENT("readPluginContent"),

    /** 获取已知的 GitHub 仓库列表（Plugin 安装下拉）。 */
    LIST_KNOWN_PLUGIN_REPOS("listKnownPluginRepos"),

    /** 浏览指定仓库的远程 Plugins 列表。 */
    LIST_REMOTE_PLUGINS("listRemotePlugins"),

    /** 读取 Plugin 命令列表。 */
    READ_PLUGIN_COMMANDS("readPluginCommands"),

    /** 保存 Skill 内容。 */
    SAVE_SKILL_CONTENT("saveSkillContent"),

    /** 保存 Plugin 内容。 */
    SAVE_PLUGIN_CONTENT("savePluginContent"),

    // ========== 计划模式 ==========

    /** 创建计划。 */
    CREATE_PLAN("createPlan"),

    /** 列出项目下的计划。 */
    LIST_PLANS("listPlans"),

    /** 获取计划详情（含任务列表）。 */
    GET_PLAN_DETAIL("getPlanDetail"),

    /** 更新计划信息。 */
    UPDATE_PLAN("updatePlan"),

    /** 删除计划。 */
    DELETE_PLAN("deletePlan"),

    /** 更新计划任务。 */
    UPDATE_PLAN_TASK("updatePlanTask"),

    /** 执行计划任务。 */
    EXECUTE_PLAN_TASK("executePlanTask"),

    /** 停止计划任务执行。 */
    STOP_PLAN_TASK("stopPlanTask"),

    /** AI 编辑任务列表。 */
    AI_EDIT_TASKS("aiEditTasks"),

    /** 保存计划任务列表变更。 */
    SAVE_PLAN_TASKS("savePlanTasks"),

    /** 获取计划并发执行数配置。 */
    GET_PLAN_CONFIG("getPlanConfig"),

    /** 保存计划并发执行数配置。 */
    SAVE_PLAN_CONFIG("savePlanConfig"),

    /** 开始计划拆分（启动 CLI 会话）。 */
    START_PLAN_SPLIT("startPlanSplit"),

    /** 保存面板布局。 */
    SAVE_PANE_LAYOUT("savePaneLayout");

    /** 动作标识值。 */
    private final String value;

    JsAction(String value) {
        this.value = value;
    }
}
