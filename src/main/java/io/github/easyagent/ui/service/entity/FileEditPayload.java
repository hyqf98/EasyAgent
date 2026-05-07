package io.github.easyagent.ui.service.entity;

import lombok.Builder;

/**
 * 统一的文件编辑元数据。
 *
 * @param editId      编辑唯一标识
 * @param toolCallId  工具调用唯一标识
 * @param toolName    工具名称
 * @param operation   归一化后的编辑操作名称
 * @param path        文件绝对路径
 * @param relativePath 相对当前项目根目录的路径
 * @param displayName 前端展示名称
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record FileEditPayload(
        String editId,
        String toolCallId,
        String toolName,
        String operation,
        String path,
        String relativePath,
        String displayName
) {}
