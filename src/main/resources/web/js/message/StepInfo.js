/**
 * 步骤完成信息组件。
 * <p>
 * 在消息气泡底部右对齐显示步骤完成原因和令牌消耗统计。
 * </p>
 *
 * @component step-info
 */
window.EARegisterComponent('step-info', 'StepInfo', {
    props: {
        reason: { type: String, default: '' },
        tokenUsage: { type: Object, default: null }
    },
    computed: {
        tokenText() {
            if (!this.tokenUsage) return '';
            let parts = [];
            if (this.tokenUsage.input) parts.push('in:' + this.tokenUsage.input);
            if (this.tokenUsage.output) parts.push('out:' + this.tokenUsage.output);
            if (this.tokenUsage.total) parts.push('total:' + this.tokenUsage.total);
            return parts.length > 0 ? parts.join(' · ') : '';
        }
    }
});
