package io.github.easyagent.ui.service;

import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.ui.service.entity.FileEditPayload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 工具调用元数据解析工具。
 * <p>
 * 负责从不同 CLI 的工具调用结构中提取统一的文件编辑元数据，
 * 供前端展示和 IDEA 文件 diff/revert 能力复用。
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public final class ToolMetadataSupport {

    /** 常见文件路径字段名。 */
    private static final List<String> PATH_KEYS = List.of(
            "file_path", "path", "filepath", "filePath", "filename", "target_file", "targetFile");

    /** 支持视为文件编辑的工具名关键词。 */
    private static final List<String> FILE_EDIT_TOOL_KEYWORDS = List.of(
            "edit", "write", "create", "patch", "replace", "multi_edit", "multiedit");

    /** 常见路径字段的匹配模式。 */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "\"(?:file_path|path|filepath|filePath|filename|target_file|targetFile)\"\\s*:\\s*\"([^\"]+)\"");

    private ToolMetadataSupport() {
    }

    /**
     * 从流式工具调用事件中解析文件编辑元数据。
     *
     * @param sessionId   会话 ID
     * @param projectPath 当前项目路径
     * @param toolCall    工具调用内容
     * @return 文件编辑元数据；不是文件编辑工具时返回 {@code null}
     */
    public static FileEditPayload resolveFileEdit(String sessionId, String projectPath, ToolCallContent toolCall) {
        if (toolCall == null) {
            return null;
        }
        return buildFileEdit(sessionId, projectPath, toolCall.toolCallId(), toolCall.toolName(), toolCall.input());
    }

    /**
     * 从历史消息内容块中解析文件编辑元数据。
     *
     * @param sessionId   会话 ID
     * @param projectPath 当前项目路径
     * @param block       历史内容块
     * @return 文件编辑元数据；不是文件编辑工具时返回 {@code null}
     */
    public static FileEditPayload resolveFileEdit(String sessionId, String projectPath, ContentBlock block) {
        if (block == null || block.toolName() == null) {
            return null;
        }
        return buildFileEdit(sessionId, projectPath, block.toolUseId(), block.toolName(), block.toolInput());
    }

    /**
     * 归一化生成文件编辑元数据。
     *
     * @param sessionId   会话 ID
     * @param projectPath 当前项目路径
     * @param toolCallId  工具调用 ID
     * @param toolName    工具名称
     * @param input       工具输入参数
     * @return 文件编辑元数据或 {@code null}
     */
    private static FileEditPayload buildFileEdit(String sessionId, String projectPath,
                                                 String toolCallId, String toolName,
                                                 String inputJson) {
        String normalizedToolName = normalize(toolName);
        if (!isFileEditTool(normalizedToolName)) {
            return null;
        }

        String rawPath = extractPath(inputJson);
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }

        Path resolved = resolvePath(projectPath, rawPath);
        String absolutePath = resolved.toString();
        String relativePath = toRelativePath(projectPath, resolved);
        String displayName = relativePath != null ? relativePath : resolved.getFileName().toString();
        String effectiveSessionId = sessionId != null ? sessionId : "unknown-session";
        String effectiveToolCallId = toolCallId != null && !toolCallId.isBlank()
                ? toolCallId
                : normalizedToolName + ":" + absolutePath;

        return FileEditPayload.builder()
                .editId(buildEditId(effectiveSessionId, effectiveToolCallId, absolutePath))
                .toolCallId(effectiveToolCallId)
                .toolName(toolName)
                .operation(resolveOperation(normalizedToolName))
                .path(absolutePath)
                .relativePath(relativePath)
                .displayName(displayName)
                .build();
    }

    /**
     * 判断是否为文件编辑工具。
     *
     * @param normalizedToolName 归一化工具名
     * @return 是否为文件编辑工具
     */
    private static boolean isFileEditTool(String normalizedToolName) {
        if (normalizedToolName == null || normalizedToolName.isBlank()) {
            return false;
        }
        for (String keyword : FILE_EDIT_TOOL_KEYWORDS) {
            if (normalizedToolName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从工具输入 JSON 中提取文件路径。
     *
     * @param inputJson 输入 JSON
     * @return 文件路径；不存在时返回 {@code null}
     */
    private static String extractPath(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        Matcher matcher = PATH_PATTERN.matcher(inputJson);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 归一化工具名称。
     *
     * @param toolName 原始工具名
     * @return 归一化结果
     */
    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 解析编辑操作名称。
     *
     * @param normalizedToolName 归一化工具名
     * @return 操作名称
     */
    private static String resolveOperation(String normalizedToolName) {
        if (normalizedToolName.contains("multi")) {
            return "multi-edit";
        }
        if (normalizedToolName.contains("create")) {
            return "create";
        }
        if (normalizedToolName.contains("write")) {
            return "write";
        }
        if (normalizedToolName.contains("patch")) {
            return "patch";
        }
        if (normalizedToolName.contains("replace")) {
            return "replace";
        }
        return "edit";
    }

    /**
     * 解析工具输入中的文件路径。
     *
     * @param projectPath 项目路径
     * @param rawPath     原始路径
     * @return 绝对路径
     */
    private static Path resolvePath(String projectPath, @NotNull String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute() || projectPath == null || projectPath.isBlank()) {
            return path.normalize();
        }
        return Path.of(projectPath).resolve(path).normalize();
    }

    /**
     * 计算相对项目路径。
     *
     * @param projectPath 项目路径
     * @param resolved    绝对路径
     * @return 相对路径；无法计算时返回 {@code null}
     */
    private static String toRelativePath(String projectPath, Path resolved) {
        if (projectPath == null || projectPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(projectPath).normalize().relativize(resolved).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 构造稳定的编辑 ID。
     *
     * @param sessionId   会话 ID
     * @param toolCallId  工具调用 ID
     * @param absolutePath 绝对路径
     * @return 编辑 ID
     */
    private static String buildEditId(String sessionId, String toolCallId, String absolutePath) {
        String raw = sessionId + "|" + toolCallId + "|" + absolutePath;
        return "edit-" + Integer.toHexString(raw.hashCode());
    }
}
