package io.github.easyagent.ui.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 从编辑器右键菜单插入文件引用。
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public class InsertFileReferenceFromEditorAction extends DumbAwareAction {

    /**
     * 获取 action 更新线程。
     *
     * @return BGT
     */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 执行插入文件引用动作。
     *
     * @param e IDEA ActionEvent
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }

        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        FileReferencePayload reference = project.getService(FileReferenceService.class).createReference(editor, file);
        if (reference == null) {
            return;
        }

        project.getService(ChatUiBridgeService.class).insertFileReferences(List.of(reference));
    }

    /**
     * 更新菜单显示状态。
     *
     * @param e IDEA ActionEvent
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean visible = project != null && editor != null;
        e.getPresentation().setEnabledAndVisible(visible);
    }
}
