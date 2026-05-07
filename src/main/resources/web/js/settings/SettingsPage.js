/**
 * 设置全页面组件。
 * <p>
 * 左侧图标菜单 + 右侧设置内容。支持语言切换和 AI 重试策略配置。
 * 主题默认跟随 IDE 设置。
 * </p>
 *
 * @component settings-page
 */

/** 常用上下文窗口选项。 */
var EA_CTX_OPTIONS = [
    { label: '32K', value: 32000 },
    { label: '64K', value: 64000 },
    { label: '128K', value: 128000 },
    { label: '200K', value: 200000 },
    { label: '256K', value: 256000 },
    { label: '512K', value: 512000 },
    { label: '1M', value: 1000000 },
    { label: '2M', value: 2000000 }
];

/**
 * 解析上下文窗口字符串为整数。
 * <p>
 * 支持 "128000"、"128K"、"2M"、"1.5M" 格式。
 * </p>
 *
 * @param {string} value - 输入字符串
 * @returns {number} 解析后的 token 数量
 */
function parseContextWindow(value) {
    if (!value || !value.trim()) return 128000;
    var trimmed = value.trim().toUpperCase();
    try {
        if (trimmed.endsWith('M')) {
            return Math.round(parseFloat(trimmed.slice(0, -1).trim()) * 1000000);
        }
        if (trimmed.endsWith('K')) {
            return Math.round(parseFloat(trimmed.slice(0, -1).trim()) * 1000);
        }
        return parseInt(trimmed, 10) || 128000;
    } catch (e) {
        return 128000;
    }
}

/**
 * 格式化上下文窗口为人类可读字符串。
 *
 * @param {number} val - token 数量
 * @returns {string} 格式化字符串
 */
function formatContextWindow(val) {
    if (!val || val <= 0) return '128K';
    if (val >= 1000000 && val % 1000000 === 0) return (val / 1000000) + 'M';
    if (val % 1000 === 0) return (val / 1000) + 'K';
    return String(val);
}

window.EARegisterComponent('settings-page', 'SettingsPage', {
    emits: ['close'],
    data() {
        return {
            activeTab: 'general',
            timeoutSeconds: 0,
            models: [],
            modelFilter: 'CLAUDE',
            isSyncing: false,
            cliModelsLoading: false,
            editingIndex: -1,
            editForm: { modelId: '', displayName: '', cliType: '', contextWindow: 0, contextDisplay: '', provider: '' },
            showCtxDropdown: false
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; void this.store.currentLocale; return window.EAi18n; },
        filteredModels() {
            return this.models.filter(function (m) { return m.cliType === this.modelFilter; }.bind(this));
        },
        ctxOptions() {
            return EA_CTX_OPTIONS;
        }
    },
    watch: {
        'store.retryTimeoutMs'(ms) {
            this.timeoutSeconds = Math.round(ms / 1000);
        }
    },
    mounted() {
        this.timeoutSeconds = Math.round(this.store.retryTimeoutMs / 1000);
        this._onModelsLoaded = function (e) {
            this.models = Array.isArray(e.detail) ? e.detail : [];
        }.bind(this);
        this._onCliModelsLoaded = function (e) {
            this.cliModelsLoading = false;
            var cliModels = Array.isArray(e.detail) ? e.detail : [];
            var existing = this.models.slice();
            var existingIds = {};
            existing.forEach(function (m) { existingIds[m.modelId] = true; });
            cliModels.forEach(function (m) {
                if (!existingIds[m.modelId]) {
                    existing.push(m);
                    existingIds[m.modelId] = true;
                }
            });
            this.models = existing;
            this._persistModels();
            if (window.__ea_onModels) {
                window.__ea_onModels(existing);
            }
        }.bind(this);
        window.addEventListener('ea-models-loaded', this._onModelsLoaded);
        window.addEventListener('ea-cli-models-loaded', this._onCliModelsLoaded);
    },
    beforeUnmount() {
        if (this._onModelsLoaded) window.removeEventListener('ea-models-loaded', this._onModelsLoaded);
        if (this._onCliModelsLoaded) window.removeEventListener('ea-cli-models-loaded', this._onCliModelsLoaded);
    },
    methods: {
        setLocale(locale) { window.EAi18n.setLocale(locale); },
        incrementRetry() {
            if (this.store.retryMaxCount >= 5) return;
            this.store.retryMaxCount++;
            this.saveRetry();
        },
        decrementRetry() {
            if (this.store.retryMaxCount <= 0) return;
            this.store.retryMaxCount--;
            this.saveRetry();
        },
        saveRetry() {
            var timeoutMs = Math.max(0, (this.timeoutSeconds || 0)) * 1000;
            this.store.retryTimeoutMs = timeoutMs;
            EABridge.saveRetryConfig(this.store.retryMaxCount, timeoutMs);
        },
        onOpenModels() {
            this.activeTab = 'models';
            EABridge.getModels();
            this.$nextTick(function () {
                setTimeout(function () {
                    var opencodeModels = this.models.filter(function (m) { return m.cliType === 'OPENCODE'; });
                    if (opencodeModels.length === 0) {
                        this.cliModelsLoading = true;
                        EABridge.queryCliModels('OPENCODE');
                    }
                }.bind(this), 500);
            }.bind(this));
        },
        onSyncAllModels() {
            this.isSyncing = true;
            this.cliModelsLoading = true;
            EABridge.syncModels();
            setTimeout(function () {
                this.isSyncing = false;
                this.cliModelsLoading = false;
            }.bind(this), 15000);
        },
        onEditModel(idx) {
            var m = this.filteredModels[idx];
            this.editingIndex = idx;
            this.editForm = {
                modelId: m.modelId,
                displayName: m.displayName,
                cliType: m.cliType,
                contextWindow: m.contextWindow,
                contextDisplay: formatContextWindow(m.contextWindow),
                provider: m.provider || ''
            };
            this.showCtxDropdown = false;
        },
        onSaveEdit(idx) {
            var ctxVal = parseContextWindow(this.editForm.contextDisplay);
            var filtered = this.filteredModels;
            var realIdx = this.models.indexOf(filtered[idx]);
            if (realIdx >= 0) {
                this.models[realIdx] = Object.assign({}, this.models[realIdx], {
                    modelId: this.editForm.modelId,
                    displayName: this.editForm.displayName,
                    contextWindow: ctxVal
                });
            }
            this.editingIndex = -1;
            this.showCtxDropdown = false;
            this._persistModels();
        },
        onDeleteModel(idx) {
            var filtered = this.filteredModels;
            var realIdx = this.models.indexOf(filtered[idx]);
            if (realIdx >= 0) {
                this.models.splice(realIdx, 1);
            }
            this._persistModels();
        },
        onCtxInput(e) {
            this.editForm.contextDisplay = e.target.value;
        },
        onCtxSelect(val) {
            this.editForm.contextWindow = val;
            this.editForm.contextDisplay = formatContextWindow(val);
            this.showCtxDropdown = false;
        },
        onCtxBlur() {
            var self = this;
            setTimeout(function () {
                self.showCtxDropdown = false;
                var parsed = parseContextWindow(self.editForm.contextDisplay);
                self.editForm.contextWindow = parsed;
                self.editForm.contextDisplay = formatContextWindow(parsed);
            }, 150);
        },
        formatContextWindow: formatContextWindow,
        _persistModels() {
            var wrapper = { version: 1, models: this.models };
            EABridge.saveModels(JSON.stringify(wrapper));
        }
    }
});
