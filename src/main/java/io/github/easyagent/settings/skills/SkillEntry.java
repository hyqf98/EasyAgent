package io.github.easyagent.settings.skills;

import lombok.Builder;

/**
 * 单个 Skill 技能条目。
 * <p>
 * 统一表示 Claude / OpenCode / Codex 三个 CLI 的 Skill 配置，
 * 每个技能由目录下的 SKILL.md 文件定义，包含 name、description 和 content。
 * </p>
 *
 * @param name          技能名称（目录名或 frontmatter 中的 name）
 * @param description   技能描述
 * @param content       SKILL.md 文件内容（仅详情查询时返回，列表时不返回）
 * @param scope         配置作用域：{@code user} 或 {@code project}
 * @param cliType       CLI 类型：CLAUDE / OPENCODE / CODEX
 * @param skillPath     技能目录绝对路径
 * @param enabled       是否启用（Claude 插件系统支持 enable/disable）
 * @param source        安装来源：{@code local}、{@code github}、{@code marketplace}
 * @param sourceUrl     安装来源 URL（GitHub 仓库地址或 marketplace 标识）
 * @param version       版本号（Claude 插件系统有版本信息）
 * @param lastModified  SKILL.md 最后修改时间（毫秒时间戳）
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Builder
public record SkillEntry(
        String name,
        String description,
        String content,
        String scope,
        String cliType,
        String skillPath,
        boolean enabled,
        String source,
        String sourceUrl,
        String version,
        long lastModified
) {
}
