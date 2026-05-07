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
        fileEdit: { type: Object, default: null },
        collapsed: { type: Boolean, default: true }
    },
    emits: ['toggle'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        statusClass() { return this.status.toLowerCase(); },
        toolKindClass() {
            var name = (this.toolName || this.title || '').toLowerCase();
            if (name.indexOf('bash') >= 0 || name.indexOf('shell') >= 0 || name.indexOf('command') >= 0) return 'terminal';
            if (name.indexOf('edit') >= 0 || name.indexOf('write') >= 0 || name.indexOf('file') >= 0) return 'file';
            if (name.indexOf('search') >= 0 || name.indexOf('grep') >= 0 || name.indexOf('find') >= 0) return 'search';
            if (name.indexOf('plugin') >= 0 || name.indexOf('mcp') >= 0) return 'plugin';
            return 'generic';
        },
        toolIcon() {
            switch (this.toolKindClass) {
                case 'terminal': return '⌘';
                case 'file': return '▣';
                case 'search': return '◌';
                case 'plugin': return '◇';
                default: return '◧';
            }
        },
        statusLabel() {
            switch (this.status) {
                case 'CALLING': return this.i18n.t('tool.status.calling');
                case 'COMPLETED': return this.i18n.t('tool.status.completed');
                case 'FAILED': return this.i18n.t('tool.status.failed');
                default: return this.status;
            }
        },
        displayTitle() { return this.title || this.toolName || 'Tool'; }
    },
    methods: {
        openDiff() {
            if (this.fileEdit && this.fileEdit.editId) {
                EABridge.openFileEditDiff(this.fileEdit.editId);
            }
        },
        revertEdit() {
            if (this.fileEdit && this.fileEdit.editId) {
                EABridge.revertFileEdit(this.fileEdit.editId);
            }
        }
    }
});
