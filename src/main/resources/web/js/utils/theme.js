/**
 * IDE 主题管理工具。
 * <p>
 * 主题默认跟随 IDE 设置，由 Java 端通过 {@code __ea_onThemeChanged} 推送。
 * 不再支持手动切换主题，始终与 IDE 主题同步。
 * Java 端推送包含 {@code isDark} 和 {@code colors} map，
 * colors map 中的 CSS 变量会通过 {@code style.setProperty} 直接注入到 :root。
 * </p>
 *
 * @namespace EATheme
 */
window.EATheme = {
    /** 当前是否为深色主题。 */
    isDark: false,

    /**
     * 初始化主题。默认使用浅色主题避免闪烁，等待 IDE 推送实际主题。
     */
    init() {
        this.apply(false);
    },

    /**
     * 应用指定主题模式并注入 IDE 提供的颜色变量。
     *
     * @param {boolean} isDark - {@code true} 应用深色主题，{@code false} 应用浅色主题
     * @param {Object} [colors] - IDE 推送的 CSS 变量键值对（如 {@code {"--ea-bg": "#2b2b2b"}}）
     */
    apply(isDark, colors) {
        this.isDark = isDark;
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
        document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';

        var root = document.documentElement;
        if (colors && typeof colors === 'object') {
            var keys = Object.keys(colors);
            for (var i = 0; i < keys.length; i++) {
                root.style.setProperty(keys[i], colors[keys[i]]);
            }
        }

        if (window.EAStore) {
            window.EAStore.themeMode = isDark ? 'dark' : 'light';
        }
    }
};
