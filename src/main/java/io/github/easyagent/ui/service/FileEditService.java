package io.github.easyagent.ui.service;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffRequestFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.InvalidDiffRequestException;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.merge.MergeResult;
import com.intellij.diff.merge.TextMergeRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.CLIType;
import io.github.easyagent.enums.ToolCallStatus;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.EasyAgentState;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.HistoricalFileEditData;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.ui.service.entity.FileEditPayload;
import io.github.easyagent.ui.service.entity.FileEditSnapshot;
import io.github.easyagent.util.GsonUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
@SuppressWarnings("deprecation")
public final class FileEditService {

    /** 通知组 ID。 */
    private static final String NOTIFICATION_GROUP_ID = "EasyAgent.NotificationGroup";

    /** 当前 IDEA 项目。 */
    private final Project project;

    /** 当前项目根路径。 */
    private final String projectPath;

    /** 会话服务，用于从历史记录修复文件编辑快照。 */
    private final SessionService sessionService;

    /** 按编辑 ID 跟踪的文件编辑快照。 */
    private final Map<String, TrackedFileEdit> trackedEdits = new ConcurrentHashMap<>();

    /**
     * 构造文件编辑服务。
     *
     * @param project     当前 IDEA 项目
     * @param projectPath 当前项目路径
     */
    public FileEditService(Project project, String projectPath) {
        this(project, projectPath, null);
    }

    /**
     * 构造文件编辑服务。
     *
     * @param project       当前 IDEA 项目
     * @param projectPath   当前项目路径
     * @param sessionService 会话服务
     */
    public FileEditService(Project project, String projectPath, SessionService sessionService) {
        this.project = project;
        this.projectPath = projectPath;
        this.sessionService = sessionService;
        this.loadPersistedSnapshots();
    }

    /**
     * 记录流式工具调用的文件编辑状态。
     *
     * @param sessionId 会话 ID
     * @param toolCall  工具调用内容
     */
    public void trackToolCall(String sessionId, ToolCallContent toolCall) {
        FileEditPayload fileEdit = ToolMetadataSupport.resolveFileEdit(sessionId, this.projectPath, toolCall);
        TrackedFileEdit tracked = null;
        if (fileEdit != null) {
            tracked = this.trackedEdits.computeIfAbsent(fileEdit.editId(),
                    key -> this.createTrackedEdit(fileEdit));
        }

        if (tracked == null && toolCall != null && toolCall.toolCallId() != null && !toolCall.toolCallId().isBlank()) {
            tracked = this.findTrackedEditByToolCallId(toolCall.toolCallId());
        }

        if (tracked == null) {
            return;
        }

        if (toolCall.status() == ToolCallStatus.COMPLETED || toolCall.status() == ToolCallStatus.FAILED) {
            TrackedFileEdit targetTracked = tracked;
            VirtualFile file = ReadAction.compute(
                    () -> targetTracked.file != null && targetTracked.file.isValid()
                            ? targetTracked.file
                            : findFile(targetTracked.payload.path()));
            targetTracked.file = file;
            targetTracked.afterExists = file != null;
            targetTracked.afterContent = ReadAction.compute(() -> readCurrentText(file));
            this.persistTrackedEdit(targetTracked);
        }
    }

    /**
     * 从历史会话消息中恢复文件编辑快照。
     *
     * @param messages 历史会话消息列表
     */
    public void trackHistoricalMessages(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (SessionMessage message : messages) {
            if (message == null || message.contents() == null || message.contents().isEmpty()) {
                continue;
            }
            Map<String, FileEditPayload> toolUsePayloads = new ConcurrentHashMap<>();
            for (ContentBlock block : message.contents()) {
                if (block == null || block.type() == null) {
                    continue;
                }
                if (block.type() != io.github.easyagent.enums.ContentBlockType.TOOL_USE) {
                    continue;
                }
                FileEditPayload fileEdit = ToolMetadataSupport.resolveFileEdit(message.sessionId(), this.projectPath, block);
                if (fileEdit == null || block.toolUseId() == null || block.toolUseId().isBlank()) {
                    continue;
                }
                toolUsePayloads.put(block.toolUseId(), fileEdit);
            }
            for (ContentBlock block : message.contents()) {
                if (block == null
                        || block.toolUseId() == null
                        || block.toolUseId().isBlank()
                        || block.historicalFileEditData() == null) {
                    continue;
                }
                FileEditPayload fileEdit = toolUsePayloads.get(block.toolUseId());
                if (fileEdit == null) {
                    continue;
                }
                TrackedFileEdit historical = this.createHistoricalTrackedEdit(fileEdit, block);
                if (historical == null) {
                    continue;
                }
                this.mergeHistoricalTrackedEdit(historical);
            }
        }
    }

