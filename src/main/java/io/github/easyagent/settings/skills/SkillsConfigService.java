package io.github.easyagent.settings.skills;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.easyagent.util.GsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Skills 技能配置管理服务。
 * <p>
 * 负责 Claude / OpenCode / Codex 三个 CLI 的 Skills 读取和安装：
 * </p>
 * <ul>
 *   <li><b>读取</b>：扫描各 CLI 的 skills 目录，解析 SKILL.md frontmatter</li>
 *   <li><b>安装</b>：通过各 CLI 命令从 GitHub 仓库安装 skills</li>
 * </ul>
 *
 * <h3>Skills 目录路径</h3>
 * <table>
 *   <tr><th>CLI</th><th>用户级路径</th><th>项目级路径</th></tr>
 *   <tr><td>Claude</td><td>{@code ~/.claude/skills/}</td><td>{@code <project>/.claude/skills/}</td></tr>
 *   <tr><td>OpenCode</td><td>{@code ~/.config/opencode/skill/}</td><td>{@code <project>/.opencode/skill/}</td></tr>
 *   <tr><td>Codex</td><td>{@code ~/.codex/skills/}</td><td>{@code <project>/.codex/skills/}</td></tr>
 * </table>
 *
 * <h3>安装方式</h3>
 * <ul>
 *   <li><b>Claude</b>：{@code claude plugin install <name>} 或从 marketplace 安装</li>
 *   <li><b>Codex</b>：通过内置 Python 脚本 {@code install-skill-from-github.py} 从 GitHub 安装</li>
 *   <li><b>OpenCode</b>：通过 {@code git clone --sparse} 将 GitHub 仓库中的 skill 目录复制到本地</li>
 * </ul>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
public class SkillsConfigService {

    private static final Logger log = LoggerFactory.getLogger(SkillsConfigService.class);

    private static final String USER_HOME = System.getProperty("user.home");
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Gson GSON = GsonUtils.getGson();

    /**
     * 加载指定 CLI 类型在指定项目路径下的所有 Skills 配置。
     *
     * @param cliType     CLI 类型：CLAUDE / OPENCODE / CODEX
     * @param projectPath 当前项目根路径，可为 null（仅加载用户级）
     * @return Skill 条目列表，合并用户级和项目级
     */
    public List<SkillEntry> loadSkills(String cliType, String projectPath) {
        List<SkillEntry> result = new ArrayList<>();
        if ("CLAUDE".equals(cliType.toUpperCase())) {
            result.addAll(this.loadClaudePlugins("user"));
            if (projectPath != null && !projectPath.isBlank()) {
                result.addAll(this.loadClaudePlugins("project"));
            }
        }
        result.addAll(this.loadSkillsFromDirectory(cliType, "user", null));
        if (projectPath != null && !projectPath.isBlank()) {
            result.addAll(this.loadSkillsFromDirectory(cliType, "project", projectPath));
        }
        return result;
    }

