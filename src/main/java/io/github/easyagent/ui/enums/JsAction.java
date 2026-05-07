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

    /** 搜索项目文件引用候选。 */
    SEARCH_FILE_REFERENCES("searchFileReferences"),

    /** 根据路径生成文件引用。 */
    RESOLVE_FILE_REFERENCE("resolveFileReference"),

    /** 保存剪贴板图片并生成图片引用。 */
    SAVE_CLIPBOARD_IMAGE("saveClipboardImage"),

    /** 打开 AI 文件编辑 diff。 */
    OPEN_FILE_EDIT_DIFF("openFileEditDiff"),

    /** 回撤 AI 文件编辑。 */
    REVERT_FILE_EDIT("revertFileEdit");

    /** 动作标识值。 */
    private final String value;

    JsAction(String value) {
        this.value = value;
    }

    /**
     * 根据字符串值解析 JS 动作枚举。
     *
     * @param value 动作字符串
     * @return 对应的 JS 动作枚举，无法匹配时返回 {@code null}
     */
    public static JsAction fromValue(String value) {
        return ValueEnum.fromValue(JsAction.class, value);
    }
}
