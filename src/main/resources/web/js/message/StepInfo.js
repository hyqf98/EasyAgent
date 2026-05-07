/**
 * 步骤完成信息组件。
 * <p>
 * 在消息气泡底部左对齐显示步骤完成原因和令牌消耗统计。
 * </p>
 *
 * @component step-info
 */
window.EARegisterComponent('step-info', 'StepInfo', {
    props: {
        state: { type: String, default: '' },
        reason: { type: String, default: '' },
        retryStatus: { type: Object, default: null },
        tokenUsage: { type: Object, default: null },
        showStop: { type: Boolean, default: false }
    },
    emits: ['stop'],
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        stateLabel() {
            if (this.state === 'retrying' && this.retryStatus) {
                return this.i18n.t('chat.retrying', {
                    current: this.retryStatus.currentAttempt,
                    total: this.retryStatus.maxAttempts
                });
            }
            if (this.state === 'generating') return this.i18n.t('chat.generating');
            if (this.state === 'failed') return this.i18n.t('chat.failed');
            if (this.state === 'completed' || this.reason === 'stop' || this.reason === 'end_turn') {
                return this.i18n.t('chat.completed');
            }
            return this.reason || '';
        },
        tokenText() {
            if (!this.tokenUsage) return '';
            var parts = [];
            if (this.tokenUsage.input) parts.push(this.i18n.t('stepInfo.tokenIn', { n: this.tokenUsage.input }));
            if (this.tokenUsage.output) parts.push(this.i18n.t('stepInfo.tokenOut', { n: this.tokenUsage.output }));
            if (this.tokenUsage.total) parts.push(this.i18n.t('stepInfo.tokenTotal', { n: this.tokenUsage.total }));
            return parts.length > 0 ? parts.join(' · ') : '';
        }
    }
});
