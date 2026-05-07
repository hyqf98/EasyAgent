/**
 * 底部输入栏组件。
 * <p>
 * 方形输入框，支持 5 行高度，超出滚动。
 * Enter 发送、Shift+Enter 换行。流式响应时切换为停止按钮。
 * 支持中文 IME 输入法，避免组合输入期间误触发发送。
 * 内嵌模型选择器，显示在输入框右下角。
 * </p>
 *
 * @component input-bar
 */
window.EARegisterComponent('input-bar', 'InputBar', {
    props: {
        isStreaming: { type: Boolean, default: false },
        disabled: { type: Boolean, default: false }
    },
    emits: ['send', 'stop'],
    data() {
        return { text: '', isComposing: false, showModelDropdown: false };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        placeholder() {
            return this.i18n.t('input.placeholder');
        },
        currentModels() {
            var list = this.store.modelsList || [];
            var cliType = this.store.cliType;
            return list.filter(function (m) { return m.cliType === cliType; });
        },
        currentModelLabel() {
            if (!this.store.selectedModelId) return this.i18n.t('chat.defaultModel');
            var models = this.currentModels;
            var selected = this.store.selectedModelId;
            for (var i = 0; i < models.length; i++) {
                if (models[i].modelId === selected) return models[i].displayName || models[i].modelId;
            }
            return selected;
        }
    },
    mounted() {
        this._onClickOutside = function (e) {
            if (this.showModelDropdown && !e.target.closest('.model-dropdown-wrapper')) {
                this.showModelDropdown = false;
            }
        }.bind(this);
        document.addEventListener('click', this._onClickOutside);
    },
    beforeUnmount() {
        if (this._onClickOutside) {
            document.removeEventListener('click', this._onClickOutside);
        }
    },
    methods: {
        selectModel(modelId) {
            this.store.selectedModelId = modelId;
            this.showModelDropdown = false;
        },
        handleKeydown(e) {
            if (this.isComposing) return;
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.send();
            }
        },
        handleCompositionStart() {
            this.isComposing = true;
        },
        handleCompositionEnd() {
            this.isComposing = false;
        },
        send() {
            var trimmed = this.text.trim();
            if (!trimmed || this.disabled) return;
            this.$emit('send', trimmed);
            this.text = '';
            this.$nextTick(function () {
                var ta = this.$refs.textarea;
                if (ta) { ta.style.height = 'auto'; }
            }.bind(this));
        },
        autoResize() {
            var ta = this.$refs.textarea;
            if (!ta) return;
            ta.style.height = 'auto';
            ta.style.height = Math.min(ta.scrollHeight, 120) + 'px';
        }
    }
});
