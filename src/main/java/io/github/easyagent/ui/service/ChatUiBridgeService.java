package io.github.easyagent.ui.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import io.github.easyagent.ui.jcef.JCEFMessageBridge;
import io.github.easyagent.ui.service.entity.FileReferencePayload;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EasyAgent UI 桥接协调服务。
 * <p>
 * 负责在 IDEA 原生动作、项目树/编辑器右键菜单和 JCEF 前端之间转发文件引用插入事件，
 * 并确保需要时自动激活 EasyAgent 工具窗口。
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Service(Service.Level.PROJECT)
public final class ChatUiBridgeService {

    /** EasyAgent ToolWindow ID。 */
    private static final String TOOL_WINDOW_ID = "EasyAgent";

    /** 当前 IDEA 项目。 */
    private final Project project;

    /** 当前活跃的 JCEF 消息桥。 */
    private final AtomicReference<JCEFMessageBridge> bridgeRef = new AtomicReference<>();

    /** 桥接未就绪时缓存的文件引用批次。 */
    private final Queue<List<FileReferencePayload>> pendingReferenceBatches = new ConcurrentLinkedQueue<>();

    /**
     * 构造 UI 协调服务。
     *
     * @param project 当前 IDEA 项目
     */
    public ChatUiBridgeService(Project project) {
        this.project = project;
    }

    /**
     * 注册当前活跃的 JCEF 消息桥。
     *
     * @param bridge JCEF 消息桥
     */
    public void registerBridge(JCEFMessageBridge bridge) {
        this.bridgeRef.set(bridge);
        this.flushPendingReferences();
    }

    /**
     * 注销当前 JCEF 消息桥。
     *
     * @param bridge JCEF 消息桥
     */
    public void unregisterBridge(JCEFMessageBridge bridge) {
        this.bridgeRef.compareAndSet(bridge, null);
    }

    /**
     * 将文件引用插入当前聊天输入框。
     *
     * @param references 文件引用列表
     */
    public void insertFileReferences(List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            this.activateToolWindow();
            JCEFMessageBridge bridge = this.bridgeRef.get();
            if (bridge == null) {
                this.pendingReferenceBatches.add(references);
                return;
            }
            bridge.pushFileReferences(references);
        });
    }

    /**
     * 刷新桥接建立前积压的文件引用事件。
     */
    private void flushPendingReferences() {
        JCEFMessageBridge bridge = this.bridgeRef.get();
        if (bridge == null) {
            return;
        }

        List<FileReferencePayload> references;
        while ((references = this.pendingReferenceBatches.poll()) != null) {
            bridge.pushFileReferences(references);
        }
    }

    /**
     * 激活 EasyAgent 工具窗口。
     */
    private void activateToolWindow() {
        ToolWindow toolWindow = ToolWindowManager.getInstance(this.project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null, true);
        }
    }
}
