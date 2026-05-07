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
    CLI_MODELS("CliModels");

    /** 回调名称。 */
    private final String value;

    JsCallback(String value) {
        this.value = value;
    }

    /**
     * 根据字符串值解析回调名称枚举。
     *
     * @param value 回调名称字符串
     * @return 对应的回调名称枚举，无法匹配时返回 {@code null}
     */
    public static JsCallback fromValue(String value) {
        return ValueEnum.fromValue(JsCallback.class, value);
    }
}
