/**
 * IDE 主题管理工具。
 * <p>
 * 主题默认跟随 IDE 设置，由 Java 端通过 {@code __ea_onThemeChanged} 推送。
 * 不再支持手动切换主题，始终与 IDE 主题同步。
 * </p>
 *
 * @namespace EATheme
 */
window.EATheme = {
    /** 当前是否为深色主题。 */
    isDark: false,

    /**
     * 初始化主题。优先使用系统深浅色，等待 IDE 推送最终主题。
     */
    init() {
        var prefersDark = false;
        if (window.matchMedia) {
            prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        }
        this.apply(prefersDark);
    },

    /**
     * 应用指定主题模式。
     *
     * @param {boolean} isDark - {@code true} 应用深色主题，{@code false} 应用浅色主题
     */
    apply(isDark) {
        this.isDark = isDark;
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
        document.documentElement.style.colorScheme = isDark ? 'dark' : 'light';
        if (window.EAStore) {
            window.EAStore.themeMode = isDark ? 'dark' : 'light';
        }
    }
};
