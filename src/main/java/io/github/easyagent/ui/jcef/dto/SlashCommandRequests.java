package io.github.easyagent.ui.jcef.dto;

/**
 * 斜杠命令相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class SlashCommandRequests {

    private SlashCommandRequests() {
    }

    /**
     * 获取斜杠命令列表请求。
     *
     * @param action   动作名称
     * @param cliType  CLI 类型
     * @param requestId 请求 ID
     */
    public record GetSlashCommandsRequest(String action, String cliType,
                                            String requestId) implements JsRequest {
    }

    /**
     * 执行斜杠命令请求。
     *
     * @param action   动作名称
     * @param cliType  CLI 类型
     * @param rawText  原始命令文本
     * @param requestId 请求 ID
     */
    public record ExecuteSlashCommandRequest(String action, String cliType,
                                               String rawText, String requestId) implements JsRequest {
    }
}
