package io.github.easyagent.settings.models;

import lombok.Builder;

/**
 * 默认模型信息。
 * <p>
 * 当用户不选择特定模型时（不传 {@code --model} 给 CLI），
 * 使用此记录存储默认模型的显示名称和上下文窗口大小。
 * </p>
 *
 * @param displayName   前端展示名称（如 {@code 默认模型}）
 * @param contextWindow 上下文窗口大小（token 数量）
 * @author haijun
 * @date 2026/5/9
 * @since 1.0.0
 */
@Builder
public record DefaultModelInfo(
        String displayName,
        int contextWindow
) {

    /** 默认上下文窗口大小。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /**
     * 获取显示名称，为空时返回默认值。
     *
     * @return 显示名称
     */
    public String safeDisplayName() {
        return (this.displayName != null && !this.displayName.isBlank())
                ? this.displayName
                : "Default";
    }

    /**
     * 获取上下文窗口，无效时返回默认值。
     *
     * @return 上下文窗口大小
     */
    public int safeContextWindow() {
        return this.contextWindow > 0 ? this.contextWindow : DEFAULT_CONTEXT_WINDOW;
    }
}
