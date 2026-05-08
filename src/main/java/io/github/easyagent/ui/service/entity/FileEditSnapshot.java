package io.github.easyagent.ui.service.entity;

import lombok.Builder;

/**
 * AI 文件编辑快照。
 * <p>
 * 持久化保存一次文件编辑的前后内容与统一元数据，
 * 供历史会话重新打开时继续使用 IDEA 原生 diff 与回撤能力。
 * </p>
 *
 * @param editId       编辑唯一标识
 * @param toolCallId   工具调用唯一标识
 * @param toolName     工具名称
 * @param operation    编辑操作名称
 * @param path         文件绝对路径
 * @param relativePath 相对项目路径
 * @param displayName  展示名称
 * @param beforeExists 编辑前文件是否存在
 * @param beforeContent 编辑前文本
 * @param afterExists  编辑后文件是否存在
 * @param afterContent 编辑后文本
 * @param capturedAt   快照捕获时间戳
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Builder
public record FileEditSnapshot(
        String editId,
        String toolCallId,
        String toolName,
        String operation,
        String path,
        String relativePath,
        String displayName,
        Boolean beforeExists,
        String beforeContent,
        Boolean afterExists,
        String afterContent,
        Long capturedAt
) {

    /**
     * 转换为统一的文件编辑元数据。
     *
     * @return 文件编辑元数据
     */
    public FileEditPayload toPayload() {
        return FileEditPayload.builder()
                .editId(this.editId)
                .toolCallId(this.toolCallId)
                .toolName(this.toolName)
                .operation(this.operation)
                .path(this.path)
                .relativePath(this.relativePath)
                .displayName(this.displayName)
                .build();
    }
}
