package io.github.easyagent.ui.jcef.handler;

import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.session.SessionService;
import io.github.easyagent.settings.EasyAgentAppState;
import io.github.easyagent.settings.EasyAgentState;
import io.github.easyagent.settings.config.CliConfigService;
import io.github.easyagent.settings.mcp.McpConfigService;
import io.github.easyagent.settings.mcp.McpTestService;
import io.github.easyagent.settings.models.ModelConfigService;
import io.github.easyagent.settings.plugins.PluginsConfigService;
import io.github.easyagent.settings.skills.SkillsConfigService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.JsRequest;
import io.github.easyagent.ui.service.ChatManager;
import io.github.easyagent.ui.service.ChatUiBridgeService;
import io.github.easyagent.ui.service.FileEditService;
import io.github.easyagent.ui.service.FileReferenceService;
import io.github.easyagent.ui.service.command.SlashCommandService;
import io.github.easyagent.plan.PlanService;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Handler 共享上下文，封装所有 bridge 基础设施和服务引用。
 * <p>
 * 每个 {@link MessageHandler} 实现通过此上下文访问所需的服务和工具方法，
 * 避免 handler 直接依赖 {@link io.github.easyagent.ui.jcef.JCEFMessageBridge} 实例。
 * </p>
 *
 * @param browser              JCEF 浏览器实例
 * @param chatManager          对话管理器
 * @param sessionService       会话服务
 * @param modelConfigService   模型配置服务
 * @param cliConfigService     CLI 配置服务
 * @param mcpConfigService     MCP 配置服务
 * @param mcpTestService       MCP 测试服务
 * @param skillsConfigService  Skills 配置服务
 * @param pluginsConfigService Plugins 配置服务
 * @param project              当前 IDEA 项目
 * @param fileReferenceService 文件引用服务
 * @param chatUiBridgeService  聊天 UI 桥服务
 * @param fileEditService      文件编辑服务
 * @param slashCommandService  斜杠命令服务
 * @param planService          计划服务
 * @param asyncExecutor        异步线程池
 * @param historyCache         历史消息缓存
 * @param currentSessionId     当前会话 ID（可变）
 * @param currentProjectPath   当前项目路径（可变，可覆盖 project.getBasePath()）
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
@Slf4j
public record BridgeContext(
        JBCefBrowser browser,
        ChatManager chatManager,
        SessionService sessionService,
        ModelConfigService modelConfigService,
        CliConfigService cliConfigService,
        McpConfigService mcpConfigService,
        McpTestService mcpTestService,
        SkillsConfigService skillsConfigService,
        PluginsConfigService pluginsConfigService,
        Project project,
        FileReferenceService fileReferenceService,
        ChatUiBridgeService chatUiBridgeService,
        FileEditService fileEditService,
        SlashCommandService slashCommandService,
        PlanService planService,
        ExecutorService asyncExecutor,
        ConcurrentHashMap<String, String> historyCache,
        AtomicReference<String> currentSessionId,
        AtomicReference<String> currentProjectPath
) {

    private static final String JS_CALLBACK_PREFIX = "window.__ea_on";
    private static final String JS_EMPTY_ARRAY = "[]";

    /**
     * 获取 CEF 浏览器原生实例。
     *
     * @return CefBrowser 实例
     */
    public CefBrowser getCefBrowser() {
        return this.browser.getCefBrowser();
    }

    /**
     * 获取当前项目路径。
     * <p>
     * 优先返回通过 {@link #setProjectPath(String)} 设置的路径，
     * 未设置时回退到 {@code project.getBasePath()}。
     * </p>
     *
     * @return 项目根路径，可能为 null
     */
    public String getProjectPath() {
        String customPath = this.currentProjectPath.get();
        if (customPath != null) {
            return customPath;
        }
        return this.project != null ? this.project.getBasePath() : null;
    }

    /**
     * 设置当前项目路径覆盖值。
     *
     * @param path 项目路径
     */
    public void setProjectPath(String path) {
        this.currentProjectPath.set(path);
    }

    /**
     * 获取当前会话 ID。
     *
     * @return 当前会话 ID，可能为 null
     */
    public String getCurrentSessionId() {
        return this.currentSessionId.get();
    }

    /**
     * 设置当前会话 ID。
     *
     * @param sessionId 会话 ID
     */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId.set(sessionId);
    }

    /**
     * 获取 EasyAgentState 项目级状态。
     *
     * @return EasyAgentState 实例
     */
    public EasyAgentState getState() {
        return this.project != null ? this.project.getService(EasyAgentState.class) : null;
    }

    /**
     * 获取 EasyAgentAppState 应用级状态。
     *
     * @return EasyAgentAppState 实例
     */
    public EasyAgentAppState getAppState() {
        return EasyAgentAppState.getInstance();
    }

    /**
     * 调用前端 JS 全局回调函数（传入已序列化的 JSON）。
     *
     * @param callback 回调枚举
     * @param data     已序列化的 JSON 数据
     */
    public void invokeJSCallback(JsCallback callback, String data) {
        String name = callback.getValue();
        String js = JS_CALLBACK_PREFIX + name + "&&" + JS_CALLBACK_PREFIX + name + "(" + data + ")";
        this.executeJS(js);
    }

    /**
     * 调用前端 JS 全局回调函数（自动序列化）。
     *
     * @param callback 回调枚举
     * @param data     待序列化的对象
     */
    public void invokeJSCallback(JsCallback callback, Object data) {
        this.invokeJSCallback(callback, GsonUtils.toJson(data));
    }

    /**
     * 在浏览器中执行 JavaScript 代码。
     *
     * @param js 要执行的 JavaScript 代码
     */
    public void executeJS(String js) {
        try {
            this.getCefBrowser().executeJavaScript(js, "", 0);
        } catch (Exception e) {
            log.warn("Failed to execute JS: {}", e.getMessage());
        }
    }

    /**
     * 注册一个 JS 请求处理器到给定的处理器映射中。
     *
     * @param handlers    处理器映射
     * @param action      JS 动作
     * @param requestType 请求实体类型
     * @param consumer    处理逻辑
     * @param <T>         请求实体类型
     */
    @SuppressWarnings("unchecked")
    public <T extends JsRequest> void registerHandler(Map<JsAction, ? super QueryHandlerRecord<T>> handlers,
                                                       JsAction action, Class<T> requestType,
                                                       Consumer<T> consumer) {
        ((Map<JsAction, QueryHandlerRecord<T>>) handlers).put(action, new QueryHandlerRecord<>(requestType, consumer));
    }
}
