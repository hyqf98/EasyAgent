/**
 * 待发送消息队列组件。
 * <p>
 * 显示在输入框上方的待发送消息列表，支持编辑和删除。
 * 默认收起状态，展开后可逐条管理待发送内容。
 * AI 响应完成后自动发送队列中的第一条消息。
 * </p>
 *
 * @component pending-queue
 */
window.EARegisterComponent('pending-queue', 'PendingQueue', {
    props: {
        items: { type: Array, default: () => [] }
    },
    emits: ['remove', 'update', 'send-next'],
    data() {
        return {
            collapsed: true,
            editingId: null,
            editText: ''
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; }
    },
    watch: {
        items: {
            handler(newVal) {
                if (newVal.length === 0) {
                    this.collapsed = true;
                    this.editingId = null;
                }
            },
            deep: true
        }
    },
    methods: {
        /**
         * 进入编辑模式。
         *
         * @param {{id: string, text: string}} item - 待编辑的消息项
         */
        startEdit(item) {
            this.editingId = item.id;
            this.editText = item.text;
            this.$nextTick(() => {
                const areas = this.$refs.editArea;
                if (areas) {
                    const ta = Array.isArray(areas) ? areas[0] : areas;
                    if (ta) ta.focus();
                }
            });
        },

        /**
         * 保存编辑内容。
         *
         * @param {string} id - 消息项 ID
         */
        saveEdit(id) {
            const trimmed = this.editText.trim();
            if (!trimmed) return;
            this.$emit('update', { id: id, text: trimmed });
            this.editingId = null;
            this.editText = '';
        },

        /**
         * 取消编辑。
         */
        cancelEdit() {
            this.editingId = null;
            this.editText = '';
        }
    }
});
