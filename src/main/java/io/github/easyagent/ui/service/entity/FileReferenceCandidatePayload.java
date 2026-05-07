package io.github.easyagent.ui.service.entity;

import lombok.Builder;

/**
 * 文件引用搜索候选项。
 *
 * @param path         文件绝对路径
 * @param relativePath 相对当前项目根目录的路径
 * @param displayName  前端展示名称
 * @param fileName     文件名
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record FileReferenceCandidatePayload(
        String path,
        String relativePath,
        String displayName,
        String fileName
) {}
