package io.github.easyagent.settings.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Plugin 插件配置管理服务。
 * <p>
 * 负责 Claude / OpenCode / Codex 三个 CLI 的 Plugin 读取、安装和删除，
 * 同时提供浏览 GitHub 仓库可用插件的能力。
 * </p>
 *
 * <h3>Plugin 目录路径</h3>
 * <table>
 *   <tr><th>CLI</th><th>用户级路径</th><th>项目级路径</th></tr>
 *   <tr><td>Claude</td><td>{@code ~/.claude/plugins/}</td><td>不支持</td></tr>
 *   <tr><td>OpenCode</td><td>{@code ~/.config/opencode/plugins/}</td><td>{@code <project>/.opencode/plugins/}</td></tr>
 *   <tr><td>Codex</td><td>{@code ~/.codex/plugins/}</td><td>不支持</td></tr>
 * </table>
 *
 * @author haijun
 * @date 2026/5/13
 * @since 1.0.0
 */
@Slf4j
public class PluginsConfigService {

    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private static final Path CLAUDE_PLUGINS_DIR = HOME.resolve(".claude").resolve("plugins");
    private static final Path OPENCODE_PLUGINS_DIR = HOME.resolve(".config").resolve("opencode").resolve("plugins");
    private static final Path CODEX_PLUGINS_DIR = HOME.resolve(".codex").resolve("plugins");
    private static final Path OPENCODE_CONFIG = HOME.resolve(".config").resolve("opencode").resolve("opencode.json");

    private static final String CLAUDE_PLUGIN_MANIFEST = ".claude-plugin";
    private static final String CODEX_PLUGIN_MANIFEST = ".codex-plugin";
    private static final String PLUGIN_JSON = "plugin.json";
    private static final String INSTALLED_PLUGINS_FILE = "installed_plugins.json";
    private static final String KNOWN_MARKETPLACES_FILE = "known_marketplaces.json";

    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String projectPath;

    public PluginsConfigService(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * 加载指定 CLI 类型的所有已安装插件。
     *
     * @param cliType CLI 类型：CLAUDE / OPENCODE / CODEX
     * @return 插件条目列表
     */
    public List<PluginEntry> loadInstalledPlugins(String cliType) {
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> this.loadClaudePlugins();
            case "OPENCODE" -> this.loadOpenCodePlugins();
            case "CODEX" -> this.loadCodexPlugins();
            default -> List.of();
        };
    }

