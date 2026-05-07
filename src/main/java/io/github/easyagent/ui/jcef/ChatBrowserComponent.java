package io.github.easyagent.ui.jcef;

import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import io.github.easyagent.ui.service.ChatManager;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * JCEF 浏览器组件封装。
 * <p>
 * 管理 {@link JBCefBrowser} 实例的生命周期，从插件资源目录加载前端页面，
 * 并提供 Java-JavaScript 双向通信桥接。
 * </p>
 * <p>
 * 资源加载策略：优先尝试直接通过 classpath URL 加载（开发模式），
 * 若失败则将 {@code /web/} 目录下所有资源解压到系统临时目录后通过
 * {@code file://} URL 加载（生产模式）。
 * 所有 {@code .vue.html} 模板会内联注入到页面中，避免 file:// 协议下
 * fetch/XHR 无法加载的问题。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class ChatBrowserComponent {

    /** 资源目录前缀。 */
    private static final String WEB_RESOURCE_PREFIX = "web";

    /** 临时目录中的子目录名。 */
    private static final String TEMP_DIR_NAME = "easyagent-web";

    /** 前端入口文件名。 */
    private static final String INDEX_HTML = "index.html";

    /** 运行期生成的 HTML 文件名。 */
    private static final String GENERATED_INDEX_HTML = "index.generated.html";

    /** URL 协议：文件系统。 */
    private static final String PROTOCOL_FILE = "file";

    /** URL 协议：JAR 包。 */
    private static final String PROTOCOL_JAR = "jar";

    /** JAR URL 中路径分隔符。 */
    private static final String JAR_FILE_PREFIX = "file:";

    /** JAR URL 中 '!」 的分隔符。 */
    private static final String JAR_ENTRY_SEPARATOR = "!";

    /** 资源路径分隔符。 */
    private static final String PATH_SEPARATOR = "/";

    /** 模板注入占位标记。 */
    private static final String TEMPLATES_PLACEHOLDER = "/*__EA_TEMPLATES_PLACEHOLDER__*/";

    /** 模板外层开始标签。 */
    private static final String TEMPLATE_WRAPPER_START = "<template>";

    /** 模板外层结束标签。 */
    private static final String TEMPLATE_WRAPPER_END = "</template>";

    private final JBCefBrowser browser;

    private final JCEFMessageBridge cefBridge;

    /**
     * 构造 JCEF 浏览器组件。
     *
     * @param project 当前 IDEA 项目，用于获取项目路径
     * @throws UnsupportedOperationException 当运行环境不支持 JCEF 时抛出
     */
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

        this.loadIndexHTML();
    }

    /**
     * 获取浏览器 Swing 组件。
     *
     * @return JCEF 浏览器的 Swing 组件
     */
    public JComponent getComponent() {
        return this.browser.getComponent();
    }

    /**
     * 获取 Java-JavaScript 通信桥实例。
     *
     * @return 通信桥实例
     */
    public JCEFMessageBridge getCefBridge() {
        return this.cefBridge;
    }

    /**
     * 释放浏览器和通信桥资源。
     */
    public void dispose() {
        this.cefBridge.dispose();
        this.browser.dispose();
    }

    /**
     * 从插件资源目录加载 index.html 到浏览器。
     * <p>
     * 按以下优先级尝试加载：
     * <ol>
     *   <li>直接通过 classpath URL 加载（IDE 开发运行模式）</li>
     *   <li>将资源解压到临时目录后通过 {@code file://} URL 加载</li>
     *   <li>加载兜底错误页面</li>
     * </ol>
     * </p>
     */
    private void loadIndexHTML() {
        try {
            URL directUrl = this.getClass().getClassLoader()
                    .getResource(WEB_RESOURCE_PREFIX + PATH_SEPARATOR + INDEX_HTML);
            if (directUrl != null && PROTOCOL_FILE.equals(directUrl.getProtocol())) {
                try {
                    Path webDir = Path.of(directUrl.toURI()).getParent();
                    this.loadWithInlineTemplates(webDir);
                    return;
                } catch (Exception e) {
                    log.warn("Failed to load with inline templates from classpath, falling back to loadURL", e);
                    this.browser.loadURL(directUrl.toExternalForm());
                    return;
                }
            }

            Path tempDir = this.extractWebResources();
            this.loadWithInlineTemplates(tempDir);

        } catch (Exception e) {
            log.error("Failed to load web UI", e);
            this.loadFallbackHTML();
        }
    }

    /**
     * 加载 index.html 并将所有 .vue.html 模板内联注入到页面中。
     * <p>
     * 读取 index.html 内容，查找 {@link #TEMPLATES_PLACEHOLDER} 占位标记，
     * 将所有模板文件内容作为 {@code window.__EA_TEMPLATES} JSON 对象替换进去，
     * 然后写入运行期生成的 HTML 文件并通过 {@code loadURL} 加载。
     * 这样前端 TemplateLoader 可以直接从内存读取，同时确保相对路径
     * JS/CSS 资源在 JCEF 中按真实 file:// 页面稳定解析。
     * </p>
     *
     * @param tempDir 解压后的资源根目录
     * @throws IOException 读取文件失败时抛出
     */
    private void loadWithInlineTemplates(Path tempDir) throws IOException {
        Path indexFile = tempDir.resolve(INDEX_HTML);
        String html = Files.readString(indexFile, StandardCharsets.UTF_8);

        Map<String, String> templates = this.collectTemplates(tempDir);
        String templatesJson = this.buildTemplatesJson(templates);
        String replacement = "window.__EA_TEMPLATES = " + templatesJson + ";";

        html = html.replace(TEMPLATES_PLACEHOLDER, replacement);

        Path generatedIndexFile = tempDir.resolve(GENERATED_INDEX_HTML);
        Files.writeString(generatedIndexFile, html, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        String generatedUrl = generatedIndexFile.toUri().toURL().toExternalForm();
        this.browser.loadURL(generatedUrl);
        log.info("Loaded web UI with {} inline templates, generatedUrl={}", templates.size(), generatedUrl);
    }

    /**
     * 收集临时目录下所有 .vue.html 模板文件内容。
     *
     * @param tempDir 资源根目录
     * @return 模板路径到内容的映射
     * @throws IOException 读取文件失败时抛出
     */
    private Map<String, String> collectTemplates(Path tempDir) throws IOException {
        Map<String, String> templates = new HashMap<>();
        this.collectTemplatesRecursive(tempDir, tempDir, templates);
        return templates;
    }

    /**
     * 递归收集目录下的 .vue.html 文件。
     *
     * @param baseDir   根目录（用于计算相对路径）
     * @param currentDir 当前遍历目录
     * @param collector  模板收集映射
     * @throws IOException 读取文件失败时抛出
     */
    private void collectTemplatesRecursive(Path baseDir, Path currentDir, Map<String, String> collector) throws IOException {
        try (var stream = Files.list(currentDir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    this.collectTemplatesRecursive(baseDir, path, collector);
                } else if (path.getFileName().toString().endsWith(".vue.html")) {
                    String relativePath = baseDir.relativize(path).toString().replace('\\', '/');
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String cleaned = this.unwrapTemplate(content);
                    collector.put(relativePath, cleaned);
                }
            }
        }
    }

    /**
     * 仅移除组件文件最外层的 template 包裹，保留内部合法的 Vue template 语法。
     *
     * @param content 模板原始内容
     * @return 去掉最外层包裹后的模板内容
     */
    private String unwrapTemplate(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith(TEMPLATE_WRAPPER_START) && trimmed.endsWith(TEMPLATE_WRAPPER_END)) {
            return trimmed.substring(TEMPLATE_WRAPPER_START.length(),
                    trimmed.length() - TEMPLATE_WRAPPER_END.length()).trim();
        }
        return trimmed;
    }

    /**
     * 构建模板 JSON 字符串。
     * <p>
     * 手动构建 JSON 避免引入额外依赖，对模板内容中的特殊字符进行转义。
     * </p>
     *
     * @param templates 模板映射
     * @return JSON 字符串
     */
    private String buildTemplatesJson(Map<String, String> templates) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"");
            sb.append(this.escapeJson(entry.getKey()));
            sb.append("\":\"");
            sb.append(this.escapeJson(entry.getValue()));
            sb.append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 将 {@code /web/} 资源目录解压到系统临时目录。
     * <p>
     * 解压后文件位于 {@code ${java.io.tmpdir}/easyagent-web/} 目录下。
     * </p>
     *
     * @return 解压后的根目录路径
     * @throws IOException 解压失败时抛出
     */
    private Path extractWebResources() throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        Files.createDirectories(tempDir);

        Set<String> resourcePaths = this.listWebResources();
        int prefixLength = WEB_RESOURCE_PREFIX.length() + 1;
        for (String resourcePath : resourcePaths) {
            Path targetFile = tempDir.resolve(resourcePath.substring(prefixLength));
            Files.createDirectories(targetFile.getParent());
            try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        return tempDir;
    }

    /**
     * 列出 classpath 中 {@code web/} 前缀下的所有资源路径。
     *
     * @return 资源路径集合
     * @throws IOException 读取资源失败时抛出
     */
    private Set<String> listWebResources() throws IOException {
        Set<String> paths = new HashSet<>();
        Enumeration<URL> resources = this.getClass().getClassLoader().getResources(WEB_RESOURCE_PREFIX);
        while (resources.hasMoreElements()) {
            URL resourceUrl = resources.nextElement();
            switch (resourceUrl.getProtocol()) {
                case PROTOCOL_FILE -> this.listFileResources(resourceUrl, paths);
                case PROTOCOL_JAR -> this.listJarEntries(resourceUrl, paths);
                default -> { /* ignore other protocols */ }
            }
        }
        return paths;
    }

    /**
     * 列出文件系统目录下的资源路径。
     *
     * @param resourceUrl 资源 URL
     * @param collector   路径收集集合
     * @throws IOException 遍历失败时抛出
     */
    private void listFileResources(URL resourceUrl, Set<String> collector) throws IOException {
        try {
            Path dir = Path.of(resourceUrl.toURI());
            this.listFilesRecursive(dir, WEB_RESOURCE_PREFIX, collector);
        } catch (URISyntaxException e) {
            log.warn("Invalid resource URI: {}", resourceUrl, e);
        }
    }

    /**
     * 递归列出文件系统目录下的资源路径。
     *
     * @param dir       目录路径
     * @param prefix    资源路径前缀
     * @param collector 路径收集集合
     * @throws IOException 遍历失败时抛出
     */
    private void listFilesRecursive(Path dir, String prefix, Set<String> collector) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String resourcePath = prefix + PATH_SEPARATOR + path.getFileName();
                if (Files.isDirectory(path)) {
                    this.listFilesRecursive(path, resourcePath, collector);
                } else {
                    collector.add(resourcePath);
                }
            }
        }
    }

    /**
     * 列出 JAR 包中 {@code web/} 前缀下的资源路径。
     *
     * @param jarUrl    JAR 资源 URL
     * @param collector 路径收集集合
     * @throws IOException 读取 JAR 失败时抛出
     */
    private void listJarEntries(URL jarUrl, Set<String> collector) throws IOException {
        String jarPath = extractJarFilePath(jarUrl.getPath());
        String resourcePrefix = WEB_RESOURCE_PREFIX + PATH_SEPARATOR;

        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(resourcePrefix) && !entry.isDirectory()) {
                    collector.add(name);
                }
            }
        }
    }

    /**
     * 从 JAR URL 路径中提取实际文件路径。
     * <p>
     * 处理格式：{@code file:/path/to/jar!/sub/path} → {@code /path/to/jar}
     * </p>
     *
     * @param rawPath JAR URL 的原始路径
     * @return JAR 文件的实际文件系统路径
     */
    private static String extractJarFilePath(String rawPath) {
        String path = rawPath;
        if (path.startsWith(JAR_FILE_PREFIX)) {
            path = path.substring(JAR_FILE_PREFIX.length());
        }
        int bangIndex = path.indexOf(JAR_ENTRY_SEPARATOR);
        if (bangIndex > 0) {
            path = path.substring(0, bangIndex);
        }
        return path;
    }

    /**
     * 加载兜底错误页面。
     */
    private void loadFallbackHTML() {
        String fallbackHtml = """
                <html><head><meta charset="UTF-8">
                <style>
                    body{font-family:-apple-system,sans-serif;display:flex;justify-content:center;
                    align-items:center;height:100vh;margin:0;background:#0F172A;color:#94A3B8;}
                    .container{text-align:center;}
                    h2{color:#F1F5F9;font-size:18px;margin-bottom:8px;}
                    p{font-size:13px;}
                </style></head><body>
                <div class="container"><h2>🤖 EasyAgent</h2>
                <p>Failed to load UI resources.<br>Please restart the IDE.</p></div>
                </body></html>
                """;
        this.browser.loadHTML(fallbackHtml);
    }
}
