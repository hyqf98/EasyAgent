/**
 * 待办任务列表组件。
 * <p>
 * 显示 AI 在交互过程中创建的待办任务列表，包含进度条和状态图标。
 * </p>
 *
 * @component todo-list-block
 */
window.EARegisterComponent('todo-list-block', 'TodoListBlock', {
    props: {
        items: { type: Array, default: () => [] },
        cliType: { type: String, default: 'CLAUDE' }
    },
    data() {
        return { collapsed: true };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        completedCount() {
            return this.items.filter(i => i.status === 'COMPLETED').length;
        },
        progressPercent() {
            if (this.items.length === 0) return 0;
            return Math.round((this.completedCount / this.items.length) * 100);
        },
        progressClass() { return this.cliType.toLowerCase(); }
    },
    methods: {
        statusIcon(status) {
            switch (status) {
                case 'PENDING': return '○';
                case 'IN_PROGRESS': return '◉';
                case 'COMPLETED': return '✓';
                case 'CANCELLED': return '✗';
                default: return '○';
            }
        },
        statusClass(status) { return status.toLowerCase().replace('_', '-'); }
    }
});
