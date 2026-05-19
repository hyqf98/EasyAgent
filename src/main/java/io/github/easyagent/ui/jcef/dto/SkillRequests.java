package io.github.easyagent.ui.jcef.dto;

/**
 * Skills 相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class SkillRequests {

    private SkillRequests() {
    }

    /**
     * 获取 Skills 列表请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record GetSkillsRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 从 GitHub 安装 Skill 请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param githubUrl GitHub 仓库地址
     * @param skillName 技能名称
     * @param scope     安装作用域
     */
    public record InstallSkillRequest(String action, String cliType, String githubUrl,
                                        String skillName, String scope) implements JsRequest {
    }

    /**
     * 删除 Skill 请求。
     *
     * @param action    动作名称
     * @param cliType   CLI 类型
     * @param skillName skill 名称
     * @param skillPath skill 目录路径
     */
    public record DeleteSkillRequest(String action, String cliType,
                                       String skillName, String skillPath) implements JsRequest {
    }

    /**
     * 读取 Skill 内容请求。
     *
     * @param action    动作名称
     * @param skillPath skill 目录路径
     */
    public record ReadSkillContentRequest(String action, String skillPath) implements JsRequest {
    }

    /**
     * 保存 Skill 内容请求。
     *
     * @param action    动作名称
     * @param skillPath skill 目录路径
     * @param content   内容
     */
    public record SaveSkillContentRequest(String action, String skillPath,
                                           String content) implements JsRequest {
    }

    /**
     * 列出已知仓库请求。
     *
     * @param action  动作名称
     * @param cliType CLI 类型
     */
    public record ListKnownReposRequest(String action, String cliType) implements JsRequest {
    }

    /**
     * 列出远程 Skills 请求。
     *
     * @param action    动作名称
     * @param ownerRepo 仓库地址
     */
    public record ListRemoteSkillsRequest(String action, String ownerRepo) implements JsRequest {
    }
}
