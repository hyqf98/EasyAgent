package io.github.easyagent.ui.enums;

import lombok.Getter;

import java.awt.Color;

/**
 * IDE 主题类型枚举。
 * <p>
 * 定义 EasyAgent 插件支持的主题模式，与 IDE LookAndFeel 同步。
 * </p>
 *
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
@Getter
public enum ThemeType {

    /** 浅色主题。 */
    LIGHT(false),

    /** 深色主题。 */
    DARK(true);

    /** 是否为深色主题。 */
    private final boolean dark;

    ThemeType(boolean dark) {
        this.dark = dark;
    }

    /**
     * 根据是否为深色模式获取主题类型。
     *
     * @param isDark 是否为深色模式
     * @return 对应的主题类型
     */
    public static ThemeType fromDark(boolean isDark) {
        return isDark ? DARK : LIGHT;
    }

    /**
     * 根据 IDE LookAndFeel 名称自动推断主题类型。
     *
     * @param lafName LookAndFeel 名称
     * @return 匹配的主题类型，默认返回 {@link #LIGHT}
     */
    public static ThemeType fromLafName(String lafName) {
        if (lafName == null) {
            return LIGHT;
        }
        String lower = lafName.toLowerCase();
        if (lower.contains("dark") || lower.contains("darcula")) {
            return DARK;
        }
        return LIGHT;
    }

    /**
     * 根据 UI 背景色和 LookAndFeel 名称推断主题类型。
     *
     * @param color   UI 背景色
     * @param lafName LookAndFeel 名称
     * @return 推断出的主题类型
     */
    public static ThemeType fromUiColor(Color color, String lafName) {
        if (color == null) {
            return fromLafName(lafName);
        }
        int brightness = (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
        return brightness < 140 ? DARK : LIGHT;
    }
}
