/**
 * 消息气泡组件。
 * <p>
 * 渲染单条对话消息，根据 role 属性决定左右布局。
 * 支持：TEXT、THINKING、TOOL_USE、TODO_LIST、ERROR。
 * </p>
 *
 * @component message-bubble
 */
window.EARegisterComponent('message-bubble', 'MessageBubble', {
    props: {
        message: { type: Object, required: true },
        isLast: { type: Boolean, default: false },
        showRetry: { type: Boolean, default: false }
    },
    emits: ['retry'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; }
    },
    methods: {
        toggleThinking(block) { block.collapsed = !block.collapsed; },
        toggleTool(block) { block.collapsed = !block.collapsed; },
        toggleSystemInfo(block) { block.collapsed = !block.collapsed; },
        renderMarkdown(text) { return EAMarkdown.render(text); },
        isTextBlock(b) { return b.type === 'TEXT'; },
        isThinkingBlock(b) { return b.type === 'THINKING'; },
        isToolBlock(b) { return b.type === 'TOOL_USE'; },
        isErrorBlock(b) { return b.type === 'ERROR'; },
        isTodoBlock(b) { return b.type === 'TODO_LIST'; },
        isSystemInfoBlock(b) { return b.type === 'SYSTEM_INFO'; },
        roleLabel(role) {
            switch (role) {
                case 'USER': return this.i18n.t('role.user');
                case 'ASSISTANT': return this.i18n.t('role.assistant');
                default: return role;
            }
        },
        roleEmoji(role) { return role === 'USER' ? '👤' : '🤖'; }
    }
});