    /**
     * 打开 AI 文件修改前后 diff。
     *
     * @param editId 编辑 ID
     */
    public void openDiff(String editId) {
        TrackedFileEdit tracked = this.resolveTrackedEdit(editId, null, null);
        this.openDiff(tracked);
    }

    /**
     * 打开 AI 文件修改前后 diff。
     *
     * @param editId      编辑 ID
     * @param toolCallId  工具调用 ID
     * @param path        文件路径
     */
    public void openDiff(String editId, String toolCallId, String path) {
        TrackedFileEdit tracked = this.resolveTrackedEdit(editId, toolCallId, path);
        this.openDiff(tracked);
    }

    /**
     * 打开 AI 文件修改前后 diff。
     *
     * @param tracked 跟踪对象
     */
    private void openDiff(TrackedFileEdit tracked) {
        if (tracked == null || (!tracked.beforeExists && !tracked.afterExists)) {
            this.notify("No tracked file diff is available for this AI edit.", NotificationType.WARNING);
            return;
        }

        VirtualFile file = ReadAction.compute(
                () -> tracked.file != null && tracked.file.isValid() ? tracked.file : findFile(tracked.payload.path()));
        tracked.file = file;
        Document document = file != null ? ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(file)) : null;
        if (file == null || document == null) {
            this.showSimpleDiff(tracked, file);
            return;
        }
        this.showMergeReview(tracked, file, document);
    }

    /**
     * 使用简单左右对比打开文件变更。
     *
     * @param tracked 跟踪对象
     * @param file    关联文件
     */
    private void showSimpleDiff(TrackedFileEdit tracked, VirtualFile file) {
        String title = "AI File Edit: " + tracked.payload.displayName();
        DiffContentFactory factory = DiffContentFactory.getInstance();
        DiffContent beforeContent = this.createDiffContent(factory, file, tracked.beforeContent);
        DiffContent afterContent = this.createDiffContent(factory, file, tracked.afterContent);
        SimpleDiffRequest request = new SimpleDiffRequest(
                title,
                beforeContent,
                afterContent,
                tracked.beforeExists ? "Before AI edit" : "Before AI edit (file did not exist)",
                tracked.afterExists ? "After AI edit" : "After AI edit (file deleted)"
        );
        DiffManager.getInstance().showDiff(this.project, request);
    }

    /**
     * 使用 Git 冲突式三向合并查看 AI 变更。
     *
     * @param tracked  跟踪对象
     * @param file     关联文件
     * @param document 当前文件文档
     */
    private void showMergeReview(TrackedFileEdit tracked, VirtualFile file, Document document) {
        String beforeText = StringUtil.notNullize(tracked.beforeContent);
        String currentText = StringUtil.notNullize(document.getText());
        String afterText = tracked.afterContent != null ? tracked.afterContent : currentText;
        String title = "AI File Edit: " + tracked.payload.displayName();
        List<byte[]> contents = List.of(
                beforeText.getBytes(StandardCharsets.UTF_8),
                currentText.getBytes(StandardCharsets.UTF_8),
                StringUtil.notNullize(afterText).getBytes(StandardCharsets.UTF_8)
        );
        List<String> titles = List.of("Before AI edit", "Current file", "AI edit");
        try {
            TextMergeRequest request = DiffRequestFactory.getInstance().createTextMergeRequest(
                    this.project, file, contents, title, titles, result -> this.onMergeResult(result, file));
            DiffManager.getInstance().showMerge(this.project, request);
        } catch (InvalidDiffRequestException e) {
            this.showSimpleDiff(tracked, file);
        }
    }

    /**
     * 处理三向合并结果。
     *
     * @param result 合并结果
     * @param file   当前文件
     */
    private void onMergeResult(MergeResult result, VirtualFile file) {
        if (result == MergeResult.CANCEL || file == null) {
            return;
        }
        Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(file));
        if (document == null) {
            return;
        }
        FileDocumentManager.getInstance().saveDocument(document);
    }

    /**
     * 将文件恢复到 AI 编辑前的内容。
     *
     * @param editId 编辑 ID
     */
    public void revertEdit(String editId) {
        this.revertEdit(this.resolveTrackedEdit(editId, null, null));
    }

    /**
     * 将文件恢复到 AI 编辑前的内容。
     *
     * @param editId      编辑 ID
     * @param toolCallId  工具调用 ID
     * @param path        文件路径
     */
    public void revertEdit(String editId, String toolCallId, String path) {
        this.revertEdit(this.resolveTrackedEdit(editId, toolCallId, path));
    }

    /**
     * 将文件恢复到 AI 编辑前的内容。
     *
     * @param tracked 跟踪对象
     */
    private void revertEdit(TrackedFileEdit tracked) {
        if (tracked == null) {
            this.notify("No tracked file snapshot is available to revert.", NotificationType.WARNING);
            return;
        }
        VirtualFile file = ReadAction.compute(
                () -> tracked.file != null && tracked.file.isValid() ? tracked.file : findFile(tracked.payload.path()));
        tracked.file = file;
        if (!tracked.beforeExists) {
            this.deleteCreatedFile(file);
            return;
        }

        if (file == null) {
            file = this.ensureFileExists(tracked.payload.path());
            tracked.file = file;
        }
        if (file == null) {
            this.notify("The edited file can no longer be found.", NotificationType.ERROR);
            return;
        }
        VirtualFile targetFile = file;

        Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(targetFile));
        if (document == null) {
            this.notify("The edited file is not a text document.", NotificationType.ERROR);
            return;
        }

        WriteCommandAction.runWriteCommandAction(this.project, "Revert AI File Edit", null, () -> {
            document.setText(StringUtil.notNullize(tracked.beforeContent));
            FileDocumentManager.getInstance().saveDocument(document);
            FileEditorManager.getInstance(this.project).openFile(targetFile, true);
        });
    }

    /**
     * 创建新的文件编辑跟踪对象。
     *
     * @param fileEdit 文件编辑元数据
     * @return 跟踪对象
     */
    private TrackedFileEdit createTrackedEdit(FileEditPayload fileEdit) {
        return ReadAction.compute(() -> {
            VirtualFile file = findFile(fileEdit.path());
            String beforeContent = readCurrentText(file);
            return new TrackedFileEdit(fileEdit, file != null, beforeContent, false, null, file);
        });
    }

    /**
     * 根据历史消息创建文件编辑快照。
     *
     * @param fileEdit 文件编辑元数据
     * @param block    历史内容块
     * @return 跟踪对象，无法恢复时返回 {@code null}
     */
    private TrackedFileEdit createHistoricalTrackedEdit(FileEditPayload fileEdit, ContentBlock block) {
        return ReadAction.compute(() -> {
            VirtualFile file = findFile(fileEdit.path());
            String currentContent = readCurrentText(file);
            HistoricalFileEditData historical = block != null ? block.historicalFileEditData() : null;
            HistoricalFileContents contents = this.resolveHistoricalContents(fileEdit, historical, currentContent);
            String beforeContent = contents.beforeContent();
            String afterContent = contents.afterContent();
            boolean beforeExists = this.inferBeforeExists(fileEdit.operation(), beforeContent, file);
            boolean afterExists = this.inferAfterExists(fileEdit.operation(), afterContent, file);
            if (beforeContent == null && afterContent == null && file == null) {
                return null;
            }
            return new TrackedFileEdit(fileEdit, beforeExists, beforeContent, afterExists, afterContent, file);
        });
    }

    /**
     * 合并历史恢复出来的文件编辑快照。
     *
     * @param historical 历史快照
     */
    private void mergeHistoricalTrackedEdit(TrackedFileEdit historical) {
        if (historical == null || historical.payload == null) {
            return;
        }
        TrackedFileEdit tracked = this.trackedEdits.putIfAbsent(historical.payload.editId(), historical);
        if (tracked == null) {
            this.persistTrackedEdit(historical);
            return;
        }
        boolean changed = false;
        if (tracked.beforeContent == null && historical.beforeContent != null) {
            tracked.beforeContent = historical.beforeContent;
            tracked.beforeExists = historical.beforeExists;
            changed = true;
        }
        if (tracked.afterContent == null && historical.afterContent != null) {
            tracked.afterContent = historical.afterContent;
            tracked.afterExists = historical.afterExists;
            changed = true;
        }
        if (tracked.file == null && historical.file != null) {
            tracked.file = historical.file;
        }
        if (changed) {
            this.persistTrackedEdit(tracked);
        }
    }

    /**
     * 生成历史文件编辑后的文本。
     *
     * @param fileEdit    文件编辑元数据
     * @param historical  历史文件编辑原始数据
     * @param currentText 当前文件文本
     * @return AI 编辑后的文本
     */
    private HistoricalFileContents resolveHistoricalContents(FileEditPayload fileEdit, HistoricalFileEditData historical,
                                                            String currentText) {
        if (fileEdit == null) {
            return new HistoricalFileContents(currentText, currentText);
        }
        String operation = StringUtil.notNullize(fileEdit.operation()).toLowerCase();
        if ("delete".equals(operation)) {
            return new HistoricalFileContents(currentText, null);
        }
        if (historical == null) {
            return new HistoricalFileContents(currentText, currentText);
        }
        String original = historical.originalFile();
        String oldString = historical.oldString();
        String newString = historical.newString();
        boolean replaceAll = historical.replaceAll() != null && historical.replaceAll();
        if (original != null && oldString != null && newString != null) {
            String after = this.applyHistoricalReplacement(original, oldString, newString, replaceAll);
            return new HistoricalFileContents(original, after);
        }
        if (currentText != null && oldString != null && newString != null) {
            String before = this.applyReverseHistoricalReplacement(currentText, oldString, newString, replaceAll);
            return new HistoricalFileContents(before, currentText);
        }
        if ("create".equals(operation)) {
            return new HistoricalFileContents(null, currentText);
        }
        return new HistoricalFileContents(currentText, currentText);
    }

    /**
     * 计算 AI 编辑前文件是否存在。
     *
     * @param operation      编辑操作
     * @param beforeContent  编辑前文本
     * @param file           当前文件
     * @return 是否存在
     */
    private boolean inferBeforeExists(String operation, String beforeContent, VirtualFile file) {
        if ("create".equalsIgnoreCase(operation)) {
            return false;
        }
        return beforeContent != null || file != null;
    }

    /**
     * 计算 AI 编辑后文件是否存在。
     *
     * @param operation     编辑操作
     * @param afterContent  编辑后文本
     * @param file          当前文件
     * @return 是否存在
     */
    private boolean inferAfterExists(String operation, String afterContent, VirtualFile file) {
        if ("delete".equalsIgnoreCase(operation)) {
            return false;
        }
        return afterContent != null || file != null;
    }

    /**
     * 应用 Claude 编辑结果到原始文件全文。
     *
     * @param original    原始文件全文
     * @param oldString   替换前文本
     * @param newString   替换后文本
     * @param replaceAll  是否全量替换
     * @return 变更后的全文
     */
    private String applyHistoricalReplacement(String original, String oldString, String newString, boolean replaceAll) {
        if (original == null || oldString == null || newString == null || oldString.isEmpty()) {
            return original;
        }
        if (replaceAll) {
            return original.replace(oldString, newString);
        }
        int index = original.indexOf(oldString);
        if (index < 0) {
            return original;
        }
        return original.substring(0, index) + newString + original.substring(index + oldString.length());
    }

    /**
     * 反向应用历史替换，用于从编辑后的文本反推编辑前内容。
     *
     * @param currentText 当前文本
     * @param oldString   替换前文本
     * @param newString   替换后文本
     * @param replaceAll  是否全量替换
     * @return 编辑前文本
     */
    private String applyReverseHistoricalReplacement(String currentText, String oldString, String newString,
                                                     boolean replaceAll) {
        if (currentText == null || oldString == null || newString == null || newString.isEmpty()) {
            return currentText;
        }
        if (replaceAll) {
            return currentText.replace(newString, oldString);
        }
        int index = currentText.indexOf(newString);
        if (index < 0) {
            return currentText;
        }
        return currentText.substring(0, index) + oldString + currentText.substring(index + newString.length());
    }

    /**
     * 解析一个可用的编辑跟踪对象。
     * <p>
     * 先从内存查找，未命中时再从项目持久化状态恢复。
     * </p>
     *
     * @param editId 编辑 ID
     * @return 跟踪对象；不存在时返回 {@code null}
     */
    private TrackedFileEdit resolveTrackedEdit(String editId, String toolCallId, String path) {
        TrackedFileEdit tracked = this.trackedEdits.get(editId);
        if (tracked != null) {
            return tracked;
        }
        tracked = this.resolveTrackedEditByPayload(editId, toolCallId, path);
        if (tracked != null) {
            return tracked;
        }
        if (this.project == null || editId == null || editId.isBlank()) {
            return null;
        }
        String snapshotJson = EasyAgentState.getInstance(this.project).getFileEditSnapshot(editId);
        if (snapshotJson == null || snapshotJson.isBlank()) {
            this.rebuildCurrentSessionHistory();
            tracked = editId != null ? this.trackedEdits.get(editId) : null;
            return tracked != null ? tracked : this.resolveTrackedEditByPayload(editId, toolCallId, path);
        }
        FileEditSnapshot snapshot = GsonUtils.fromJson(snapshotJson, FileEditSnapshot.class);
        if (snapshot == null || snapshot.getPath() == null || snapshot.getPath().isBlank()) {
            this.rebuildCurrentSessionHistory();
            tracked = editId != null ? this.trackedEdits.get(editId) : null;
            return tracked != null ? tracked : this.resolveTrackedEditByPayload(editId, toolCallId, path);
        }
        TrackedFileEdit restored = new TrackedFileEdit(snapshot.toPayload(),
                this.existsFlag(snapshot.getBeforeExists(), snapshot.getBeforeContent()),
                snapshot.getBeforeContent(),
                this.existsFlag(snapshot.getAfterExists(), snapshot.getAfterContent()),
                snapshot.getAfterContent(),
                ReadAction.compute(() -> findFile(snapshot.getPath())));
        this.trackedEdits.put(editId, restored);
        return restored;
    }

    /**
     * 重新加载当前会话的历史文件编辑快照。
     */
    private void rebuildCurrentSessionHistory() {
        if (this.project == null || this.sessionService == null) {
            return;
        }
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        String sessionId = state.getCurrentSessionId();
        String cliType = EasyAgentAppState.getInstance().getCurrentCliType();
        if (sessionId == null || sessionId.isBlank() || cliType == null || cliType.isBlank()) {
            return;
        }
        try {
            CLIType type = CLIType.valueOf(cliType);
            List<SessionMessage> messages = this.sessionService.readMessages(type, sessionId);
            this.trackHistoricalMessages(messages);
        } catch (Exception ignored) {
        }
    }

    /**
     * 通过工具调用元数据恢复编辑跟踪对象。
     *
     * @param editId     编辑 ID
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     * @return 跟踪对象；不存在时返回 {@code null}
     */
    private TrackedFileEdit resolveTrackedEditByPayload(String editId, String toolCallId, String path) {
        if ((toolCallId == null || toolCallId.isBlank()) && (path == null || path.isBlank())) {
            return null;
        }
        for (TrackedFileEdit tracked : this.trackedEdits.values()) {
            if (this.matchesLookup(tracked, toolCallId, path)) {
                if (editId != null && !editId.isBlank()) {
                    this.trackedEdits.putIfAbsent(editId, tracked);
                }
                return tracked;
            }
        }
        if (this.project == null) {
            return null;
        }
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        for (String snapshotJson : state.getFileEditSnapshots().values()) {
            if (snapshotJson == null || snapshotJson.isBlank()) {
                continue;
            }
            try {
                FileEditSnapshot snapshot = GsonUtils.fromJson(snapshotJson, FileEditSnapshot.class);
                if (snapshot == null || !this.matchesLookup(snapshot, toolCallId, path)) {
                    continue;
                }
                TrackedFileEdit restored = new TrackedFileEdit(snapshot.toPayload(),
                        this.existsFlag(snapshot.getBeforeExists(), snapshot.getBeforeContent()),
                        snapshot.getBeforeContent(),
                        this.existsFlag(snapshot.getAfterExists(), snapshot.getAfterContent()),
                        snapshot.getAfterContent(),
                        ReadAction.compute(() -> findFile(snapshot.getPath())));
                this.trackedEdits.put(snapshot.getEditId(), restored);
                if (editId != null && !editId.isBlank()) {
                    this.trackedEdits.putIfAbsent(editId, restored);
                }
                return restored;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 根据工具调用 ID 查找已跟踪的文件编辑。
     *
     * @param toolCallId 工具调用 ID
     * @return 跟踪对象；不存在时返回 {@code null}
     */
    private TrackedFileEdit findTrackedEditByToolCallId(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        for (TrackedFileEdit tracked : this.trackedEdits.values()) {
            if (tracked != null && tracked.payload != null && toolCallId.equals(tracked.payload.toolCallId())) {
                return tracked;
            }
        }
        if (this.project == null) {
            return null;
        }
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        for (String snapshotJson : state.getFileEditSnapshots().values()) {
            if (snapshotJson == null || snapshotJson.isBlank()) {
                continue;
            }
            try {
                FileEditSnapshot snapshot = GsonUtils.fromJson(snapshotJson, FileEditSnapshot.class);
                if (snapshot == null || !toolCallId.equals(snapshot.getToolCallId())) {
                    continue;
                }
                TrackedFileEdit restored = new TrackedFileEdit(snapshot.toPayload(),
                        this.existsFlag(snapshot.getBeforeExists(), snapshot.getBeforeContent()),
                        snapshot.getBeforeContent(),
                        this.existsFlag(snapshot.getAfterExists(), snapshot.getAfterContent()),
                        snapshot.getAfterContent(),
                        ReadAction.compute(() -> findFile(snapshot.getPath())));
                this.trackedEdits.put(snapshot.getEditId(), restored);
                return restored;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 匹配跟踪对象的工具调用元数据。
     *
     * @param tracked    跟踪对象
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     * @return 是否匹配
     */
    private boolean matchesLookup(TrackedFileEdit tracked, String toolCallId, String path) {
        if (tracked == null || tracked.payload == null) {
            return false;
        }
        return this.matchesLookup(tracked.payload, toolCallId, path);
    }

    /**
     * 匹配文件编辑元数据。
     *
     * @param payload    文件编辑元数据
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     * @return 是否匹配
     */
    private boolean matchesLookup(FileEditPayload payload, String toolCallId, String path) {
        if (payload == null) {
            return false;
        }
        boolean hasTool = toolCallId != null && !toolCallId.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        boolean toolMatch = hasTool && toolCallId.equals(payload.toolCallId());
        boolean pathMatch = hasPath && path.equals(payload.path());
        if (hasTool && hasPath) {
            return toolMatch && pathMatch;
        }
        return toolMatch || pathMatch;
    }

    /**
     * 匹配持久化快照元数据。
     *
     * @param snapshot   文件编辑快照
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     * @return 是否匹配
     */
    private boolean matchesLookup(FileEditSnapshot snapshot, String toolCallId, String path) {
        return snapshot != null && this.matchesLookup(snapshot.toPayload(), toolCallId, path);
    }

    /**
     * 载入项目持久化的文件编辑快照。
     */
    private void loadPersistedSnapshots() {
        if (this.project == null) {
            return;
        }
        EasyAgentState state = EasyAgentState.getInstance(this.project);
        for (String snapshotJson : state.getFileEditSnapshots().values()) {
            if (snapshotJson == null || snapshotJson.isBlank()) {
                continue;
            }
            try {
                FileEditSnapshot snapshot = GsonUtils.fromJson(snapshotJson, FileEditSnapshot.class);
                if (snapshot == null || snapshot.getEditId() == null || snapshot.getPath() == null) {
                    continue;
                }
                this.trackedEdits.put(snapshot.getEditId(), new TrackedFileEdit(snapshot.toPayload(),
                        this.existsFlag(snapshot.getBeforeExists(), snapshot.getBeforeContent()),
                        snapshot.getBeforeContent(),
                        this.existsFlag(snapshot.getAfterExists(), snapshot.getAfterContent()),
                        snapshot.getAfterContent(),
                        ReadAction.compute(() -> findFile(snapshot.getPath()))));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 将已完成的文件编辑快照持久化到项目状态。
     *
     * @param tracked 跟踪对象
     */
    private void persistTrackedEdit(TrackedFileEdit tracked) {
        if (this.project == null || tracked == null || tracked.payload == null) {
            return;
        }
        FileEditSnapshot snapshot = FileEditSnapshot.builder()
                .editId(tracked.payload.editId())
                .toolCallId(tracked.payload.toolCallId())
                .toolName(tracked.payload.toolName())
                .operation(tracked.payload.operation())
                .path(tracked.payload.path())
                .relativePath(tracked.payload.relativePath())
                .displayName(tracked.payload.displayName())
                .beforeExists(tracked.beforeExists)
                .beforeContent(tracked.beforeContent)
                .afterExists(tracked.afterExists)
                .afterContent(tracked.afterContent)
                .capturedAt(System.currentTimeMillis())
                .build();
        EasyAgentState.getInstance(this.project)
                .saveFileEditSnapshot(tracked.payload.editId(), GsonUtils.toJson(snapshot));
    }

    /**
     * 根据文件路径查找 VirtualFile。
     *
     * @param path 绝对路径
     * @return VirtualFile；不存在时返回 {@code null}
     */
    private static VirtualFile findFile(String path) {
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
    }

    /**
     * 读取文件当前文本。
     *
     * @param file 文件对象
     * @return 当前文本；不可读时返回 {@code null}
     */
    private static String readCurrentText(VirtualFile file) {
        if (file == null || !file.isValid() || file.isDirectory()) {
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
     * 创建用于 IDEA diff 的内容对象。
     *
     * @param factory Diff 内容工厂
     * @param file    关联文件
     * @param text    文本内容
     * @return diff 内容
     */
    private DiffContent createDiffContent(DiffContentFactory factory, VirtualFile file, String text) {
        String safeText = StringUtil.notNullize(text);
        return file != null
                ? factory.create(this.project, safeText, file)
                : factory.create(safeText);
    }

    /**
     * 兼容旧快照中不存在的存在性标记。
     *
     * @param existsFlag 序列化的存在标记
     * @param content    对应文本内容
     * @return 是否存在
     */
    private boolean existsFlag(Boolean existsFlag, String content) {
        return existsFlag != null ? existsFlag : content != null;
    }

    /**
     * 历史文件编辑解析结果。
     *
     * @param beforeContent 编辑前内容
     * @param afterContent  编辑后内容
     */
    private record HistoricalFileContents(String beforeContent, String afterContent) {
    }

    /**
     * 回撤 AI 新建的文件。
     *
     * @param file 当前文件
     */
    private void deleteCreatedFile(VirtualFile file) {
        if (file == null) {
            this.notify("The AI-created file has already been removed.", NotificationType.INFORMATION);
            return;
        }
        WriteCommandAction.runWriteCommandAction(this.project, "Revert AI File Creation", null, () -> {
            try {
                file.delete(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        this.notify("Reverted AI-created file.", NotificationType.INFORMATION);
    }

    /**
     * 确保回撤时目标文件存在。
     *
     * @param path 文件绝对路径
     * @return 创建或定位到的文件
     */
    private VirtualFile ensureFileExists(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        AtomicReference<VirtualFile> fileRef = new AtomicReference<>();
        WriteCommandAction.runWriteCommandAction(this.project, "Restore AI Edited File", null, () -> {
            try {
                VirtualFile existing = findFile(path);
                if (existing != null) {
                    fileRef.set(existing);
                    return;
                }
                Path nioPath = Path.of(path);
                String parentPath = nioPath.getParent() != null ? nioPath.getParent().toString() : null;
                VirtualFile parent = parentPath != null ? VfsUtil.createDirectoryIfMissing(parentPath) : null;
                if (parent == null || nioPath.getFileName() == null) {
                    return;
                }
                String name = nioPath.getFileName().toString();
                VirtualFile created = parent.findChild(name);
                fileRef.set(created != null ? created : parent.createChildData(this, name));
            } catch (Exception ignored) {
            }
        });
        return fileRef.get();
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

        /** AI 编辑前文件是否存在。 */
        private boolean beforeExists;

        /** AI 编辑前的文件内容。 */
        private String beforeContent;

        /** AI 编辑后的文件是否存在。 */
        private boolean afterExists;

        /** AI 编辑后的文件内容。 */
        private String afterContent;

        /** 当前定位到的文件对象。 */
        private VirtualFile file;

        /**
         * 构造跟踪对象。
         *
         * @param payload       编辑元数据
         * @param beforeExists  编辑前文件是否存在
         * @param beforeContent 编辑前文本
         * @param afterExists   编辑后文件是否存在
         * @param afterContent  编辑后文本
         */
        private TrackedFileEdit(FileEditPayload payload, boolean beforeExists, String beforeContent,
                                boolean afterExists, String afterContent, VirtualFile file) {
            this.payload = payload;
            this.beforeExists = beforeExists;
            this.beforeContent = beforeContent;
            this.afterExists = afterExists;
            this.afterContent = afterContent;
            this.file = file;
        }
    }
}
