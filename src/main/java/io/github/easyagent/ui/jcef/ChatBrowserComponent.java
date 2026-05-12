package io.github.easyagent.ui.jcef;

import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import io.github.easyagent.ui.service.ChatManager;
import lombok.extern.slf4j.Slf4j;
import org.cef.CefApp;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * JCEF 浏览器组件封装。
 * <p>
 * 通过注册自定义 scheme（http://easyagent.local）从 classpath 加载前端资源，
 * 同时将 .vue.html 模板内联注入 index.html，避免额外的文件请求。
 * </p>
 * <p>
 * 开发模式（runIde）通过 -Deasyagent.project.dir 系统属性识别，
 * 使用 build/resources/main/web 目录加载。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class ChatBrowserComponent {

    private static final String SCHEME = "http";
    private static final String DOMAIN = "easyagent.local";
    private static final String BASE_URL = SCHEME + "://" + DOMAIN + "/";
    private static final String WEB_RESOURCE_PREFIX = "web";
    private static final String TEMPLATES_PLACEHOLDER = "/*__EA_TEMPLATES_PLACEHOLDER__*/";
    private static final List<String> TEMPLATE_RESOURCE_PATHS = List.of(
            "js/chat/ChatHeader.vue.html",
            "js/chat/ChatView.vue.html",
            "js/chat/EmptyState.vue.html",
            "js/chat/InputBar.vue.html",
            "js/chat/PendingQueue.vue.html",
            "js/message/ErrorBlock.vue.html",
            "js/message/MessageBubble.vue.html",
            "js/message/StepInfo.vue.html",
            "js/message/ThinkingBlock.vue.html",
            "js/message/TodoListBlock.vue.html",
            "js/message/ToolCallBlock.vue.html",
            "js/plan/PlanView.vue.html",
            "js/settings/SettingsPage.vue.html"
    );

    private final JBCefBrowser browser;
    private final JCEFMessageBridge cefBridge;

    private static volatile boolean schemeRegistered = false;

    public ChatBrowserComponent(Project project) {
        if (!JBCefApp.isSupported()) {
            throw new UnsupportedOperationException("JCEF is not supported in this environment");
        }

        this.browser = new JBCefBrowser();
        ChatManager chatManager = new ChatManager();
        this.cefBridge = new JCEFMessageBridge(this.browser, chatManager, project);
        this.cefBridge.installJSBridge();

        if (project != null && project.getBasePath() != null) {
            this.cefBridge.setProjectPath(project.getBasePath());
        }

        this.registerSchemeHandler();
        this.loadIndexHTML();
    }

    public JComponent getComponent() {
        return this.browser.getComponent();
    }

    public JCEFMessageBridge getCefBridge() {
        return this.cefBridge;
    }

    public void dispose() {
        this.cefBridge.dispose();
    }

    private synchronized void registerSchemeHandler() {
        if (schemeRegistered) return;
        try {
            CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, DOMAIN, new EasyAgentSchemeHandlerFactory());
            schemeRegistered = true;
            log.info("Registered JCEF scheme handler: {}://{}", SCHEME, DOMAIN);
        } catch (Exception e) {
            log.error("Failed to register scheme handler", e);
        }
    }

    private void loadIndexHTML() {
        try {
            String indexContent = this.readResource(WEB_RESOURCE_PREFIX + "/index.html");
            if (indexContent == null) {
                log.error("Cannot read index.html from classpath");
                this.loadFallbackHTML();
                return;
            }

            Map<String, String> templates = this.collectTemplates();
            String templatesJson = this.buildTemplatesJson(templates);
            indexContent = indexContent.replace(TEMPLATES_PLACEHOLDER,
                    "window.__EA_TEMPLATES = " + templatesJson + ";");

            indexContent = this.rewriteResourcePaths(indexContent);

            String devMode = System.getProperty("easyagent.project.dir");
            if (devMode != null) {
                indexContent = indexContent.replace("<body>",
                        "<body>\n    <script>window.__EA_DEV_MODE__ = true;</script>");
            }

            this.browser.loadHTML(indexContent);
            log.info("Loaded web UI with {} templates", templates.size());
        } catch (Exception e) {
            log.error("Failed to load web UI", e);
            this.loadFallbackHTML();
        }
    }

    private String rewriteResourcePaths(String html) {
        html = html.replace("href=\"css/", "href=\"" + BASE_URL + "css/");
        html = html.replace("href=\"lib/", "href=\"" + BASE_URL + "lib/");
        html = html.replace("src=\"lib/", "src=\"" + BASE_URL + "lib/");
        html = html.replace("src=\"js/", "src=\"" + BASE_URL + "js/");
        return html;
    }

    private String readResource(String resourcePath) {
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> collectTemplates() {
        Map<String, String> templates = new LinkedHashMap<>();
        for (String relativePath : TEMPLATE_RESOURCE_PATHS) {
            String resourcePath = WEB_RESOURCE_PREFIX + "/" + relativePath;
            String content = this.readResource(resourcePath);
            if (content == null) {
                log.warn("Missing template resource: {}", resourcePath);
                continue;
            }
            templates.put(relativePath, unwrapTemplate(content));
        }

        if (templates.isEmpty()) {
            log.error("Collected 0 templates from classpath. Expected {} resources under {}/",
                    TEMPLATE_RESOURCE_PATHS.size(), WEB_RESOURCE_PREFIX);
        }

        log.info("Collected {} templates", templates.size());
        return templates;
    }

    private String buildTemplatesJson(Map<String, String> templates) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append("\"").append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unwrapTemplate(String html) {
        String trimmed = html.trim();
        String startTag = "<template>";
        String endTag = "</template>";
        if (trimmed.startsWith(startTag) && trimmed.endsWith(endTag)) {
            return trimmed.substring(startTag.length(), trimmed.length() - endTag.length()).trim();
        }
        return trimmed;
    }

    private void loadFallbackHTML() {
        String html = """
                <html><head><meta charset="UTF-8">
                <style>
                    body{font-family:-apple-system,sans-serif;display:flex;justify-content:center;
                    align-items:center;height:100vh;margin:0;background:#0F172A;color:#94A3B8;}
                    .container{text-align:center;}
                    h2{color:#F1F5F9;font-size:18px;margin-bottom:8px;}
                    p{font-size:13px;}
                </style></head><body>
                <div class="container"><h2>EasyAgent</h2>
                <p>Failed to load UI resources.<br>Please restart the IDE.</p></div>
                </body></html>
                """;
        this.browser.loadHTML(html);
    }

    static class EasyAgentSchemeHandlerFactory implements CefSchemeHandlerFactory {
        @Override
        public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
            return new ClasspathResourceHandler();
        }
    }

    static class ClasspathResourceHandler implements CefResourceHandler {

        private InputStream inputStream;
        private String mimeType;
        private int contentLength;

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            try {
                String resourcePath = urlToResourcePath(request.getURL());
                if (resourcePath == null) return false;

                InputStream is = ChatBrowserComponent.class.getClassLoader().getResourceAsStream(resourcePath);
                if (is == null) return false;

                this.inputStream = is;
                this.mimeType = guessMimeType(resourcePath);
                this.contentLength = is.available();
                callback.Continue();
                return true;
            } catch (Exception e) {
                log.error("Error processing request: {}", request.getURL(), e);
                return false;
            }
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setMimeType(this.mimeType);
            response.setStatus(200);
            responseLength.set(this.contentLength);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            try {
                if (this.inputStream == null) return false;
                int read = this.inputStream.read(dataOut, 0, bytesToRead);
                if (read <= 0) {
                    this.inputStream.close();
                    this.inputStream = null;
                    return false;
                }
                bytesRead.set(read);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void cancel() {
            if (this.inputStream != null) {
                try { this.inputStream.close(); } catch (IOException ignored) {}
                this.inputStream = null;
            }
        }

        private static String urlToResourcePath(String url) {
            if (!url.startsWith(BASE_URL)) return null;
            String path = url.substring(BASE_URL.length());
            int idx = path.indexOf('?');
            if (idx >= 0) path = path.substring(0, idx);
            idx = path.indexOf('#');
            if (idx >= 0) path = path.substring(0, idx);
            if (path.isEmpty()) path = "index.html";
            return WEB_RESOURCE_PREFIX + "/" + path;
        }

        private static String guessMimeType(String path) {
            if (path.endsWith(".html")) return "text/html";
            if (path.endsWith(".css")) return "text/css";
            if (path.endsWith(".js")) return "application/javascript";
            if (path.endsWith(".json")) return "application/json";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".woff") || path.endsWith(".woff2")) return "font/woff2";
            if (path.endsWith(".ttf")) return "font/ttf";
            return "application/octet-stream";
        }
    }
}
