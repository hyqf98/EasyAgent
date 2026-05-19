package io.github.easyagent.ui.jcef.dto;

import io.github.easyagent.settings.config.ClaudeConfig;
import io.github.easyagent.settings.config.CodexConfig;
import io.github.easyagent.settings.config.OpenCodeConfig;
import io.github.easyagent.settings.config.CliProfile;

/**
 * CLI 配置相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class CliConfigRequests {

    private CliConfigRequests() {
    }

    /**
     * 保存 CLI 配置请求。
     *
     * @param action   动作名称
     * @param cliType  CLI 类型
     * @param claude   Claude 配置
     * @param opencode OpenCode 配置
     * @param codex    Codex 配置
     */
    public record SaveCliConfigsRequest(String action, String cliType,
                                         ClaudeConfig claude, OpenCodeConfig opencode,
                                         CodexConfig codex) implements JsRequest {
    }

    /**
     * 保存 CLI 配置档案请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     * @param profile 配置档案数据
     */
    public record SaveCliProfileRequest(String action, String cliType,
                                          CliProfile profile) implements JsRequest {
    }

    /**
     * 删除 CLI 配置档案请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param profileId 档案 ID
     */
    public record DeleteCliProfileRequest(String action, String cliType,
                                            String profileId) implements JsRequest {
    }

    /**
     * 应用 CLI 配置档案请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param profileId 档案 ID
     */
    public record ApplyCliProfileRequest(String action, String cliType,
                                           String profileId) implements JsRequest {
    }
}
