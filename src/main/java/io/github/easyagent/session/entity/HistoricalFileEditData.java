package io.github.easyagent.session.entity;

import lombok.Builder;

/**
 * 历史文件编辑原始数据。
 * <p>
 * 用于从 Claude、Codex、OpenCode 原始会话记录中恢复 AI 编辑前后的原始文本，
 * 供历史重放时重新构建文件变动视图。
 * </p>
 *
 * @param originalFile 原始文件全文
 * @param oldString    原始替换文本
 * @param newString    替换后的文本
 * @param replaceAll   是否全量替换
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
@Builder
public record HistoricalFileEditData(
        String originalFile,
        String oldString,
        String newString,
        Boolean replaceAll
) {}
