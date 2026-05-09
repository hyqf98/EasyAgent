package io.github.easyagent.ui.service.entity;

import lombok.Builder;
import lombok.Data;

/**
 * AI 文件编辑快照。
 * <p>
 * 持久化保存一次文件编辑的前后内容与统一元数据，
 * 供历史会话重新打开时继续使用 IDEA 原生 diff 与回撤能力。
 * </p>
 *
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Data
@Builder
public class FileEditSnapshot {

    /** 编辑唯一标识。 */
    private String editId;

    /** 工具调用唯一标识。 */
    private String toolCallId;

    /** 工具名称。 */
    private String toolName;

    /** 编辑操作名称。 */
    private String operation;

    /** 文件绝对路径。 */
    private String path;

    /** 相对项目路径。 */
    private String relativePath;

    /** 展示名称。 */
    private String displayName;

    /** 编辑前文件是否存在。 */
    private Boolean beforeExists;

    /** 编辑前文本。 */
    private String beforeContent;

    /** 编辑后文件是否存在。 */
    private Boolean afterExists;

    /** 编辑后文本。 */
    private String afterContent;

    /** 快照捕获时间戳。 */
    private Long capturedAt;

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
