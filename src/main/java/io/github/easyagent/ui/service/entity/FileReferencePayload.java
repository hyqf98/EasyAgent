package io.github.easyagent.ui.service.entity;

import lombok.Builder;

/**
 * 前端可消费的文件引用载荷。
 *
 * @param id          引用唯一标识
 * @param path        文件绝对路径
 * @param relativePath 相对当前项目根目录的路径
 * @param displayName 前端展示名称
 * @param referenceType 引用类型（FILE/IMAGE）
 * @param inlineToken 前端输入框内联展示占位符
 * @param startLine   起始行号（1-based）
 * @param endLine     结束行号（1-based）
 * @param selection   是否来自编辑器选区
 * @param content     兼容字段，当前仅保留结构不再传输文件内容
 * @param truncated   兼容字段，当前固定为 {@code false}
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record FileReferencePayload(
        String id,
        String path,
        String relativePath,
        String displayName,
        String referenceType,
        String inlineToken,
        Integer startLine,
        Integer endLine,
        Boolean selection,
        String content,
        Boolean truncated
) {}
