package io.github.easyagent.ui.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 从项目树右键菜单插入文件引用。
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public class InsertFileReferenceFromProjectViewAction extends DumbAwareAction {

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
        if (project == null) {
            return;
        }

        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        List<FileReferencePayload> references = project.getService(FileReferenceService.class).createReferences(files);
        if (references.isEmpty()) {
            return;
        }

        project.getService(ChatUiBridgeService.class).insertFileReferences(references);
    }

    /**
     * 更新菜单显示状态。
     *
     * @param e IDEA ActionEvent
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean visible = project != null && files != null && files.length > 0;
        e.getPresentation().setEnabledAndVisible(visible);
    }
}
