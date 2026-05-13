/**
 * 多面板标签栏组件。
 * <p>
 * 显示当前所有打开的面板标签，支持点击切换、关闭、新增面板。
 * 多面板时显示，单面板时隐藏以节省空间。
 * </p>
 *
 * @component tab-bar
 */
window.EARegisterComponent('tab-bar', 'TabBar', {
    emits: ['select-pane', 'close-pane', 'add-pane'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        panes() { return this.store.activePanes; },
        focusedPaneId() { return this.store.focusedPaneId; }
    },
    methods: {
        onTabClick(paneId) {
            this.$emit('select-pane', paneId);
        },
        onTabClose(paneId) {
            this.$emit('close-pane', paneId);
        },
        getTabTitle(pane) {
            if (pane.title) return pane.title;
            var sid = pane.sessionId || '';
            if (sid.indexOf('new-') === 0) return this.i18n.t('tab.newChat');
            if (sid.length <= 10) return sid;
            return sid.substring(0, 8) + '...';
        }
    }
});
