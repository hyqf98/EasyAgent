package io.github.easyagent.ui.service;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.easyagent.ui.service.entity.FileReferenceCandidatePayload;
import io.github.easyagent.ui.service.entity.FileReferencePayload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文件引用服务。
 * <p>
 * 负责搜索项目文件候选并转换为前端输入框和 AI 提示词可复用的结构化文件引用。
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Service(Service.Level.PROJECT)
public final class FileReferenceService {

    /** 普通文件引用类型。 */
    public static final String REFERENCE_TYPE_FILE = "FILE";

    /** 图片引用类型。 */
    public static final String REFERENCE_TYPE_IMAGE = "IMAGE";

    /** 默认候选数量限制。 */
    private static final int DEFAULT_SEARCH_LIMIT = 12;

    /** 项目内图片临时目录。 */
    private static final String TEMP_IMAGE_DIR = "tmp";

    /** 搜索缓存刷新间隔（毫秒）。 */
    private static final long SEARCH_INDEX_TTL_MS = 15_000L;

    private final Project project;

    private volatile List<SearchableFile> searchableFiles;

    private volatile long searchableFilesIndexedAt;

    /**
     * 构造文件引用服务。
     *
     * @param project 当前 IDEA 项目
     */
    public FileReferenceService(Project project) {
        this.project = project;
    }

    /**
     * 根据项目树选中的文件生成引用。
     *
     * @param files 选中的文件数组
     * @return 文件引用列表
     */
    @SuppressWarnings("deprecation")
    public List<FileReferencePayload> createReferences(VirtualFile[] files) {
        return ReadAction.compute(() -> this.createReferencesInternal(files));
    }

    /**
     * 在读动作内根据项目树选中的文件生成引用。
     *
     * @param files 选中的文件数组
     * @return 文件引用列表
     */
    private List<FileReferencePayload> createReferencesInternal(VirtualFile[] files) {
        List<FileReferencePayload> references = new ArrayList<>();
        if (files == null) {
            return references;
        }
        for (VirtualFile file : files) {
            if (file == null || file.isDirectory()) {
                continue;
            }
            FileReferencePayload reference = this.createReference(file, false, null, null);
            if (reference != null) {
                references.add(reference);
            }
        }
        return references;
    }

    /**
     * 根据当前编辑器生成文件引用。
     *
     * @param editor 编辑器实例
     * @param file   当前文件
     * @return 文件引用；无法生成时返回 {@code null}
     */
    @SuppressWarnings("deprecation")
    public FileReferencePayload createReference(@NotNull Editor editor, @Nullable VirtualFile file) {
        return ReadAction.compute(() -> this.createEditorReferenceInternal(editor, file));
    }

    /**
     * 在读动作内根据编辑器生成文件引用。
     *
     * @param editor 编辑器实例
     * @param file 当前文件
     * @return 文件引用；无法生成时返回 {@code null}
     */
    private FileReferencePayload createEditorReferenceInternal(@NotNull Editor editor, @Nullable VirtualFile file) {
        if (file == null || file.isDirectory()) {
            return null;
        }

        boolean selection = editor.getSelectionModel().hasSelection();
        Integer startLine = null;
        Integer endLine = null;
        if (selection) {
            int startOffset = editor.getSelectionModel().getSelectionStart();
            int endOffset = editor.getSelectionModel().getSelectionEnd();
            startLine = editor.getDocument().getLineNumber(startOffset) + 1;
            endLine = editor.getDocument().getLineNumber(Math.max(startOffset, endOffset - 1)) + 1;
        }
        return this.createReference(file, selection, startLine, endLine);
    }

    /**
     * 根据绝对路径生成完整文件引用。
     *
     * @param filePath 文件绝对路径
     * @return 完整文件引用；找不到文件时返回 {@code null}
     */
    @SuppressWarnings("deprecation")
    public FileReferencePayload createReference(String filePath) {
        return ReadAction.compute(() -> this.createPathReferenceInternal(filePath));
    }

    /**
     * 在读动作内根据绝对路径生成完整文件引用。
     *
     * @param filePath 文件绝对路径
     * @return 完整文件引用；找不到文件时返回 {@code null}
     */
    private FileReferencePayload createPathReferenceInternal(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (file == null) {
            return null;
        }
        return this.createReference(file, false, null, null);
    }

