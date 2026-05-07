/**
 * 思考内容折叠块组件。
 * <p>
 * 显示 AI 的思考推理过程，支持折叠/展开切换。
 * </p>
 *
 * @component thinking-block
 */
window.EARegisterComponent('thinking-block', 'ThinkingBlock', {
    props: {
        text: { type: String, default: '' },
        collapsed: { type: Boolean, default: true },
        duration: { type: Number, default: null }
    },
    emits: ['toggle'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; }
    }
});
