package io.github.easyagent.settings.skills;

import io.github.easyagent.util.GsonUtils;
import lombok.extern.slf4j.Slf4j;

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
 * 负责 Claude / OpenCode / Codex 三个 CLI 的 Skills 读取和安装，
 * 统一使用目录扫描 + git clone sparse 方式，不依赖任何 CLI 专有命令。
 * </p>
 *
 * <h3>Skills 目录路径</h3>
 * <table>
 *   <tr><th>CLI</th><th>用户级路径</th><th>项目级路径</th></tr>
 *   <tr><td>Claude</td><td>{@code ~/.claude/skills/}</td><td>{@code <project>/.claude/skills/}</td></tr>
 *   <tr><td>OpenCode</td><td>{@code ~/.config/opencode/skill/}</td><td>{@code <project>/.opencode/skill/}</td></tr>
 *   <tr><td>Codex</td><td>{@code ~/.codex/skills/}</td><td>{@code <project>/.codex/skills/}</td></tr>
 * </table>
 *
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Slf4j
public class SkillsConfigService {

    private static final String USER_HOME = System.getProperty("user.home");
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** 当前项目根路径，用于解析项目级 skills 目录。 */
    private final String projectPath;

    /**
     * 构造服务实例。
     *
     * @param projectPath 当前 IDEA 项目根路径，可为 null
     */
    public SkillsConfigService(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * 加载指定 CLI 类型在当前项目路径下的所有 Skills 配置。
     * <p>
     * 先加载用户级 skills，再加载项目级 skills（若有项目路径）。
     * </p>
     *
     * @param cliType CLI 类型：CLAUDE / OPENCODE / CODEX
     * @return Skill 条目列表，合并用户级和项目级
     */
    public List<SkillEntry> loadSkills(String cliType) {
        List<SkillEntry> result = new ArrayList<>();
        result.addAll(this.scanDirectory(cliType, "user"));
        if (this.projectPath != null && !this.projectPath.isBlank()) {
            result.addAll(this.scanDirectory(cliType, "project"));
        }
        return result;
    }

    /**
     * 从 GitHub 仓库安装 Skill 到指定 CLI 和作用域。
     *
     * @param cliType   CLI 类型：CLAUDE / OPENCODE / CODEX
     * @param githubUrl GitHub 仓库地址，支持格式：
     *                  <ul>
     *                    <li>{@code https://github.com/owner/repo}</li>
     *                    <li>{@code https://github.com/owner/repo/tree/branch/path/to/skill}</li>
     *                    <li>{@code owner/repo}</li>
     *                  </ul>
     * @param skillPath skill 在仓库中的路径（可选，为空时取 URL 中的 subPath）
     * @param scope     安装作用域：user 或 project
     * @return 安装结果
     */
    public InstallResult installSkill(String cliType, String githubUrl, String skillPath, String scope) {
        return this.installViaGitClone(cliType.toUpperCase(), githubUrl, skillPath, scope);
    }

    /**
     * 删除指定 Skill（直接删除目录）。
     *
     * @param cliType   CLI 类型（用于日志）
     * @param skillName skill 名称（用于日志）
     * @param skillPath skill 目录绝对路径
     * @return 删除结果
     */
    public DeleteResult deleteSkill(String cliType, String skillName, String skillPath) {
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

    /**
     * 读取指定 Skill 的完整 SKILL.md 内容。
     *
     * @param skillPath 技能目录绝对路径
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

    // ==================== Directory Scan ====================

    /**
     * 扫描指定 CLI 和作用域的 skills 目录。
     *
     * @param cliType CLI 类型
     * @param scope   作用域：user 或 project
     * @return 扫描到的 skill 列表
     */
    private List<SkillEntry> scanDirectory(String cliType, String scope) {
        Path skillsDir = this.resolveSkillsPath(cliType.toUpperCase(), scope);
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        List<SkillEntry> entries = new ArrayList<>();
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
                SkillEntry skill = this.parseSkillFile(cliType.toUpperCase(), scope, dirName, mdFile);
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
     * 解析单个 SKILL.md 文件生成 {@link SkillEntry}。
     *
     * @param cliType CLI 类型
     * @param scope   作用域
     * @param dirName 目录名
     * @param mdFile  SKILL.md 文件路径
     * @return 解析后的 skill 条目，解析失败返回 null
     */
    private SkillEntry parseSkillFile(String cliType, String scope, String dirName, Path mdFile) {
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

    // ==================== Git Clone Install ====================

    /**
     * 通过 git clone --sparse 从 GitHub 仓库安装 skill。
     * <p>
     * 根据 scope 参数决定安装到用户级目录还是项目级目录。
     * </p>
     *
     * @param cliType   CLI 类型
     * @param githubUrl GitHub 仓库地址
     * @param skillPath skill 在仓库中的路径
     * @param scope     安装作用域：user 或 project
     * @return 安装结果
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
            Path destDir = this.resolveSkillsPath(cliType, scope).resolve(skillName);
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

    // ==================== Path Resolution ====================

    /**
     * 根据 CLI 类型、作用域和项目路径解析 Skills 目录路径。
     *
     * <table>
     *   <tr><th>CLI</th><th>用户级</th><th>项目级</th></tr>
     *   <tr><td>CLAUDE</td><td>{@code ~/.claude/skills}</td><td>{@code <project>/.claude/skills}</td></tr>
     *   <tr><td>OPENCODE</td><td>{@code ~/.config/opencode/skill}</td><td>{@code <project>/.opencode/skill}</td></tr>
     *   <tr><td>CODEX</td><td>{@code ~/.codex/skills}</td><td>{@code <project>/.codex/skills}</td></tr>
     * </table>
     *
     * @param cliType CLI 类型
     * @param scope   作用域：user 或 project
     * @return skills 目录路径
     */
    private Path resolveSkillsPath(String cliType, String scope) {
        boolean isUser = "user".equals(scope);
        if (isUser) {
            return switch (cliType) {
                case "CLAUDE" -> Path.of(USER_HOME, ".claude", "skills");
                case "OPENCODE" -> Path.of(USER_HOME, ".config", "opencode", "skill");
                case "CODEX" -> Path.of(USER_HOME, ".codex", "skills");
                default -> Path.of(".");
            };
        }
        if (this.projectPath != null && !this.projectPath.isBlank()) {
            return switch (cliType) {
                case "CLAUDE" -> Path.of(this.projectPath, ".claude", "skills");
                case "OPENCODE" -> Path.of(this.projectPath, ".opencode", "skill");
                case "CODEX" -> Path.of(this.projectPath, ".codex", "skills");
                default -> Path.of(".");
            };
        }
        return Path.of(".");
    }

    // ==================== Utility Methods ====================

    /**
     * 执行外部进程并等待完成。
     *
     * @param pb      进程构建器
     * @param timeout 超时秒数
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
     *
     * @param url GitHub URL 或 owner/repo 格式
     * @return 解析结果，无效 URL 返回 null
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
     *
     * @param src  源目录
     * @param dest 目标目录
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
     *
     * @param dir 要删除的目录
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
     *
     * @param content Markdown 文本
     * @return frontmatter 文本，无 frontmatter 返回 null
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
     *
     * @param yaml YAML 文本
     * @param key  键名
     * @return 值文本，未找到返回 null
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
     *
     * @param owner    仓库所有者
     * @param repo     仓库名
     * @param ref      分支/标签
     * @param subPath  仓库内子路径
     * @param cloneUrl git clone URL
     */
    private record ParsedGitHubUrl(String owner, String repo, String ref, String subPath, String cloneUrl) {
    }
}