    /**
     * 保存剪贴板图片到项目临时目录并生成图片引用。
     *
     * @param dataUrl  图片 Data URL
     * @param fileName 建议文件名
     * @return 图片引用；保存失败时返回 {@code null}
     */
    public FileReferencePayload createImageReference(String dataUrl, String fileName) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }
        String basePath = this.project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }

        String mimeType = extractMimeType(dataUrl);
        String extension = resolveImageExtension(fileName, mimeType);
        String safeBaseName = sanitizeBaseName(fileName);
        Path tempDir = Path.of(basePath).resolve(TEMP_IMAGE_DIR);
        Path imagePath = ensureUniquePath(tempDir.resolve(safeBaseName + "." + extension));

        try {
            Files.createDirectories(tempDir);
            Files.write(imagePath, decodeDataUrl(dataUrl));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }

        LocalFileSystem.getInstance().refreshAndFindFileByPath(imagePath.toString());
        String relativePath = this.toRelativePath(imagePath);
        return FileReferencePayload.builder()
                .id(buildReferenceId(imagePath.toString(), null, null))
                .path(imagePath.toString())
                .relativePath(relativePath)
                .displayName(safeBaseName)
                .referenceType(REFERENCE_TYPE_IMAGE)
                .inlineToken(null)
                .startLine(null)
                .endLine(null)
                .selection(false)
                .content(null)
                .truncated(false)
                .build();
    }

    /**
     * 搜索当前项目中的文件候选。
     *
     * @param query 搜索关键字
     * @param limit 返回数量限制
     * @return 候选列表
     */
    @SuppressWarnings("deprecation")
    public List<FileReferenceCandidatePayload> searchCandidates(String query, Integer limit) {
        return ReadAction.compute(() -> this.searchCandidatesInternal(query, limit));
    }

    /**
     * 在读动作内搜索当前项目中的文件候选。
     *
     * @param query 搜索关键字
     * @param limit 返回数量限制
     * @return 候选列表
     */
    private List<FileReferenceCandidatePayload> searchCandidatesInternal(String query, Integer limit) {
        List<SearchableFile> files = this.getSearchableFiles();
        if (files.isEmpty()) {
            return List.of();
        }

        String normalizedQuery = normalize(query);
        int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;
        List<SearchResult> ranked = this.rankSearchResults(files, normalizedQuery);
        if (ranked.isEmpty() && !normalizedQuery.isBlank()) {
            ranked = this.rankSearchResults(this.refreshSearchableFiles(), normalizedQuery);
        }

        ranked.sort(Comparator
                .comparingInt(SearchResult::score).reversed()
                .thenComparing(result -> result.file().displayName()));

        List<FileReferenceCandidatePayload> candidates = new ArrayList<>();
        for (int i = 0; i < Math.min(effectiveLimit, ranked.size()); i++) {
            SearchableFile file = ranked.get(i).file();
            candidates.add(FileReferenceCandidatePayload.builder()
                    .path(file.path())
                    .relativePath(file.relativePath())
                    .displayName(file.displayName())
                    .fileName(file.fileName())
                    .build());
        }
        return candidates;
    }

    /**
     * 对文件候选进行打分并筛选可命中的项目。
     *
     * @param files            可搜索文件列表
     * @param normalizedQuery  归一化后的查询词
     * @return 带得分的搜索结果
     */
    private List<SearchResult> rankSearchResults(List<SearchableFile> files, String normalizedQuery) {
        List<SearchResult> ranked = new ArrayList<>();
        for (SearchableFile file : files) {
            int score = scoreFile(file, normalizedQuery);
            if (score >= 0) {
                ranked.add(new SearchResult(file, score));
            }
        }
        return ranked;
    }

    /**
     * 将结构化文件引用拼装成追加提示词。
     *
     * @param prompt      用户原始输入
     * @param references 结构化文件引用
     * @return 追加后的提示词
     */
    public String enrichPrompt(String prompt, List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return prompt;
        }

        String rawPrompt = prompt == null ? "" : prompt;
        List<FileReferencePayload> remaining = new ArrayList<>(references.stream()
                .filter(reference -> reference != null)
                .toList());
        List<ReferenceOccurrence> occurrences = collectReferenceOccurrences(rawPrompt, remaining);
        StringBuilder builder = new StringBuilder();

        if (occurrences.isEmpty()) {
            String normalizedPrompt = stripInlineTokens(rawPrompt, references);
            if (normalizedPrompt != null && !normalizedPrompt.isBlank()) {
                builder.append(normalizedPrompt.trim());
            }
            appendTrailingReferences(builder, remaining);
            return builder.toString().trim();
        }

        int cursor = 0;
        for (ReferenceOccurrence occurrence : occurrences) {
            if (occurrence.index() > cursor) {
                builder.append(rawPrompt, cursor, occurrence.index());
            }
            appendReferenceSnippet(builder, occurrence.reference());
            cursor = occurrence.index() + occurrence.reference().inlineToken().length();
            remaining.remove(occurrence.reference());
        }

        if (cursor < rawPrompt.length()) {
            builder.append(rawPrompt.substring(cursor));
        }

        appendTrailingReferences(builder, remaining);
        return builder.toString().trim();
    }

    /**
     * 构造单个文件引用。
     *
     * @param file       目标文件
     * @param selection  是否来自选区
     * @param startLine  起始行号
     * @param endLine    结束行号
     * @return 文件引用
     */
    private FileReferencePayload createReference(@NotNull VirtualFile file,
                                                 boolean selection,
                                                 @Nullable Integer startLine,
                                                 @Nullable Integer endLine) {
        Path path = Path.of(file.getPath()).normalize();
        String relativePath = toRelativePath(path);
        String displayName = relativePath != null ? relativePath : file.getName();

        return FileReferencePayload.builder()
                .id(buildReferenceId(path.toString(), startLine, endLine))
                .path(path.toString())
                .relativePath(relativePath)
                .displayName(displayName)
                .referenceType(REFERENCE_TYPE_FILE)
                .inlineToken(null)
                .startLine(startLine)
                .endLine(endLine)
                .selection(selection)
                .content(null)
                .truncated(false)
                .build();
    }

    /**
     * 从提示词中移除前端输入框用到的占位符。
     *
     * @param prompt 原始提示词
     * @param references 引用列表
     * @return 清理后的提示词
     */
    private static String stripInlineTokens(String prompt, List<FileReferencePayload> references) {
        String normalized = prompt == null ? "" : prompt;
        for (FileReferencePayload reference : references) {
            if (reference == null || reference.inlineToken() == null || reference.inlineToken().isBlank()) {
                continue;
            }
            normalized = normalized.replace(reference.inlineToken(), "");
        }
        return normalized.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * 根据当前内容决定是否在追加区块前插入空行。
     *
     * @param builder 提示词构造器
     */
    private static void appendSectionBreak(StringBuilder builder) {
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
    }

    /**
     * 收集提示词中出现的引用占位符位置。
     *
     * @param prompt      原始提示词
     * @param references 引用列表
     * @return 按出现位置排序的占位符列表
     */
    private static List<ReferenceOccurrence> collectReferenceOccurrences(String prompt,
                                                                         List<FileReferencePayload> references) {
        List<ReferenceOccurrence> occurrences = new ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            return occurrences;
        }
        for (FileReferencePayload reference : references) {
            if (reference == null || reference.inlineToken() == null || reference.inlineToken().isBlank()) {
                continue;
            }
            int index = prompt.indexOf(reference.inlineToken());
            if (index >= 0) {
                occurrences.add(new ReferenceOccurrence(index, reference));
            }
        }
        occurrences.sort(Comparator.comparingInt(ReferenceOccurrence::index));
        return occurrences;
    }

    /**
     * 在提示词当前位置插入一个引用片段。
     *
     * @param builder   提示词构造器
     * @param reference 当前引用
     */
    private static void appendReferenceSnippet(StringBuilder builder, FileReferencePayload reference) {
        if (reference == null) {
            return;
        }
        appendSectionBreak(builder);
        if (REFERENCE_TYPE_IMAGE.equals(reference.referenceType())) {
            builder.append("Referenced image: ")
                    .append(reference.relativePath() != null ? reference.relativePath() : reference.path())
                    .append('\n');
            return;
        }

        builder.append("Referenced file: ")
                .append(reference.relativePath() != null ? reference.relativePath() : reference.path());
        if (reference.startLine() != null && reference.endLine() != null) {
            builder.append(" (lines ")
                    .append(reference.startLine())
                    .append('-')
                    .append(reference.endLine())
                    .append(')');
        }
        builder.append('\n');
    }

    /**
     * 将未出现在占位符序列中的引用追加到提示词尾部。
     *
     * @param builder    提示词构造器
     * @param references 剩余引用
     */
    private static void appendTrailingReferences(StringBuilder builder, List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        appendSectionBreak(builder);
        builder.append("Additional references:\n");
        for (int i = 0; i < references.size(); i++) {
            appendReferenceSnippet(builder, references.get(i));
            if (i < references.size() - 1) {
                builder.append('\n');
            }
        }
    }

    /**
     * 引用占位符位置。
     *
     * @param index     原始提示词中的起始偏移
     * @param reference 关联引用
     */
    private record ReferenceOccurrence(int index, FileReferencePayload reference) {}

    /**
     * 将绝对路径转换为项目相对路径。
     *
     * @param path 目标路径
     * @return 相对路径；无法转换时返回 {@code null}
     */
    private String toRelativePath(Path path) {
        String basePath = this.project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        try {
            return Path.of(basePath).normalize().relativize(path).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 基于文件路径和范围生成稳定引用 ID。
     *
     * @param path      文件路径
     * @param startLine 起始行
     * @param endLine   结束行
     * @return 引用 ID
     */
    private static String buildReferenceId(String path, Integer startLine, Integer endLine) {
        String raw = path + "|" + startLine + "|" + endLine;
        return "ref-" + Integer.toHexString(raw.hashCode());
    }

    /**
     * 从 Data URL 中提取 MIME 类型。
     *
     * @param dataUrl 图片 Data URL
     * @return MIME 类型；提取失败时返回 {@code image/png}
     */
    private static String extractMimeType(String dataUrl) {
        int prefixEnd = dataUrl.indexOf(";base64,");
        if (dataUrl.startsWith("data:") && prefixEnd > 5) {
            return dataUrl.substring(5, prefixEnd);
        }
        return "image/png";
    }

    /**
     * 将 Data URL 解码为图片字节。
     *
     * @param dataUrl 图片 Data URL
     * @return 二进制内容
     */
    private static byte[] decodeDataUrl(String dataUrl) {
        int marker = dataUrl.indexOf("base64,");
        String encoded = marker >= 0 ? dataUrl.substring(marker + 7) : dataUrl;
        return Base64.getDecoder().decode(encoded);
    }

    /**
     * 根据文件名和 MIME 类型推断图片扩展名。
     *
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @return 图片扩展名
     */
    private static String resolveImageExtension(String fileName, String mimeType) {
        if (fileName != null) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
                return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
            }
        }
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    /**
     * 将图片基础文件名清洗为安全路径片段。
     *
     * @param fileName 原始文件名
     * @return 安全文件名
     */
    private static String sanitizeBaseName(String fileName) {
        String candidate = fileName == null || fileName.isBlank() ? "Image" : fileName;
        int dotIndex = candidate.lastIndexOf('.');
        String baseName = dotIndex > 0 ? candidate.substring(0, dotIndex) : candidate;
        String sanitized = baseName.replaceAll("[^A-Za-z0-9._-]", "");
        return sanitized.isBlank() ? "Image" : sanitized;
    }

    /**
     * 如果目标路径已存在，则生成一个不冲突的新路径。
     *
     * @param path 初始目标路径
     * @return 可写入的新路径
     */
    private static Path ensureUniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
        int counter = 1;
        while (true) {
            Path candidate = path.getParent().resolve(baseName + "-" + counter + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    /**
     * 推断 Markdown 代码块语言。
     *
     * @param filePath 文件路径
     * @return 语言标识
     */
    private static String detectFenceLanguage(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(dotIndex + 1);
    }

    /**
     * 获取项目内可搜索的文件缓存。
     *
     * @return 可搜索文件列表
     */
    private List<SearchableFile> getSearchableFiles() {
        List<SearchableFile> cached = this.searchableFiles;
        if (cached != null && System.currentTimeMillis() - this.searchableFilesIndexedAt < SEARCH_INDEX_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            if (this.searchableFiles != null
                    && System.currentTimeMillis() - this.searchableFilesIndexedAt < SEARCH_INDEX_TTL_MS) {
                return this.searchableFiles;
            }
            return this.refreshSearchableFiles();
        }
    }

    /**
     * 强制刷新项目文件搜索缓存。
     *
     * @return 最新的可搜索文件列表
     */
    private List<SearchableFile> refreshSearchableFiles() {
        this.searchableFiles = this.collectProjectFiles();
        this.searchableFilesIndexedAt = System.currentTimeMillis();
        return this.searchableFiles;
    }

    /**
     * 扫描项目内容根内的所有文本文件。
     *
     * @return 搜索文件列表
     */
    private List<SearchableFile> collectProjectFiles() {
        List<SearchableFile> files = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectRootManager.getInstance(this.project).getFileIndex();
        fileIndex.iterateContent(fileOrDir -> {
            if (fileOrDir.isDirectory() || fileOrDir.getFileType().isBinary()) {
                return true;
            }
            Path path = Path.of(fileOrDir.getPath()).normalize();
            String relativePath = this.toRelativePath(path);
            if (relativePath == null || relativePath.isBlank()) {
                return true;
            }
            files.add(new SearchableFile(
                    path.toString(),
                    relativePath,
                    fileOrDir.getName(),
                    relativePath
            ));
            return true;
        });
        files.sort(Comparator.comparing(SearchableFile::displayName));
        return files;
    }

    /**
     * 归一化查询文本。
     *
     * @param value 原始文本
     * @return 归一化文本
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 计算文件候选得分。
     *
     * @param file            文件候选
     * @param normalizedQuery 归一化后的查询词
     * @return 匹配得分；不匹配时返回 -1
     */
    private static int scoreFile(SearchableFile file, String normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return file.fileName().startsWith(".") ? 0 : 50;
        }

        String name = normalize(file.fileName());
        String path = normalize(file.relativePath());
        if (name.equals(normalizedQuery)) {
            return 1_000;
        }
        if (name.startsWith(normalizedQuery)) {
            return 850 - (name.length() - normalizedQuery.length());
        }
        if (path.startsWith(normalizedQuery)) {
            return 800 - (path.length() - normalizedQuery.length());
        }
        int nameContains = name.indexOf(normalizedQuery);
        if (nameContains >= 0) {
            return 700 - nameContains * 2;
        }
        int pathContains = path.indexOf(normalizedQuery);
        if (pathContains >= 0) {
            return 620 - pathContains;
        }

        int fuzzyName = fuzzyScore(name, normalizedQuery);
        if (fuzzyName >= 0) {
            return 520 + fuzzyName;
        }
        int fuzzyPath = fuzzyScore(path, normalizedQuery);
        if (fuzzyPath >= 0) {
            return 420 + fuzzyPath;
        }
        return -1;
    }

    /**
     * 子序列模糊匹配得分。
     *
     * @param target 目标文本
     * @param query  查询文本
     * @return 得分；不匹配时返回 -1
     */
    private static int fuzzyScore(String target, String query) {
        int targetIndex = 0;
        int firstMatch = -1;
        int previousMatch = -1;
        int gapPenalty = 0;

        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            int foundIndex = target.indexOf(ch, targetIndex);
            if (foundIndex < 0) {
                return -1;
            }
            if (firstMatch < 0) {
                firstMatch = foundIndex;
            }
            if (previousMatch >= 0) {
                gapPenalty += foundIndex - previousMatch - 1;
            }
            previousMatch = foundIndex;
            targetIndex = foundIndex + 1;
        }
        return Math.max(0, 120 - firstMatch * 3 - gapPenalty * 4);
    }

    /**
     * 搜索缓存中的文件项。
     *
     * @param path         绝对路径
     * @param relativePath 相对路径
     * @param fileName     文件名
     * @param displayName  展示名称
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record SearchableFile(
            String path,
            String relativePath,
            String fileName,
            String displayName
    ) {}

    /**
     * 带得分的搜索结果。
     *
     * @param file  文件候选
     * @param score 匹配得分
     * @author haijun
     * @date 2026/5/7
     * @since 1.0.0
     */
    private record SearchResult(
            SearchableFile file,
            int score
    ) {}
}
