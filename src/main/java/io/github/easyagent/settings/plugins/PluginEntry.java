package io.github.easyagent.settings.plugins;

import lombok.Builder;

/**
 * 单个 Plugin 插件条目。
 * <p>
 * 统一表示 Claude / OpenCode / Codex 三个 CLI 的已安装插件信息，
 * 包含插件元数据（名称、描述、版本、作者等）和安装位置信息。
 * </p>
 *
 * @param name          插件名称
 * @param description   插件描述
 * @param version       插件版本号
 * @param author        插件作者
 * @param homepage      插件主页 URL
 * @param license       插件许可证
 * @param cliType       CLI 类型：CLAUDE / OPENCODE / CODEX
 * @param scope         配置作用域：{@code user} 或 {@code project}
 * @param installPath   插件安装目录绝对路径
 * @param source        安装来源：{@code local}、{@code github}、{@code marketplace}、{@code npm}
 * @param sourceUrl     安装来源 URL（GitHub 仓库地址或 marketplace 标识）
 * @param lastModified  最后修改时间（毫秒时间戳）
 * @author haijun
 * @date 2026/5/13
 * @since 1.0.0
 */
@Builder
public record PluginEntry(
        String name,
        String description,
        String version,
        String author,
        String homepage,
        String license,
        String cliType,
        String scope,
        String installPath,
        String source,
        String sourceUrl,
        long lastModified
) {
}
