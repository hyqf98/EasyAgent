package io.github.easyagent.ui.toolwindow;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import io.github.easyagent.ui.jcef.ChatBrowserComponent;
import org.jetbrains.annotations.NotNull;

/**
 * EasyAgent 工具窗口工厂。
 * <p>
 * 在 IDEA 右侧注册 AI 对话面板。整个面板由 JCEF 浏览器渲染，
 * 所有 UI 交互（会话列表、CLI 切换、消息展示）均在前端 Vue3 应用中完成。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class EasyAgentToolWindowFactory implements ToolWindowFactory, DumbAware {

    /**
     * 创建工具窗口内容。
     *
     * @param project    当前 IDEA 项目
     * @param toolWindow 工具窗口实例
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ChatBrowserComponent browserComponent = new ChatBrowserComponent(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(browserComponent.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * 工具窗口是否默认可用。
     *
     * @param project 当前 IDEA 项目
     * @return 始终返回 {@code true}
     */
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