    /**
     * 从 GitHub 仓库安装 Skill 到指定 CLI。
     *
     * @param cliType   CLI 类型：CLAUDE / OPENCODE / CODEX
     * @param githubUrl GitHub 仓库地址，支持格式：
     *                  <ul>
     *                    <li>{@code https://github.com/owner/repo}</li>
     *                    <li>{@code https://github.com/owner/repo/tree/branch/path/to/skill}</li>
     *                    <li>{@code owner/repo}</li>
     *                  </ul>
     * @param skillPath skill 在仓库中的路径（可选，为空时取仓库根目录）
     * @param scope     安装作用域：user 或 project
     * @return 安装结果
     */
    public InstallResult installSkill(String cliType, String githubUrl, String skillPath, String scope) {
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> this.installClaudeSkill(githubUrl, skillPath, scope);
            case "CODEX" -> this.installCodexSkill(githubUrl, skillPath, scope);
            case "OPENCODE" -> this.installOpenCodeSkill(githubUrl, skillPath, scope);
            default -> new InstallResult(false, "Unsupported CLI type: " + cliType, null);
        };
    }

    /**
     * 删除指定 Skill。
     *
     * @param cliType   CLI 类型
     * @param skillName skill 名称
     * @param skillPath skill 目录路径
     * @return 删除结果
     */
    public DeleteResult deleteSkill(String cliType, String skillName, String skillPath) {
        if ("CLAUDE".equals(cliType.toUpperCase())) {
            return this.deleteClaudePlugin(skillName);
        }
        return this.deleteLocalSkill(skillPath);
    }

    /**
     * 读取指定 Skill 的完整 SKILL.md 内容。
     *
     * @param skillPath 技能目录路径
     * @return SKILL.md 完整文本内容，读取失败返回 null
     */
    public String readSkillContent(String skillPath) {
        Path mdFile = Path.of(skillPath, SKILL_FILE_NAME);
        if (!Files.exists(mdFile)) {
            return null;
        }
        try {
            return Files.readString(mdFile);
        } catch (IOException e) {
            log.warn("读取 Skill 内容失败: {}", mdFile, e);
            return null;
        }
    }

    // ==================== Claude Plugin System ====================

    /**
     * 从 Claude 插件系统加载已安装的插件列表。
     */
    private List<SkillEntry> loadClaudePlugins(String scope) {
        List<SkillEntry> entries = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "plugin", "list", "--json");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean exited = process.waitFor(15, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return entries;
            }
            JsonArray plugins = JsonParser.parseString(output).getAsJsonArray();
            for (JsonElement elem : plugins) {
                JsonObject plugin = elem.getAsJsonObject();
                String id = plugin.has("id") ? plugin.get("id").getAsString() : "";
                String pluginScope = plugin.has("scope") ? plugin.get("scope").getAsString() : "user";
                if (!scope.equals(pluginScope)) {
                    continue;
                }
                String pluginName = id.contains("@") ? id.substring(0, id.indexOf("@")) : id;
                String installPath = plugin.has("installPath") ? plugin.get("installPath").getAsString() : "";
                boolean enabled = !plugin.has("enabled") || plugin.get("enabled").getAsBoolean();
                String version = plugin.has("version") ? plugin.get("version").getAsString() : "";

                String name = pluginName;
                String description = "";
                String content = null;
                long lastModified = 0;
                String source = "marketplace";
                String sourceUrl = id.contains("@") ? id.substring(id.indexOf("@") + 1) : "";

                if (!installPath.isBlank()) {
                    Path mdFile = Path.of(installPath, SKILL_FILE_NAME);
                    if (Files.exists(mdFile)) {
                        String fileContent = Files.readString(mdFile);
                        content = fileContent;
                        String frontmatter = this.extractFrontmatter(fileContent);
                        if (frontmatter != null) {
                            String frontName = this.extractYamlValue(frontmatter, "name");
                            if (frontName != null && !frontName.isBlank()) {
                                name = frontName.trim();
                            }
                            String frontDesc = this.extractYamlValue(frontmatter, "description");
                            if (frontDesc != null) {
                                description = frontDesc.trim();
                            }
                        }
                        BasicFileAttributes attrs = Files.readAttributes(mdFile, BasicFileAttributes.class);
                        lastModified = attrs.lastModifiedTime().toMillis();
                    }
                }

                entries.add(SkillEntry.builder()
                        .name(name).description(description).content(content)
                        .scope(pluginScope).cliType("CLAUDE").skillPath(installPath)
                        .enabled(enabled).source(source).sourceUrl(sourceUrl)
                        .version(version).lastModified(lastModified)
                        .build());
            }
        } catch (Exception e) {
            log.debug("加载 Claude 插件列表失败", e);
        }
        return entries;
    }

    /**
     * 通过 Claude marketplace 安装插件。
     * <p>
     * 如果 githubUrl 是 marketplace 中的插件名，直接使用 {@code claude plugin install}。
     * 否则先添加 marketplace，再安装。
     * </p>
     */
    private InstallResult installClaudeSkill(String githubUrl, String skillPath, String scope) {
        try {
            String scopeArg = "--scope";
            String scopeValue = "user".equals(scope) ? "user" : "project";

            if (githubUrl.contains("/") && !githubUrl.startsWith("http")) {
                String[] parts = githubUrl.split("/");
                if (parts.length >= 2) {
                    String owner = parts[0];
                    String repo = parts[1];
                    String marketName = owner + "-" + repo;

                    ProcessBuilder addPb = new ProcessBuilder(
                            "claude", "plugin", "marketplace", "add",
                            "https://github.com/" + owner + "/" + repo,
                            "--scope", scopeValue
                    );
                    this.runProcess(addPb, 30);

                    String pluginName = skillPath != null && !skillPath.isBlank()
                            ? Path.of(skillPath).getFileName().toString()
                            : repo;
                    ProcessBuilder installPb = new ProcessBuilder(
                            "claude", "plugin", "install",
                            pluginName + "@" + marketName,
                            scopeArg, scopeValue
                    );
                    String output = this.runProcess(installPb, 60);
                    return new InstallResult(true, "Installed via Claude plugin system", output);
                }
            }

            ProcessBuilder installPb = new ProcessBuilder(
                    "claude", "plugin", "install", githubUrl,
                    scopeArg, scopeValue
            );
            String output = this.runProcess(installPb, 60);
            return new InstallResult(true, "Installed via Claude plugin system", output);
        } catch (Exception e) {
            log.warn("Claude 插件安装失败: {}", githubUrl, e);
            return new InstallResult(false, "Claude install failed: " + e.getMessage(), null);
        }
    }

    /**
     * 通过 Claude CLI 删除插件。
     */
    private DeleteResult deleteClaudePlugin(String pluginName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "plugin", "uninstall", pluginName);
            String output = this.runProcess(pb, 30);
            return new DeleteResult(true, output);
        } catch (Exception e) {
            log.warn("Claude 插件卸载失败: {}", pluginName, e);
            return new DeleteResult(false, e.getMessage());
        }
    }

    // ==================== Codex Skill System ====================

    /**
     * 通过 Codex 内置的 Python 脚本从 GitHub 安装 skill。
     */
    private InstallResult installCodexSkill(String githubUrl, String skillPath, String scope) {
        try {
            Path scriptPath = Path.of(USER_HOME, ".codex", "skills", ".system",
                    "skill-installer", "scripts", "install-skill-from-github.py");
            if (!Files.exists(scriptPath)) {
                return this.installViaGitClone("CODEX", githubUrl, skillPath, scope);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("python3");
            cmd.add(scriptPath.toString());
            cmd.add("--url");
            cmd.add(githubUrl);
            if (skillPath != null && !skillPath.isBlank()) {
                cmd.add("--path");
                cmd.add(skillPath);
            }
            cmd.add("--dest");
            cmd.add(this.resolveSkillsPath("CODEX", scope, null).toString());

            String output = this.runProcess(new ProcessBuilder(cmd), 60);
            return new InstallResult(true, "Installed via Codex skill installer", output);
        } catch (Exception e) {
            log.warn("Codex skill 安装失败: {}", githubUrl, e);
            return this.installViaGitClone("CODEX", githubUrl, skillPath, scope);
        }
    }

    // ==================== OpenCode Skill System ====================

    /**
     * OpenCode 无专用安装命令，直接通过 git clone sparse 安装。
     */
    private InstallResult installOpenCodeSkill(String githubUrl, String skillPath, String scope) {
        return this.installViaGitClone("OPENCODE", githubUrl, skillPath, scope);
    }

    // ==================== Generic Git Clone Install ====================

    /**
     * 通用的 git clone sparse 方式安装 skill。
     * <p>
     * 适用于 Codex（脚本不可用时）和 OpenCode 的 skill 安装。
     * 使用 git clone --depth 1 --sparse，然后复制 SKILL.md 及其关联文件到目标目录。
     * </p>
     */
    private InstallResult installViaGitClone(String cliType, String githubUrl, String skillPath, String scope) {
        try {
            ParsedGitHubUrl parsed = this.parseGitHubUrl(githubUrl);
            if (parsed == null) {
                return new InstallResult(false, "Invalid GitHub URL: " + githubUrl, null);
            }

            String effectiveSkillPath = (skillPath != null && !skillPath.isBlank())
                    ? skillPath : parsed.subPath;
            if (effectiveSkillPath == null || effectiveSkillPath.isBlank()) {
                return new InstallResult(false, "Skill path is required", null);
            }

            String skillName = Path.of(effectiveSkillPath).getFileName().toString();
            Path destDir = this.resolveSkillsPath(cliType, scope, null).resolve(skillName);
            if (Files.exists(destDir)) {
                return new InstallResult(false, "Skill already exists: " + skillName, null);
            }

            Path tmpDir = Files.createTempDirectory("skill-install-");
            try {
                Path repoDir = tmpDir.resolve("repo");

                List<String> cloneCmd = List.of(
                        "git", "clone", "--depth", "1", "--sparse",
                        "--single-branch", "--branch", parsed.ref,
                        parsed.cloneUrl, repoDir.toString()
                );
                this.runProcess(new ProcessBuilder(cloneCmd), 60);

                List<String> sparseCmd = List.of(
                        "git", "-C", repoDir.toString(), "sparse-checkout", "set", effectiveSkillPath
                );
                this.runProcess(new ProcessBuilder(sparseCmd), 30);

                Path srcSkill = repoDir.resolve(effectiveSkillPath);
                if (!Files.isDirectory(srcSkill)) {
                    return new InstallResult(false, "Skill path not found in repo: " + effectiveSkillPath, null);
                }
                Path mdCheck = srcSkill.resolve(SKILL_FILE_NAME);
                if (!Files.exists(mdCheck)) {
                    return new InstallResult(false, "SKILL.md not found in: " + effectiveSkillPath, null);
                }

                Files.createDirectories(destDir);
                this.copyDirectory(srcSkill, destDir);

                return new InstallResult(true, "Installed to " + destDir, skillName);
            } finally {
                this.deleteDirectory(tmpDir);
            }
        } catch (Exception e) {
            log.warn("Git clone 安装 skill 失败: {}", githubUrl, e);
            return new InstallResult(false, "Install failed: " + e.getMessage(), null);
        }
    }

    // ==================== Local Skills Directory Scan ====================

    /**
     * 从本地 skills 目录扫描加载 skills 列表。
     */
    private List<SkillEntry> loadSkillsFromDirectory(String cliType, String scope, String projectPath) {
        Path skillsDir = this.resolveSkillsPath(cliType, scope, projectPath);
        return this.scanSkillsDirectory(cliType, scope, skillsDir);
    }

    /**
     * 根据 CLI 类型、作用域和项目路径解析 Skills 目录路径。
     */
    private Path resolveSkillsPath(String cliType, String scope, String projectPath) {
        boolean isUser = "user".equals(scope);
        return switch (cliType.toUpperCase()) {
            case "CLAUDE" -> isUser
                    ? Path.of(USER_HOME, ".claude", "skills")
                    : (projectPath != null ? Path.of(projectPath, ".claude", "skills") : Path.of("."));
            case "OPENCODE" -> isUser
                    ? Path.of(USER_HOME, ".config", "opencode", "skill")
                    : (projectPath != null ? Path.of(projectPath, ".opencode", "skill") : Path.of("."));
            case "CODEX" -> isUser
                    ? Path.of(USER_HOME, ".codex", "skills")
                    : (projectPath != null ? Path.of(projectPath, ".codex", "skills") : Path.of("."));
            default -> Path.of(".");
        };
    }

    /**
     * 扫描指定目录下的所有 Skill 子目录。
     */
    private List<SkillEntry> scanSkillsDirectory(String cliType, String scope, Path skillsDir) {
        List<SkillEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            return entries;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String dirName = entry.getFileName().toString();
                if (dirName.startsWith(".")) {
                    continue;
                }
                Path mdFile = entry.resolve(SKILL_FILE_NAME);
                if (!Files.exists(mdFile)) {
                    continue;
                }
                SkillEntry skill = this.parseLocalSkill(cliType, scope, dirName, mdFile);
                if (skill != null) {
                    entries.add(skill);
                }
            }
        } catch (IOException e) {
            log.warn("扫描 Skills 目录失败: {}", skillsDir, e);
        }
        return entries;
    }

    /**
     * 解析本地 Skill 的 SKILL.md 文件。
     */
    private SkillEntry parseLocalSkill(String cliType, String scope, String dirName, Path mdFile) {
        try {
            String content = Files.readString(mdFile);
            String name = dirName;
            String description = "";

            String frontmatter = this.extractFrontmatter(content);
            if (frontmatter != null) {
                String frontName = this.extractYamlValue(frontmatter, "name");
                if (frontName != null && !frontName.isBlank()) {
                    name = frontName.trim();
                }
                String frontDesc = this.extractYamlValue(frontmatter, "description");
                if (frontDesc != null) {
                    description = frontDesc.trim();
                }
            }

            BasicFileAttributes attrs = Files.readAttributes(mdFile, BasicFileAttributes.class);

            return SkillEntry.builder()
                    .name(name).description(description).content(null)
                    .scope(scope).cliType(cliType)
                    .skillPath(mdFile.getParent().toString())
                    .enabled(true).source("local").sourceUrl(null)
                    .version("").lastModified(attrs.lastModifiedTime().toMillis())
                    .build();
        } catch (IOException e) {
            log.warn("解析 Skill 文件失败: {}", mdFile, e);
            return null;
        }
    }

    /**
     * 删除本地 skill 目录。
     */
    private DeleteResult deleteLocalSkill(String skillPath) {
        if (skillPath == null || skillPath.isBlank()) {
            return new DeleteResult(false, "Skill path is empty");
        }
        try {
            Path dir = Path.of(skillPath);
            if (!Files.exists(dir)) {
                return new DeleteResult(false, "Skill directory not found: " + skillPath);
            }
            this.deleteDirectory(dir);
            return new DeleteResult(true, "Deleted: " + skillPath);
        } catch (IOException e) {
            log.warn("删除 Skill 失败: {}", skillPath, e);
            return new DeleteResult(false, "Delete failed: " + e.getMessage());
        }
    }

    // ==================== Utility Methods ====================

    /**
     * 执行外部进程并等待完成。
     *
     * @param pb       进程构建器
     * @param timeout  超时秒数
     * @return 进程的标准输出
     */
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

    /**
     * 解析 GitHub URL，提取 owner、repo、ref、subPath。
     */
    private ParsedGitHubUrl parseGitHubUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        String cleaned = url.trim();
        if (!cleaned.startsWith("http")) {
            String[] parts = cleaned.split("/");
            if (parts.length < 2) {
                return null;
            }
            return new ParsedGitHubUrl(
                    parts[0], parts[1], "main", null,
                    "https://github.com/" + parts[0] + "/" + parts[1] + ".git"
            );
        }

        cleaned = cleaned.replace("https://github.com/", "").replace("http://github.com/", "");
        String[] segments = cleaned.split("/");
        if (segments.length < 2) {
            return null;
        }
        String owner = segments[0];
        String repo = segments[1].replace(".git", "");
        String ref = "main";
        String subPath = null;

        if (segments.length > 3 && ("tree".equals(segments[2]) || "blob".equals(segments[2]))) {
            ref = segments[3];
            if (segments.length > 4) {
                StringBuilder sb = new StringBuilder();
                for (int i = 4; i < segments.length; i++) {
                    if (i > 4) {
                        sb.append("/");
                    }
                    sb.append(segments[i]);
                }
                subPath = sb.toString();
            }
        } else if (segments.length > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < segments.length; i++) {
                if (i > 2) {
                    sb.append("/");
                }
                sb.append(segments[i]);
            }
            subPath = sb.toString();
        }

        return new ParsedGitHubUrl(owner, repo, ref, subPath,
                "https://github.com/" + owner + "/" + repo + ".git");
    }

    /**
     * 递归复制目录。
     */
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

    /**
     * 递归删除目录。
     */
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

    /**
     * 从 Markdown 内容中提取 YAML frontmatter。
     */
    private String extractFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("---", 3);
        if (end < 0) {
            return null;
        }
        return content.substring(3, end).trim();
    }

    /**
     * 从 YAML 文本中提取指定键的值（简单 key: value 格式）。
     */
    private String extractYamlValue(String yaml, String key) {
        String prefix = key + ":";
        String[] lines = yaml.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * 安装结果。
     *
     * @param success   是否成功
     * @param message   结果消息
     * @param skillName 安装后的 skill 名称
     */
    public record InstallResult(boolean success, String message, String skillName) {
    }

    /**
     * 删除结果。
     *
     * @param success 是否成功
     * @param message 结果消息
     */
    public record DeleteResult(boolean success, String message) {
    }

    /**
     * 解析后的 GitHub URL 信息。
     */
    private record ParsedGitHubUrl(String owner, String repo, String ref, String subPath, String cloneUrl) {
    }
}
