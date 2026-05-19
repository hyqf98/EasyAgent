package io.github.easyagent.ui.jcef.dto;

/**
 * Plugins 相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class PluginRequests {

    private PluginRequests() {
    }

    /**
     * 获取 Plugins 列表请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record GetPluginsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 安装 Plugin 请求。
     *
     * @param action     动作名称
     * @param cliType    CLI 类型
     * @param githubUrl  GitHub 仓库地址
     * @param pluginName 插件名称
     * @param scope      安装作用域
     */
    public record InstallPluginRequest(String action, String cliType, String githubUrl,
                                         String pluginName, String scope) implements JsRequest {
    }

    /**
     * 删除 Plugin 请求。
     *
     * @param action      动作名称
     * @param cliType     CLI 类型
     * @param pluginName  插件名称
     * @param installPath 安装路径
     */
    public record DeletePluginRequest(String action, String cliType,
                                        String pluginName, String installPath) implements JsRequest {
    }

    /**
     * 读取 Plugin 内容请求。
     *
     * @param action      动作名称
     * @param installPath 安装路径
     */
    public record ReadPluginContentRequest(String action, String installPath) implements JsRequest {
    }

    /**
     * 保存 Plugin 内容请求。
     *
     * @param action      动作名称
     * @param installPath 安装路径
     * @param content     内容
     */
    public record SavePluginContentRequest(String action, String installPath,
                                            String content) implements JsRequest {
    }

    /**
     * 列出远程 Plugins 请求。
     *
     * @param action    动作名称
     * @param ownerRepo 仓库地址
     */
    public record ListRemotePluginsRequest(String action, String ownerRepo) implements JsRequest {
    }
}
