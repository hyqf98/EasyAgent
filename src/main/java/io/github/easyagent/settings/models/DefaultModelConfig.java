package io.github.easyagent.settings.models;

/**
 * CLI 类型的默认模型配置。
 * <p>
 * 记录每种 CLI 类型对应的默认模型 ID 和上下文窗口大小。
 * 用于在没有选择特定模型时提供默认值。
 * </p>
 *
 * @param modelId       默认模型 ID
 * @param contextWindow 默认上下文窗口大小（token 数量）
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public record DefaultModelConfig(
        String modelId,
        int contextWindow
) {
}
