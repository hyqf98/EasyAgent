package io.github.easyagent.ui.service;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.ui.service.entity.FileEditPayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件编辑跟踪与恢复服务。
 * <p>
 * 跟踪 AI 文件编辑工具调用前后的内容快照，并通过 IDEA 原生 diff/Undo 机制
 * 提供改动回显和回撤能力。
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public final class FileEditService {

    /** 通知组 ID。 */
    private static final String NOTIFICATION_GROUP_ID = "EasyAgent.NotificationGroup";

    /** 当前 IDEA 项目。 */
    private final Project project;

    /** 当前项目根路径。 */
    private final String projectPath;

    /** 按编辑 ID 跟踪的文件编辑快照。 */
    private final Map<String, TrackedFileEdit> trackedEdits = new ConcurrentHashMap<>();

    /**
     * 构造文件编辑服务。
     *
     * @param project     当前 IDEA 项目
     * @param projectPath 当前项目路径
     */
    public FileEditService(Project project, String projectPath) {
        this.project = project;
        this.projectPath = projectPath;
    }

    /**
     * 记录流式工具调用的文件编辑状态。
     *
     * @param sessionId 会话 ID
     * @param toolCall  工具调用内容
     */
    public void trackToolCall(String sessionId, ToolCallContent toolCall) {
        FileEditPayload fileEdit = ToolMetadataSupport.resolveFileEdit(sessionId, this.projectPath, toolCall);
        if (fileEdit == null) {
            return;
        }

        TrackedFileEdit tracked = this.trackedEdits.computeIfAbsent(fileEdit.editId(), key -> {
            VirtualFile file = findFile(fileEdit.path());
            String beforeContent = readCurrentText(file);
            return new TrackedFileEdit(fileEdit, beforeContent, null);
        });

        if (toolCall.status() == ToolCallStatus.COMPLETED || toolCall.status() == ToolCallStatus.FAILED) {
            VirtualFile file = tracked.file != null ? tracked.file : findFile(fileEdit.path());
            tracked.file = file;
            tracked.afterContent = readCurrentText(file);
        }
    }

    /**
     * 打开 AI 文件修改前后 diff。
     *
     * @param editId 编辑 ID
     */
    public void openDiff(String editId) {
        TrackedFileEdit tracked = this.trackedEdits.get(editId);
        if (tracked == null || tracked.beforeContent == null || tracked.afterContent == null) {
            this.notify("No tracked file diff is available for this AI edit.", NotificationType.WARNING);
            return;
        }

        String title = "AI File Edit: " + tracked.payload.displayName();
        SimpleDiffRequest request = new SimpleDiffRequest(
                title,
                DiffContentFactory.getInstance().create(this.project, tracked.beforeContent, tracked.file),
                DiffContentFactory.getInstance().create(this.project, tracked.afterContent, tracked.file),
                "Before AI edit",
                "After AI edit"
        );
        DiffManager.getInstance().showDiff(this.project, request);
    }

    /**
     * 将文件恢复到 AI 编辑前的内容。
     *
     * @param editId 编辑 ID
     */
    public void revertEdit(String editId) {
        TrackedFileEdit tracked = this.trackedEdits.get(editId);
        if (tracked == null || tracked.beforeContent == null) {
            this.notify("No tracked file snapshot is available to revert.", NotificationType.WARNING);
            return;
        }
        if (tracked.file == null) {
            this.notify("The edited file can no longer be found.", NotificationType.ERROR);
            return;
        }

        Document document = FileDocumentManager.getInstance().getDocument(tracked.file);
        if (document == null) {
            this.notify("The edited file is not a text document.", NotificationType.ERROR);
            return;
        }

        WriteCommandAction.runWriteCommandAction(this.project, "Revert AI File Edit", null, () -> {
            document.setText(tracked.beforeContent);
            FileDocumentManager.getInstance().saveDocument(document);
            FileEditorManager.getInstance(this.project).openFile(tracked.file, true);
        });
    }

    /**
     * 根据文件路径查找 VirtualFile。
     *
     * @param path 绝对路径
     * @return VirtualFile；不存在时返回 {@code null}
     */
    private static VirtualFile findFile(String path) {
        return LocalFileSystem.getInstance().findFileByPath(path);
    }

    /**
     * 读取文件当前文本。
     *
     * @param file 文件对象
     * @return 当前文本；不可读时返回 {@code null}
     */
    private static String readCurrentText(VirtualFile file) {
        if (file == null || file.isDirectory()) {
            return null;
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 发送 IDEA 通知。
     *
     * @param message 通知文本
     * @param type    通知级别
     */
    private void notify(String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, type)
                .notify(this.project);
    }

    /**
     * 跟踪中的文件编辑快照。
     *
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private static final class TrackedFileEdit {

        /** 统一的文件编辑元数据。 */
        private final FileEditPayload payload;

        /** AI 编辑前的文件内容。 */
        private String beforeContent;

        /** AI 编辑后的文件内容。 */
        private String afterContent;

        /** 当前定位到的文件对象。 */
        private VirtualFile file;

        /**
         * 构造跟踪对象。
         *
         * @param payload       编辑元数据
         * @param beforeContent 编辑前文本
         * @param afterContent  编辑后文本
         */
        private TrackedFileEdit(FileEditPayload payload, String beforeContent, String afterContent) {
            this.payload = payload;
            this.beforeContent = beforeContent;
            this.afterContent = afterContent;
        }
    }
}
