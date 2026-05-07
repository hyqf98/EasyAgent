/**
 * 工具调用展示块组件。
 * <p>
 * 显示 AI 调用外部工具的调用详情和执行结果。
 * 左边框颜色反映调用状态（琥珀色=调用中、绿色=完成、红色=失败）。
 * </p>
 *
 * @component tool-call-block
 */
window.EARegisterComponent('tool-call-block', 'ToolCallBlock', {
    props: {
        toolName: { type: String, default: '' },
        title: { type: String, default: '' },
        status: { type: String, default: 'CALLING' },
        input: { type: String, default: '' },
        output: { type: String, default: '' },
        collapsed: { type: Boolean, default: true }
    },
    emits: ['toggle'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        statusClass() { return this.status.toLowerCase(); },
        statusLabel() {
            switch (this.status) {
                case 'CALLING': return this.i18n.t('tool.status.calling');
                case 'COMPLETED': return this.i18n.t('tool.status.completed');
                case 'FAILED': return this.i18n.t('tool.status.failed');
                default: return this.status;
            }
        },
        displayTitle() { return this.title || this.toolName || 'Tool'; }
    }
});
