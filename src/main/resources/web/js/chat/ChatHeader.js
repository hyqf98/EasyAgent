/**
 * 顶部状态栏组件。
 * <p>
 * 显示会话标题和操作按钮。
 * 聊天模式下显示历史/新建按钮，欢迎模式下仅显示主题和设置。
 * </p>
 *
 * @component chat-header
 */
window.EARegisterComponent('chat-header', 'ChatHeader', {
    props: {
        cliType: { type: String, default: 'CLAUDE' },
        sessionTitle: { type: String, default: '' },
        model: { type: String, default: '' },
        messageCount: { type: Number, default: 0 },
        inChat: { type: Boolean, default: false }
    },
    emits: ['toggle-drawer', 'new-chat', 'open-settings'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        displayTitle() {
            if (this.sessionTitle) return this.sessionTitle;
            if (this.messageCount > 0) return this.i18n.t('chat.title');
            return this.i18n.t('app.title');
        }
    }
});