    /**
     * 从 GitHub 安装插件到指定 CLI。
     *
     * @param cliType     CLI 类型
     * @param githubUrl   GitHub 仓库地址或 owner/repo 格式
     * @param pluginName  插件名称（安装后的目录名）
     * @param scope       安装作用域：user 或 project
     * @return 安装结果
     */
    public InstallResult installPlugin(String cliType, String githubUrl, String pluginName, String scope) {
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> this.installClaudePlugin(githubUrl, pluginName);
            case "OPENCODE" -> this.installOpenCodePlugin(githubUrl, pluginName, scope);
            case "CODEX" -> this.installCodexPlugin(githubUrl, pluginName);
            default -> new InstallResult(false, "Unsupported CLI type: " + cliType, null);
        };
    }

    /**
     * 删除指定插件。
     *
     * @param cliType     CLI 类型
     * @param pluginName  插件名称
     * @param installPath 插件安装目录绝对路径
     * @return 删除结果
     */
    public DeleteResult deletePlugin(String cliType, String pluginName, String installPath) {
        if (installPath == null || installPath.isBlank()) {
            return new DeleteResult(false, "Plugin install path is empty");
        }
        try {
            Path dir = Path.of(installPath);
            if (!Files.exists(dir)) {
                return new DeleteResult(false, "Plugin directory not found: " + installPath);
            }
            this.deleteDirectory(dir);
            if ("CLAUDE".equalsIgnoreCase(cliType)) {
                this.removeFromClaudeManifest(pluginName, installPath);
            }
            return new DeleteResult(true, "Deleted: " + installPath);
        } catch (Exception e) {
            log.warn("删除 Plugin 失败: {}", installPath, e);
            return new DeleteResult(false, "Delete failed: " + e.getMessage());
        }
    }

    /**
     * 读取指定插件的 README.md 内容。
     *
     * @param installPath 插件安装目录绝对路径
     * @return README.md 内容，不存在返回 null
     */
    public String readPluginContent(String installPath) {
        if (installPath == null || installPath.isBlank()) {
            return null;
        }
        Path dir = Path.of(installPath);
        for (String readmeName : new String[]{"README.md", "readme.md", "Readme.md"}) {
            Path readme = dir.resolve(readmeName);
            if (Files.exists(readme)) {
                try {
                    return Files.readString(readme);
                } catch (IOException e) {
                    log.warn("读取 Plugin 内容失败: {}", readme, e);
                }
            }
        }
        return null;
    }

    /**
     * 保存 Plugin 内容到 README.md 文件。
     *
     * @param installPath 插件安装目录路径
     * @param content     要保存的 Markdown 内容
     * @return 是否保存成功
     */
    public boolean savePluginContent(String installPath, String content) {
        if (installPath == null || installPath.isBlank()) {
            return false;
        }
        Path dir = Path.of(installPath);
        Path targetFile = null;
        for (String readmeName : new String[]{"README.md", "readme.md", "Readme.md"}) {
            Path readme = dir.resolve(readmeName);
            if (Files.exists(readme)) {
                targetFile = readme;
                break;
            }
        }
        if (targetFile == null) {
            targetFile = dir.resolve("README.md");
        }
        try {
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, content != null ? content : "");
            return true;
        } catch (IOException e) {
            log.warn("保存 Plugin 内容失败: {}", targetFile, e);
            return false;
        }
    }

    /**
     * 读取插件的命令列表。
     * <p>
     * 从 plugin.json 的 {@code commands} 数组中提取命令信息，
     * 同时也会检查 Claude installed_plugins.json 中的命令配置。
     * </p>
     *
     * @param installPath 插件安装路径
     * @return 命令信息列表，每个命令包含 name、description 字段
     */
    public List<Map<String, String>> readPluginCommands(String installPath) {
        if (installPath == null || installPath.isBlank()) {
            return List.of();
        }
        List<Map<String, String>> commands = new ArrayList<>();
        Path dir = Path.of(installPath);

        this.readCommandsFromPluginJson(dir, commands);

        if (commands.isEmpty() && Files.isDirectory(dir)) {
            this.readCommandsFromClaudeManifest(dir, commands);
        }

        if (commands.isEmpty() && Files.isDirectory(dir)) {
            this.readCommandsFromOpenCodePlugin(dir, commands);
        }

        return commands;
    }

    private void readCommandsFromPluginJson(Path pluginDir, List<Map<String, String>> commands) {
        String[] searchPaths = {
                CLAUDE_PLUGIN_MANIFEST + "/" + PLUGIN_JSON,
                CODEX_PLUGIN_MANIFEST + "/" + PLUGIN_JSON,
                PLUGIN_JSON
        };
        for (String searchPath : searchPaths) {
            Path jsonFile = pluginDir.resolve(searchPath);
            if (!Files.isRegularFile(jsonFile)) {
                continue;
            }
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
                this.extractCommands(obj, commands);
                if (!commands.isEmpty()) {
                    return;
                }
            } catch (Exception e) {
                log.debug("读取 plugin.json 命令失败: {}", jsonFile, e);
            }
        }
    }

    private void readCommandsFromClaudeManifest(Path pluginDir, List<Map<String, String>> commands) {
        Path manifestFile = CLAUDE_PLUGINS_DIR.resolve(INSTALLED_PLUGINS_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return;
        }
        try {
            String json = Files.readString(manifestFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject plugins = root.getAsJsonObject("plugins");
            if (plugins == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : plugins.entrySet()) {
                JsonArray arr = entry.getValue().getAsJsonArray();
                if (arr == null || arr.isEmpty()) {
                    continue;
                }
                for (JsonElement elem : arr) {
                    JsonObject item = elem.getAsJsonObject();
                    String ip = GsonUtils.getString(item, "installPath");
                    if (ip != null && ip.equals(pluginDir.toString())) {
                        JsonArray cmds = item.getAsJsonArray("commands");
                        if (cmds != null) {
                            this.extractCommandsFromArray(cmds, commands);
                        }
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 Claude manifest 读取命令失败", e);
        }
    }

    private void readCommandsFromOpenCodePlugin(Path pluginDir, List<Map<String, String>> commands) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(".js") && !fileName.endsWith(".ts")) {
                    continue;
                }
                String name = fileName.replace(".js", "").replace(".ts", "");
                Map<String, String> cmd = new LinkedHashMap<>();
                cmd.put("name", "/" + name);
                cmd.put("description", fileName);
                cmd.put("file", fileName);
                commands.add(cmd);
            }
        } catch (IOException e) {
            log.debug("扫描 OpenCode 插件命令失败: {}", pluginDir, e);
        }
    }

    private void extractCommands(JsonObject obj, List<Map<String, String>> commands) {
        JsonArray cmds = obj.getAsJsonArray("commands");
        if (cmds == null) {
            return;
        }
        this.extractCommandsFromArray(cmds, commands);
    }

    private void extractCommandsFromArray(JsonArray cmds, List<Map<String, String>> commands) {
        for (JsonElement elem : cmds) {
            if (elem.isJsonObject()) {
                JsonObject cmdObj = elem.getAsJsonObject();
                Map<String, String> cmd = new LinkedHashMap<>();
                cmd.put("name", GsonUtils.getString(cmdObj, "name"));
                cmd.put("description", GsonUtils.getString(cmdObj, "description"));
                if (cmd.get("name") != null && !cmd.get("name").isBlank()) {
                    commands.add(cmd);
                }
            } else if (elem.isJsonPrimitive()) {
                Map<String, String> cmd = new LinkedHashMap<>();
                cmd.put("name", elem.getAsString());
                cmd.put("description", "");
                commands.add(cmd);
            }
        }
    }

    /**
     * 获取指定 CLI 已知的 GitHub 仓库列表（供下拉选择）。
     *
     * @param cliType CLI 类型
     * @return 仓库信息列表
     */
    public List<RepoInfo> listKnownRepos(String cliType) {
        Set<RepoInfo> repos = new LinkedHashSet<>();
        switch (cliType.toUpperCase()) {
            case "CLAUDE" -> this.addClaudeKnownRepos(repos);
            case "OPENCODE" -> this.addOpenCodeKnownRepos(repos);
            case "CODEX" -> this.addCodexKnownRepos(repos);
        }
        return new ArrayList<>(repos);
    }

    /**
     * 浏览指定 GitHub 仓库中的可用插件目录。
     *
     * @param ownerRepo owner/repo 格式的仓库标识
     * @return 远程插件信息列表
     */
    public List<RemotePluginInfo> listRemotePlugins(String ownerRepo) {
        if (ownerRepo == null || ownerRepo.isBlank() || !ownerRepo.contains("/")) {
            return List.of();
        }
        List<RemotePluginInfo> result = new ArrayList<>();
        try {
            String[] parts = ownerRepo.split("/");
            String owner = parts[0];
            String repo = parts[1];
            this.browseRepoForPlugins(owner, repo, result);
        } catch (Exception e) {
            log.warn("浏览远程仓库插件失败: {}", ownerRepo, e);
        }
        return result;
    }

    // ==================== Claude Plugin Loading ====================

    private List<PluginEntry> loadClaudePlugins() {
        List<PluginEntry> entries = new ArrayList<>();
        Path manifestFile = CLAUDE_PLUGINS_DIR.resolve(INSTALLED_PLUGINS_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return entries;
        }
        try {
            String json = Files.readString(manifestFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject plugins = root.getAsJsonObject("plugins");
            if (plugins == null) {
                return entries;
            }
            for (Map.Entry<String, JsonElement> entry : plugins.entrySet()) {
                String key = entry.getKey();
                JsonArray arr = entry.getValue().getAsJsonArray();
                if (arr == null || arr.isEmpty()) {
                    continue;
                }
                JsonObject latest = arr.get(arr.size() - 1).getAsJsonObject();
                String installPath = GsonUtils.getString(latest, "installPath");
                String version = GsonUtils.getString(latest, "version");
                String scope = GsonUtils.getString(latest, "scope");
                if (installPath == null || installPath.isBlank()) {
                    continue;
                }
                Path pluginDir = Path.of(installPath);
                PluginMeta meta = this.readPluginJson(pluginDir.resolve(CLAUDE_PLUGIN_MANIFEST).resolve(PLUGIN_JSON));
                String name = this.extractPluginNameFromKey(key);
                String marketplace = this.extractMarketplaceFromKey(key);

                entries.add(PluginEntry.builder()
                        .name(meta != null ? meta.name : name)
                        .description(meta != null ? meta.description : "")
                        .version(version != null ? version : (meta != null ? meta.version : ""))
                        .author(meta != null ? meta.author : "")
                        .homepage(meta != null ? meta.homepage : "")
                        .license(meta != null ? meta.license : "")
                        .cliType("CLAUDE").scope(scope != null ? scope : "user")
                        .installPath(installPath)
                        .source("marketplace").sourceUrl(marketplace)
                        .lastModified(this.getLastModified(pluginDir))
                        .build());
            }
        } catch (Exception e) {
            log.warn("加载 Claude 插件列表失败", e);
        }
        return entries;
    }

    // ==================== OpenCode Plugin Loading ====================

    private List<PluginEntry> loadOpenCodePlugins() {
        List<PluginEntry> entries = new ArrayList<>();
        this.addOpenCodeNpmPlugins(entries);
        this.addOpenCodeLocalPlugins(entries, OPENCODE_PLUGINS_DIR, "user");
        if (this.projectPath != null && !this.projectPath.isBlank()) {
            Path projectPlugins = Path.of(this.projectPath).resolve(".opencode").resolve("plugins");
            this.addOpenCodeLocalPlugins(entries, projectPlugins, "project");
        }
        return entries;
    }

    private void addOpenCodeNpmPlugins(List<PluginEntry> entries) {
        if (!Files.isRegularFile(OPENCODE_CONFIG)) {
            return;
        }
        try {
            String json = Files.readString(OPENCODE_CONFIG);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray pluginArr = root.getAsJsonArray("plugin");
            if (pluginArr == null) {
                return;
            }
            for (JsonElement elem : pluginArr) {
                String pkg = elem.getAsString();
                entries.add(PluginEntry.builder()
                        .name(pkg).description("npm package")
                        .version("").author("").homepage("").license("")
                        .cliType("OPENCODE").scope("user")
                        .installPath("").source("npm").sourceUrl(pkg)
                        .lastModified(0).build());
            }
        } catch (Exception e) {
            log.debug("读取 OpenCode npm 插件列表失败", e);
        }
    }

    private void addOpenCodeLocalPlugins(List<PluginEntry> entries, Path pluginsDir, String scope) {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(".js") && !fileName.endsWith(".ts")) {
                    continue;
                }
                String name = fileName.replace(".js", "").replace(".ts", "");
                entries.add(PluginEntry.builder()
                        .name(name)
                        .description("Local plugin: " + fileName)
                        .version("").author("").homepage("").license("")
                        .cliType("OPENCODE").scope(scope)
                        .installPath(entry.toString())
                        .source("local").sourceUrl("")
                        .lastModified(this.getLastModified(entry)).build());
            }
        } catch (IOException e) {
            log.debug("扫描 OpenCode 本地插件目录失败: {}", pluginsDir, e);
        }
    }

    // ==================== Codex Plugin Loading ====================

    private List<PluginEntry> loadCodexPlugins() {
        List<PluginEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(CODEX_PLUGINS_DIR)) {
            return entries;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CODEX_PLUGINS_DIR)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || entry.getFileName().toString().startsWith(".")) {
                    continue;
                }
                Path manifestDir = entry.resolve(CODEX_PLUGIN_MANIFEST);
                PluginMeta meta = this.readPluginJson(manifestDir.resolve(PLUGIN_JSON));
                if (meta == null) {
                    continue;
                }
                entries.add(PluginEntry.builder()
                        .name(meta.name).description(meta.description)
                        .version(meta.version).author(meta.author)
                        .homepage(meta.homepage).license(meta.license)
                        .cliType("CODEX").scope("user")
                        .installPath(entry.toString())
                        .source("local").sourceUrl("")
                        .lastModified(this.getLastModified(manifestDir.resolve(PLUGIN_JSON))).build());
            }
        } catch (IOException e) {
            log.warn("扫描 Codex 插件目录失败", e);
        }
        return entries;
    }

    // ==================== Plugin Installation ====================

    private InstallResult installClaudePlugin(String githubUrl, String pluginName) {
        ParsedGitUrl parsed = this.parseGitUrl(githubUrl);
        if (parsed == null) {
            return new InstallResult(false, "Invalid GitHub URL: " + githubUrl, null);
        }
        try {
            Path cacheDir = CLAUDE_PLUGINS_DIR.resolve("cache").resolve(pluginName);
            Path destDir = cacheDir.resolve("unknown");
            if (Files.exists(destDir)) {
                return new InstallResult(false, "Plugin already exists: " + pluginName, null);
            }
            Path tmpDir = Files.createTempDirectory("plugin-install-");
            try {
                Path repoDir = tmpDir.resolve("repo");
                List<String> cloneCmd = List.of("git", "clone", "--depth", "1", parsed.cloneUrl, repoDir.toString());
                this.runProcess(new ProcessBuilder(cloneCmd), 60);

                Path srcPlugin = parsed.subPath != null ? repoDir.resolve(parsed.subPath) : repoDir;
                if (!Files.isDirectory(srcPlugin)) {
                    return new InstallResult(false, "Plugin directory not found in repo", null);
                }

                Files.createDirectories(destDir);
                this.copyDirectory(srcPlugin, destDir);

                this.updateClaudeInstalledManifest(pluginName, destDir.toString());

                return new InstallResult(true, "Installed to " + destDir, pluginName);
            } finally {
                this.deleteDirectory(tmpDir);
            }
        } catch (Exception e) {
            log.warn("安装 Claude 插件失败: {}", githubUrl, e);
            return new InstallResult(false, "Install failed: " + e.getMessage(), null);
        }
    }

    private InstallResult installOpenCodePlugin(String githubUrl, String pluginName, String scope) {
        ParsedGitUrl parsed = this.parseGitUrl(githubUrl);
        if (parsed == null) {
            return new InstallResult(false, "Invalid GitHub URL: " + githubUrl, null);
        }
        try {
            Path pluginsDir = "project".equals(scope) && this.projectPath != null
                    ? Path.of(this.projectPath).resolve(".opencode").resolve("plugins")
                    : OPENCODE_PLUGINS_DIR;
            Files.createDirectories(pluginsDir);

            Path tmpDir = Files.createTempDirectory("plugin-install-");
            try {
                Path repoDir = tmpDir.resolve("repo");
                List<String> cloneCmd = List.of("git", "clone", "--depth", "1", parsed.cloneUrl, repoDir.toString());
                this.runProcess(new ProcessBuilder(cloneCmd), 60);

                Path srcPlugin = parsed.subPath != null ? repoDir.resolve(parsed.subPath) : repoDir;
                if (!Files.isDirectory(srcPlugin)) {
                    return new InstallResult(false, "Plugin directory not found in repo", null);
                }

                try (var fileStream = Files.walk(srcPlugin)) {
                    fileStream.filter(Files::isRegularFile)
                            .filter(f -> f.toString().endsWith(".js") || f.toString().endsWith(".ts"))
                            .forEach(f -> {
                                try {
                                    Files.copy(f, pluginsDir.resolve(f.getFileName()));
                                } catch (IOException e) {
                                    log.warn("复制插件文件失败: {}", f, e);
                                }
                            });
                }

                return new InstallResult(true, "Installed to " + pluginsDir, pluginName);
            } finally {
                this.deleteDirectory(tmpDir);
            }
        } catch (Exception e) {
            log.warn("安装 OpenCode 插件失败: {}", githubUrl, e);
            return new InstallResult(false, "Install failed: " + e.getMessage(), null);
        }
    }

    private InstallResult installCodexPlugin(String githubUrl, String pluginName) {
        ParsedGitUrl parsed = this.parseGitUrl(githubUrl);
        if (parsed == null) {
            return new InstallResult(false, "Invalid GitHub URL: " + githubUrl, null);
        }
        try {
            Path destDir = CODEX_PLUGINS_DIR.resolve(pluginName);
            if (Files.exists(destDir)) {
                return new InstallResult(false, "Plugin already exists: " + pluginName, null);
            }

            Path tmpDir = Files.createTempDirectory("plugin-install-");
            try {
                Path repoDir = tmpDir.resolve("repo");
                List<String> cloneCmd = List.of("git", "clone", "--depth", "1", parsed.cloneUrl, repoDir.toString());
                this.runProcess(new ProcessBuilder(cloneCmd), 60);

                Path srcPlugin = parsed.subPath != null ? repoDir.resolve(parsed.subPath) : repoDir;
                if (!Files.isDirectory(srcPlugin)) {
                    return new InstallResult(false, "Plugin directory not found in repo", null);
                }

                Files.createDirectories(destDir);
                this.copyDirectory(srcPlugin, destDir);

                return new InstallResult(true, "Installed to " + destDir, pluginName);
            } finally {
                this.deleteDirectory(tmpDir);
            }
        } catch (Exception e) {
            log.warn("安装 Codex 插件失败: {}", githubUrl, e);
            return new InstallResult(false, "Install failed: " + e.getMessage(), null);
        }
    }

    // ==================== Known Repos ====================

    private void addClaudeKnownRepos(Set<RepoInfo> repos) {
        Path marketplacesFile = CLAUDE_PLUGINS_DIR.resolve(KNOWN_MARKETPLACES_FILE);
        if (!Files.isRegularFile(marketplacesFile)) {
            return;
        }
        try {
            String json = Files.readString(marketplacesFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject mp = entry.getValue().getAsJsonObject();
                JsonObject source = mp.getAsJsonObject("source");
                if (source == null) {
                    continue;
                }
                String ownerRepo = null;
                String gitUrl = GsonUtils.getString(source, "url");
                String ghRepo = GsonUtils.getString(source, "repo");
                if (ghRepo != null && !ghRepo.isBlank()) {
                    ownerRepo = ghRepo;
                } else if (gitUrl != null && gitUrl.contains("github.com")) {
                    ownerRepo = this.extractOwnerRepoFromGitUrl(gitUrl);
                }
                if (ownerRepo != null && !ownerRepo.isBlank()) {
                    repos.add(new RepoInfo(ownerRepo, entry.getKey(), ownerRepo));
                }
            }
        } catch (Exception e) {
            log.debug("读取 Claude known_marketplaces 失败", e);
        }
    }

    private void addOpenCodeKnownRepos(Set<RepoInfo> repos) {
        if (!Files.isRegularFile(OPENCODE_CONFIG)) {
            return;
        }
        try {
            String json = Files.readString(OPENCODE_CONFIG);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray pluginArr = root.getAsJsonArray("plugin");
            if (pluginArr == null) {
                return;
            }
            for (JsonElement elem : pluginArr) {
                String pkg = elem.getAsString();
                repos.add(new RepoInfo(pkg, pkg, "npm:" + pkg));
            }
        } catch (Exception e) {
            log.debug("读取 OpenCode 插件配置失败", e);
        }
    }

    private void addCodexKnownRepos(Set<RepoInfo> repos) {
        if (!Files.isDirectory(CODEX_PLUGINS_DIR)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CODEX_PLUGINS_DIR)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || entry.getFileName().toString().startsWith(".")) {
                    continue;
                }
                PluginMeta meta = this.readPluginJson(entry.resolve(CODEX_PLUGIN_MANIFEST).resolve(PLUGIN_JSON));
                if (meta != null && meta.repository != null && !meta.repository.isBlank()) {
                    String ownerRepo = this.extractOwnerRepoFromGitUrl(meta.repository);
                    if (ownerRepo != null) {
                        repos.add(new RepoInfo(ownerRepo, meta.name, ownerRepo));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("扫描 Codex 已知仓库失败", e);
        }
    }

    // ==================== Remote Plugin Browsing ====================

    private void browseRepoForPlugins(String owner, String repo, List<RemotePluginInfo> result) {
        try {
            JsonArray rootContents = this.githubApiGet(owner, repo, "");
            if (rootContents == null) {
                return;
            }

            Set<String> dirNames = new LinkedHashSet<>();
            for (JsonElement elem : rootContents) {
                JsonObject item = elem.getAsJsonObject();
                if ("dir".equals(GsonUtils.getString(item, "type"))) {
                    dirNames.add(GsonUtils.getString(item, "name"));
                }
            }

            if (dirNames.contains("plugins")) {
                this.browsePluginsDirectory(owner, repo, "plugins", result);
            }
            if (dirNames.contains("external_plugins")) {
                this.browsePluginsDirectory(owner, repo, "external_plugins", result);
            }
            if (dirNames.contains("skills")) {
                this.browseSkillsDirectory(owner, repo, "skills", result);
            }
            for (String dirName : dirNames) {
                if ("plugins".equals(dirName) || "external_plugins".equals(dirName) || "skills".equals(dirName)) {
                    continue;
                }
                Path manifestPath = Path.of(dirName, CLAUDE_PLUGIN_MANIFEST, PLUGIN_JSON);
                this.tryAddPluginFromPath(owner, repo, manifestPath.toString(), dirName, result);
                Path codexManifest = Path.of(dirName, CODEX_PLUGIN_MANIFEST, PLUGIN_JSON);
                this.tryAddPluginFromPath(owner, repo, codexManifest.toString(), dirName, result);
            }
        } catch (Exception e) {
            log.warn("浏览仓库 {} / {} 失败", owner, repo, e);
        }
    }

    private void browsePluginsDirectory(String owner, String repo, String pluginsDir, List<RemotePluginInfo> result) {
        try {
            JsonArray contents = this.githubApiGet(owner, repo, pluginsDir);
            if (contents == null) {
                return;
            }
            for (JsonElement elem : contents) {
                JsonObject item = elem.getAsJsonObject();
                if (!"dir".equals(GsonUtils.getString(item, "type"))) {
                    continue;
                }
                String name = GsonUtils.getString(item, "name");
                this.tryAddPluginFromPath(owner, repo, pluginsDir + "/" + name, name, result);
            }
        } catch (Exception e) {
            log.debug("浏览插件目录 {} 失败: {}/{}", pluginsDir, owner, repo, e);
        }
    }

    private void browseSkillsDirectory(String owner, String repo, String skillsDir, List<RemotePluginInfo> result) {
        try {
            JsonArray contents = this.githubApiGet(owner, repo, skillsDir);
            if (contents == null) {
                return;
            }
            for (JsonElement elem : contents) {
                JsonObject item = elem.getAsJsonObject();
                if (!"dir".equals(GsonUtils.getString(item, "type"))) {
                    continue;
                }
                String name = GsonUtils.getString(item, "name");
                result.add(new RemotePluginInfo(name, skillsDir + "/" + name, "skill"));
            }
        } catch (Exception e) {
            log.debug("浏览技能目录 {} 失败: {}/{}", skillsDir, owner, repo, e);
        }
    }

    private void tryAddPluginFromPath(String owner, String repo, String manifestPath, String pluginName, List<RemotePluginInfo> result) {
        try {
            JsonElement content = this.githubApiGetContent(owner, repo, manifestPath);
            if (content != null && content.isJsonObject()) {
                JsonObject obj = content.getAsJsonObject();
                String desc = GsonUtils.getString(obj, "description");
                result.add(new RemotePluginInfo(pluginName,
                        manifestPath.contains("/") ? manifestPath.substring(0, manifestPath.lastIndexOf('/' + CLAUDE_PLUGIN_MANIFEST)) : pluginName,
                        desc != null ? desc : ""));
            }
        } catch (Exception e) {
            // not a plugin directory, skip
        }
    }

    // ==================== GitHub API ====================

    private JsonArray githubApiGet(String owner, String repo, String path) {
        try {
            String url = GITHUB_API_BASE + owner + "/" + repo + "/contents/" + path;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body()).getAsJsonArray();
            }
        } catch (Exception e) {
            log.debug("GitHub API 请求失败: {}/{} / {}", owner, repo, path, e);
        }
        return null;
    }

    private JsonElement githubApiGetContent(String owner, String repo, String path) {
        try {
            String url = GITHUB_API_BASE + owner + "/" + repo + "/contents/" + path;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Accept", "application/vnd.github.v3+json")
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return JsonParser.parseString(response.body());
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ==================== Claude Manifest Update ====================

    private void updateClaudeInstalledManifest(String pluginName, String installPath) {
        Path manifestFile = CLAUDE_PLUGINS_DIR.resolve(INSTALLED_PLUGINS_FILE);
        try {
            JsonObject root;
            if (Files.isRegularFile(manifestFile)) {
                root = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
            } else {
                root = new JsonObject();
                root.addProperty("version", 2);
                root.add("plugins", new JsonObject());
            }
            JsonObject plugins = root.getAsJsonObject("plugins");
            if (plugins == null) {
                plugins = new JsonObject();
                root.add("plugins", plugins);
            }
            String key = pluginName + "@easyagent";
            JsonObject entry = new JsonObject();
            entry.addProperty("scope", "user");
            entry.addProperty("installPath", installPath);
            entry.addProperty("version", "unknown");
            entry.addProperty("installedAt", java.time.Instant.now().toString());
            entry.addProperty("lastUpdated", java.time.Instant.now().toString());
            JsonArray arr = new JsonArray();
            arr.add(entry);
            plugins.add(key, arr);
            Files.createDirectories(manifestFile.getParent());
            Files.writeString(manifestFile, GsonUtils.toJson(root));
        } catch (Exception e) {
            log.warn("更新 Claude installed_plugins.json 失败", e);
        }
    }

    private void removeFromClaudeManifest(String pluginName, String installPath) {
        Path manifestFile = CLAUDE_PLUGINS_DIR.resolve(INSTALLED_PLUGINS_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(manifestFile)).getAsJsonObject();
            JsonObject plugins = root.getAsJsonObject("plugins");
            if (plugins == null) {
                return;
            }
            List<String> keysToRemove = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : plugins.entrySet()) {
                JsonArray arr = entry.getValue().getAsJsonArray();
                if (arr != null) {
                    for (JsonElement elem : arr) {
                        JsonObject obj = elem.getAsJsonObject();
                        String ip = GsonUtils.getString(obj, "installPath");
                        if (installPath.equals(ip)) {
                            keysToRemove.add(entry.getKey());
                            break;
                        }
                    }
                }
            }
            for (String key : keysToRemove) {
                plugins.remove(key);
            }
            if (!keysToRemove.isEmpty()) {
                Files.writeString(manifestFile, GsonUtils.toJson(root));
            }
        } catch (Exception e) {
            log.warn("从 Claude manifest 移除插件失败", e);
        }
    }

    // ==================== Utility Methods ====================

    private PluginMeta readPluginJson(Path pluginJsonPath) {
        if (!Files.isRegularFile(pluginJsonPath)) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(pluginJsonPath)).getAsJsonObject();
            PluginMeta meta = new PluginMeta();
            meta.name = GsonUtils.getString(obj, "name");
            meta.description = GsonUtils.getString(obj, "description");
            meta.version = GsonUtils.getString(obj, "version");
            meta.homepage = GsonUtils.getString(obj, "homepage");
            meta.repository = GsonUtils.getString(obj, "repository");
            meta.license = GsonUtils.getString(obj, "license");
            JsonObject author = obj.getAsJsonObject("author");
            if (author != null) {
                meta.author = GsonUtils.getString(author, "name");
            }
            return meta;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPluginNameFromKey(String key) {
        if (key == null) {
            return "";
        }
        int at = key.indexOf('@');
        return at >= 0 ? key.substring(0, at) : key;
    }

    private String extractMarketplaceFromKey(String key) {
        if (key == null) {
            return "";
        }
        int at = key.indexOf('@');
        return at >= 0 ? key.substring(at + 1) : "";
    }

    private String extractOwnerRepoFromGitUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String cleaned = url.replace(".git", "");
        int idx = cleaned.indexOf("github.com/");
        if (idx >= 0) {
            String sub = cleaned.substring(idx + "github.com/".length());
            String[] parts = sub.split("/");
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        if (cleaned.contains("/")) {
            String[] parts = cleaned.split("/");
            int start = 0;
            for (int i = 0; i < parts.length; i++) {
                if ("https:".equals(parts[i]) || "http:".equals(parts[i]) || parts[i].isBlank()) {
                    start = i + 1;
                }
            }
            if (parts.length - start >= 2) {
                return parts[start] + "/" + parts[start + 1];
            }
        }
        return null;
    }

    private ParsedGitUrl parseGitUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String cleaned = url.trim();
        if (!cleaned.startsWith("http")) {
            String[] parts = cleaned.split("/");
            if (parts.length < 2) {
                return null;
            }
            return new ParsedGitUrl(
                    "https://github.com/" + parts[0] + "/" + parts[1] + ".git",
                    parts.length > 2 ? this.joinParts(parts, 2) : null
            );
        }
        cleaned = cleaned.replace("https://github.com/", "").replace("http://github.com/", "");
        String[] segments = cleaned.split("/");
        if (segments.length < 2) {
            return null;
        }
        String repo = segments[1].replace(".git", "");
        String subPath = null;
        if (segments.length > 3 && ("tree".equals(segments[2]) || "blob".equals(segments[2]))) {
            if (segments.length > 4) {
                subPath = this.joinParts(segments, 4);
            }
        } else if (segments.length > 2) {
            subPath = this.joinParts(segments, 2);
        }
        return new ParsedGitUrl(
                "https://github.com/" + segments[0] + "/" + repo + ".git",
                subPath
        );
    }

    private String joinParts(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) {
                sb.append("/");
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private long getLastModified(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readAttributes(path, BasicFileAttributes.class).lastModifiedTime().toMillis();
            }
        } catch (IOException e) {
            // ignore
        }
        return 0;
    }

    private String runProcess(ProcessBuilder pb, int timeout) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean exited = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("Process exited with code " + exitCode + ": " + output.trim());
        }
        return output.trim();
    }

    private void copyDirectory(Path src, Path dest) throws IOException {
        try (var stream = Files.walk(src)) {
            stream.forEach(source -> {
                Path target = dest.resolve(src.relativize(source));
                try {
                    Files.copy(source, target);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                // ignore
                            }
                        });
            }
        }
    }

    // ==================== Inner Records ====================

    public record InstallResult(boolean success, String message, String pluginName) {
    }

    public record DeleteResult(boolean success, String message) {
    }

    public record RepoInfo(String ownerRepo, String displayName, String url) {
    }

    public record RemotePluginInfo(String name, String path, String description) {
    }

    private record ParsedGitUrl(String cloneUrl, String subPath) {
    }

    private static class PluginMeta {
        String name;
        String description;
        String version;
        String author;
        String homepage;
        String repository;
        String license;
    }
}
