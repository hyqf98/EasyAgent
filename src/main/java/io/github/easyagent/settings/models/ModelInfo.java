package io.github.easyagent.settings.models;

import io.github.easyagent.enums.CLIType;
import lombok.Builder;

/**
 * AI 模型配置信息。
 * <p>
 * 记录一个 AI 模型的标识、显示名称、所属 CLI 类型、上下文窗口大小和提供商。
 * 支持从远程配置同步、本地持久化和前端编辑。
 * </p>
 *
 * @param modelId       模型唯一标识（如 {@code claude-sonnet-4-20250514}）
 * @param displayName   前端展示名称（如 {@code Claude Sonnet 4}）
 * @param cliType       所属 CLI 类型（{@link CLIType#CLAUDE}、{@link CLIType#OPENCODE}、{@link CLIType#CODEX}）
 * @param contextWindow 上下文窗口大小（token 数量）
 * @param provider      模型提供商（如 {@code anthropic}、{@code openai}）
 * @param npmPackage    NPM 包名（如 {@code @ai-sdk/openai-compatible}），仅 OpenCode 使用
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Builder
public record ModelInfo(
        String modelId,
        String displayName,
        CLIType cliType,
        int contextWindow,
        String provider,
        String npmPackage
) {

    /** 默认上下文窗口大小（128K）。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /**
     * 解析上下文窗口大小字符串为整数。
     * <p>
     * 支持格式：
     * <ul>
     *   <li>纯数字：{@code "128000"} → 128000</li>
     *   <li>K 后缀：{@code "128K"} → 128000, {@code "128k"} → 128000</li>
     *   <li>M 后缀：{@code "2M"} → 2000000, {@code "1.5M"} → 1500000</li>
     * </ul>
     * </p>
     *
     * @param value 上下文窗口大小字符串
     * @return 解析后的 token 数量，解析失败返回 {@link #DEFAULT_CONTEXT_WINDOW}
     */
    public static int parseContextWindow(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        String trimmed = value.trim().toUpperCase();
        try {
            if (trimmed.endsWith("M")) {
                double num = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim());
                return (int) (num * 1_000_000);
            }
            if (trimmed.endsWith("K")) {
                double num = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim());
                return (int) (num * 1_000);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return DEFAULT_CONTEXT_WINDOW;
        }
    }

    /**
     * 将上下文窗口大小格式化为人类可读字符串。
     *
     * @param contextWindow 上下文窗口大小（token 数量）
     * @return 格式化字符串，如 {@code "128K"}、{@code "1M"}
     */
    public static String formatContextWindow(int contextWindow) {
        if (contextWindow <= 0) {
            return "128K";
        }
        if (contextWindow >= 1_000_000 && contextWindow % 1_000_000 == 0) {
            return (contextWindow / 1_000_000) + "M";
        }
        if (contextWindow % 1_000 == 0) {
            return (contextWindow / 1_000) + "K";
        }
        return String.valueOf(contextWindow);
    }
}
