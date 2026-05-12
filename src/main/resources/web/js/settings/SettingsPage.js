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
            modelSearch: '',
            isSyncing: false,
            cliModelsLoading: false,
            editingIndex: -1,
            editForm: { modelId: '', displayName: '', cliType: '', contextWindow: 0, contextDisplay: '', provider: '' },
            showCtxDropdown: false,
            showAddModel: false,
            addForm: { modelId: '', displayName: '', contextDisplay: '128K', contextWindow: 128000, provider: '', providerId: '' },
            showAddCtxDropdown: false,
            configFilter: 'CLAUDE',
            configLoaded: false,
            configSaving: '',
            claudeForm: { baseUrl: '', apiKey: '', authToken: '', model: '', commandPath: '' },
            claudeKeyVisible: false,
            opencodeForm: { providerId: 'anthropic', apiKey: '', baseUrl: '', model: '', commandPath: '' },
            opencodeKeyVisible: false,
            codexForm: { apiKey: '', baseUrl: '', model: '', commandPath: '' },
            codexKeyVisible: false,
            resolvedCommandPaths: {},
            openCodeProviders: [],
            configToast: null,
            cliProfiles: {},
            profileNewName: '',
            profileSaving: '',
            profileVisibleKeys: {},
            addingProfile: false,
            newProfileForm: { name: '', apiKey: '', baseUrl: '', model: '', providerId: '', authToken: '' },
            newProfileKeyVisible: false,
            providerDropdownOpen: '',
            providerFilter: '',
            editingDefault: false,
            defaultEditForm: { displayName: '', contextDisplay: '', contextWindow: 0 },
            mcpFilter: 'CLAUDE',
            mcpList: [],
            mcpLoading: false,
            showMcpForm: false,
            mcpEditing: null,
            mcpForm: { name: '', type: 'stdio', scope: 'user', command: '', url: '', argsStr: '', envStr: '' },
            mcpTestPanels: {},
            mcpTestDialogItem: null,
            mcpToolArgs: {},
            mcpToolResults: {},
            _overlayMouseDownPos: null,
            skillFilter: 'CLAUDE',
            skillList: [],
            skillLoading: false,
            showSkillInstallForm: false,
            skillInstalling: false,
            skillInstallForm: { githubUrl: '', skillPath: '', scope: 'user' },
            skillContentDialog: null
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; void this.store.currentLocale; return window.EAi18n; },
        filteredModels() {
            var filter = this.modelFilter;
            var search = (this.modelSearch || '').toLowerCase().trim();
            return this.models.filter(function (m) {
                if (m.cliType !== filter) return false;
                if (!search) return true;
                return (m.modelId || '').toLowerCase().indexOf(search) >= 0
                    || (m.displayName || '').toLowerCase().indexOf(search) >= 0
                    || (m.provider || '').toLowerCase().indexOf(search) >= 0;
            });
        },
        modelRows() {
            var rows = [{
                rowType: 'default',
                cliType: this.modelFilter
            }];
            var models = this.filteredModels;

            if (this.modelFilter === 'OPENCODE') {
                var groups = {};
                models.forEach(function (model) {
                    var provider = model.provider || 'other';
                    if (!groups[provider]) groups[provider] = [];
                    groups[provider].push(model);
                });
                var groupKeys = Object.keys(groups).sort();
                var globalIdx = 0;
                groupKeys.forEach(function (key) {
                    rows.push({ rowType: 'provider-header', provider: key });
                    groups[key].forEach(function (model) {
                        rows.push({ rowType: 'model', modelIndex: globalIdx, model: model });
                        globalIdx++;
                    });
                });
            } else {
                models.forEach(function (model, index) {
                    rows.push({ rowType: 'model', modelIndex: index, model: model });
                });
            }
            return rows;
        },
        ctxOptions() {
            return EA_CTX_OPTIONS;
        },
        defaultModelInfo() {
            return this.store.defaultModelInfoMap[this.modelFilter] || { displayName: '', contextWindow: 128000 };
        },
        isAddProviderMode() {
            return this.modelFilter === 'OPENCODE';
        },
        currentConfigPreview() {
            var forms = { CLAUDE: this.claudeForm, OPENCODE: this.opencodeForm, CODEX: this.codexForm };
            var form = forms[this.configFilter] || {};
            var key = form.apiKey || '';
            var masked = key.length > 8 ? key.substring(0, 4) + '****' + key.substring(key.length - 4) : (key ? '****' : '-');
            return {
                apiKey: masked,
                baseUrl: form.baseUrl || '-',
                model: form.model || '-'
            };
        },
        currentProfiles() {
            return this.cliProfiles[this.configFilter] || [];
        },
        defaultConfigForm: {
            get() {
                if (this.configFilter === 'CLAUDE') return this.claudeForm;
                if (this.configFilter === 'OPENCODE') return this.opencodeForm;
                return this.codexForm;
            }
        },
        defaultKeyVisible: {
            get() {
                if (this.configFilter === 'CLAUDE') return this.claudeKeyVisible;
                if (this.configFilter === 'OPENCODE') return this.opencodeKeyVisible;
                return this.codexKeyVisible;
            },
            set(val) {
                if (this.configFilter === 'CLAUDE') this.claudeKeyVisible = val;
                else if (this.configFilter === 'OPENCODE') this.opencodeKeyVisible = val;
                else this.codexKeyVisible = val;
            }
        }
    },
    watch: {
        'store.retryTimeoutMs'(ms) {
            this.timeoutSeconds = Math.round(ms / 1000);
        },
        modelFilter() {
            this.editingIndex = -1;
            this.showAddModel = false;
        }
    },
    mounted() {
        this.timeoutSeconds = Math.round(this.store.retryTimeoutMs / 1000);
        this.models = (this.store.modelsList || []).slice();
        this._onModelsLoaded = function (e) {
            this.models = Array.isArray(e.detail) ? e.detail.slice() : [];
            if (this.isSyncing) {
                this.isSyncing = false;
                this.cliModelsLoading = false;
            }
        }.bind(this);
        this._onCliModelsLoaded = function (e) {
            this.cliModelsLoading = false;
            var cliModels = Array.isArray(e.detail) ? e.detail : [];
            var existing = this.models.slice();
            var existingIds = {};
            existing.forEach(function (m) { existingIds[m.modelId] = true; });
            cliModels.forEach(function (m) {
                m.cliType = 'OPENCODE';
                if (!existingIds[m.modelId]) {
                    existing.push(m);
                    existingIds[m.modelId] = true;
                }
            });
            this.models = existing;
            if (this.store) {
                this.store.modelsList = existing.slice();
            }
            this._persistModels();
        }.bind(this);
        window.addEventListener('ea-models-loaded', this._onModelsLoaded);
        window.addEventListener('ea-cli-models-loaded', this._onCliModelsLoaded);
        this._onCliConfigs = function (e) {
            var detail = e.detail || {};
            var configs = detail.configs || {};
            var claude = configs.claude || {};
            var opencode = configs.opencode || {};
            var codex = configs.codex || {};
            this.claudeForm = {
                baseUrl: claude.baseUrl || '',
                apiKey: claude.apiKey || '',
                authToken: claude.authToken || '',
                model: claude.model || '',
                commandPath: claude.commandPath || ''
            };
            this.opencodeForm = {
                providerId: opencode.providerId || 'anthropic',
                apiKey: opencode.apiKey || '',
                baseUrl: opencode.baseUrl || '',
                model: opencode.model || '',
                commandPath: opencode.commandPath || ''
            };
            this.codexForm = {
                apiKey: codex.apiKey || '',
                baseUrl: codex.baseUrl || '',
                model: codex.model || '',
                commandPath: codex.commandPath || ''
            };
            this.openCodeProviders = detail.providers || [];
            this.cliProfiles = detail.profiles || {};
            this.resolvedCommandPaths = detail.resolvedCommandPaths || {};
            this.configLoaded = true;
        }.bind(this);
        this._onCliConfigsSaved = function (e) {
            var detail = e.detail || {};
            this.configSaving = '';
            var cliNames = { CLAUDE: 'Claude', OPENCODE: 'OpenCode', CODEX: 'Codex' };
            var cliName = cliNames[detail.cliType] || detail.cliType;
            if (detail.success) {
                this.configToast = { type: 'success', message: this.i18n.t('settings.configSaved', { cli: cliName }) };
            } else {
                this.configToast = { type: 'error', message: this.i18n.t('settings.configSaveFailed', { cli: cliName, error: detail.message || '' }) };
            }
            setTimeout(function () { this.configToast = null; }.bind(this), 3000);
        }.bind(this);
        window.addEventListener('ea-cli-configs', this._onCliConfigs);
        window.addEventListener('ea-cli-configs-saved', this._onCliConfigsSaved);

        this._onMcpConfigs = function (e) {
            this._onMcpConfigsLoaded(e.detail);
        }.bind(this);
        this._onMcpSaved = function (e) {
            this._onMcpSaved(e.detail);
        }.bind(this);
        this._onMcpTestConnected = function (e) {
            this._onMcpTestConnectedHandler(e.detail);
        }.bind(this);
        this._onMcpTools = function (e) {
            this._onMcpToolsHandler(e.detail);
        }.bind(this);
        this._onMcpToolResult = function (e) {
            this._onMcpToolResultHandler(e.detail);
        }.bind(this);
        window.addEventListener('ea-mcp-configs', this._onMcpConfigs);
        window.addEventListener('ea-mcp-saved', this._onMcpSaved);
        window.addEventListener('ea-mcp-test-connected', this._onMcpTestConnected);
        window.addEventListener('ea-mcp-tools', this._onMcpTools);
        window.addEventListener('ea-mcp-tool-result', this._onMcpToolResult);

        this._onSkills = function (e) {
            this.skillLoading = false;
            this.skillList = Array.isArray(e.detail) ? e.detail : [];
        }.bind(this);
        this._onSkillInstalled = function (e) {
            this.skillInstalling = false;
            var detail = e.detail || {};
            if (detail.success) {
                this.showSkillInstallForm = false;
            }
        }.bind(this);
        this._onSkillDeleted = function (e) {
            var detail = e.detail || {};
        }.bind(this);
        this.        _onSkillContent = function (e) {
            var detail = e.detail || {};
            if (this.skillContentDialog) {
                var content = detail.content || '';
                this.skillContentDialog.content = content;
                this.skillContentDialog.htmlContent = content ? (window.EAMarkdown ? EAMarkdown.render(content) : content) : '';
            }
        }.bind(this);
        window.addEventListener('ea-skills', this._onSkills);
        window.addEventListener('ea-skill-installed', this._onSkillInstalled);
        window.addEventListener('ea-skill-deleted', this._onSkillDeleted);
        window.addEventListener('ea-skill-content', this._onSkillContent);
    },
    beforeUnmount() {
        if (this._syncTimeout) { clearTimeout(this._syncTimeout); this._syncTimeout = null; }
        if (this._onModelsLoaded) window.removeEventListener('ea-models-loaded', this._onModelsLoaded);
        if (this._onCliModelsLoaded) window.removeEventListener('ea-cli-models-loaded', this._onCliModelsLoaded);
        if (this._onCliConfigs) window.removeEventListener('ea-cli-configs', this._onCliConfigs);
        if (this._onCliConfigsSaved) window.removeEventListener('ea-cli-configs-saved', this._onCliConfigsSaved);
        if (this._onMcpConfigs) window.removeEventListener('ea-mcp-configs', this._onMcpConfigs);
        if (this._onMcpSaved) window.removeEventListener('ea-mcp-saved', this._onMcpSaved);
        if (this._onMcpTestConnected) window.removeEventListener('ea-mcp-test-connected', this._onMcpTestConnected);
        if (this._onMcpTools) window.removeEventListener('ea-mcp-tools', this._onMcpTools);
        if (this._onMcpToolResult) window.removeEventListener('ea-mcp-tool-result', this._onMcpToolResult);
        if (this._onSkills) window.removeEventListener('ea-skills', this._onSkills);
        if (this._onSkillInstalled) window.removeEventListener('ea-skill-installed', this._onSkillInstalled);
        if (this._onSkillDeleted) window.removeEventListener('ea-skill-deleted', this._onSkillDeleted);
        if (this._onSkillContent) window.removeEventListener('ea-skill-content', this._onSkillContent);
    },
    methods: {
        setLocale(locale) { window.EAi18n.setLocale(locale); },
        onOverlayMouseDown(e) {
            this._overlayMouseDownPos = { x: e.clientX, y: e.clientY };
        },
        onOverlayMouseUp(e, target) {
            if (!this._overlayMouseDownPos) return;
            var dx = Math.abs(e.clientX - this._overlayMouseDownPos.x);
            var dy = Math.abs(e.clientY - this._overlayMouseDownPos.y);
            this._overlayMouseDownPos = null;
            if (dx > 5 || dy > 5) return;
            if (target === 'mcpForm') this.onCancelMcp();
            else if (target === 'mcpTest' && this.mcpTestDialogItem) this.onCloseMcpTest(this.mcpTestDialogItem);
            else if (target === 'skillInstallForm') this.onCancelInstallSkill();
            else if (target === 'skillContent') this.skillContentDialog = null;
        },
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
        incrementPlanConcurrent() {
            if (this.store.planConcurrentTasks >= 5) return;
            this.store.planConcurrentTasks++;
            EABridge.savePlanConfig(this.store.planConcurrentTasks);
        },
        decrementPlanConcurrent() {
            if (this.store.planConcurrentTasks <= 1) return;
            this.store.planConcurrentTasks--;
            EABridge.savePlanConfig(this.store.planConcurrentTasks);
        },
        onOpenModels() {
            this.activeTab = 'models';
            this.models = (this.store.modelsList || []).slice();
            EABridge.getModels();
        },
        onSyncAllModels() {
            this.isSyncing = true;
            this.cliModelsLoading = true;
            EABridge.syncModels();
            var self = this;
            this._syncTimeout = setTimeout(function () {
                self.isSyncing = false;
                self.cliModelsLoading = false;
            }, 30000);
        },
        onCancelModelsLoading() {
            if (this._syncTimeout) {
                clearTimeout(this._syncTimeout);
                this._syncTimeout = null;
            }
            this.isSyncing = false;
            this.cliModelsLoading = false;
        },
        requestCliModels() {
            if (this.cliModelsLoading) {
                return;
            }
            this.cliModelsLoading = true;
            EABridge.queryCliModels('OPENCODE');
        },

        // ==================== Add Model ====================

        onShowAddModel() {
            this.showAddModel = true;
            this.addForm = {
                modelId: '',
                displayName: '',
                contextDisplay: '128K',
                contextWindow: 128000,
                provider: '',
                providerId: ''
            };
            this.showAddCtxDropdown = false;
        },
        onCancelAdd() {
            this.showAddModel = false;
            this.showAddCtxDropdown = false;
        },
        onConfirmAdd() {
            var modelId = this.addForm.modelId.trim();
            var displayName = this.addForm.displayName.trim();
            if (!modelId) return;
            if (this.isAddProviderMode) {
                var pid = this.addForm.providerId || 'anthropic';
                if (pid === 'custom') {
                    pid = this.addForm.providerId;
                }
                modelId = pid + '/' + modelId;
            }
            var ctxVal = parseContextWindow(this.addForm.contextDisplay);
            var provider = this.isAddProviderMode ? this.addForm.providerId : '';
            this.models.push({
                modelId: modelId,
                displayName: displayName || modelId,
                cliType: this.modelFilter,
                contextWindow: ctxVal,
                provider: provider || ''
            });
            this.showAddModel = false;
            this.showAddCtxDropdown = false;
            this._persistModels();
        },
        onAddCtxInput(e) {
            this.addForm.contextDisplay = e.target.value;
        },
        onAddCtxSelect(val) {
            this.addForm.contextWindow = val;
            this.addForm.contextDisplay = formatContextWindow(val);
            this.showAddCtxDropdown = false;
        },
        onAddCtxBlur() {
            var self = this;
            setTimeout(function () {
                self.showAddCtxDropdown = false;
                var parsed = parseContextWindow(self.addForm.contextDisplay);
                self.addForm.contextWindow = parsed;
                self.addForm.contextDisplay = formatContextWindow(parsed);
            }, 150);
        },

        // ==================== Edit Model ====================

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

        // ==================== Default Model ====================

        onEditDefault() {
            var info = this.defaultModelInfo;
            this.editingDefault = true;
            this.defaultEditForm = {
                displayName: info.displayName || '',
                contextWindow: info.contextWindow || 128000,
                contextDisplay: formatContextWindow(info.contextWindow || 128000)
            };
        },
        onSaveDefault() {
            var ctxVal = parseContextWindow(this.defaultEditForm.contextDisplay);
            this.store.defaultModelInfoMap[this.modelFilter] = {
                displayName: this.defaultEditForm.displayName,
                contextWindow: ctxVal
            };
            this.editingDefault = false;
            this._persistModels();
        },
        onCancelDefault() {
            this.editingDefault = false;
        },
        onDefaultCtxInput(e) {
            this.defaultEditForm.contextDisplay = e.target.value;
        },
        onDefaultCtxSelect(val) {
            this.defaultEditForm.contextWindow = val;
            this.defaultEditForm.contextDisplay = formatContextWindow(val);
        },
        onDefaultCtxBlur() {
            var self = this;
            setTimeout(function () {
                var parsed = parseContextWindow(self.defaultEditForm.contextDisplay);
                self.defaultEditForm.contextWindow = parsed;
                self.defaultEditForm.contextDisplay = formatContextWindow(parsed);
            }, 150);
        },

        // ==================== Persist ====================

        _persistModels() {
            var wrapper = { version: 2, cliGroups: {} };
            var cliTypes = ['CLAUDE', 'OPENCODE', 'CODEX'];
            for (var i = 0; i < cliTypes.length; i++) {
                var ct = cliTypes[i];
                var group = {
                    models: this.models.filter(function (m) { return m.cliType === ct; })
                };
                var info = this.store.defaultModelInfoMap[ct];
                if (info && (info.displayName || info.contextWindow)) {
                    group.defaultModelInfo = {
                        displayName: info.displayName || '',
                        contextWindow: info.contextWindow || 128000
                    };
                }
                wrapper.cliGroups[ct] = group;
            }
            EABridge.saveModels(JSON.stringify(wrapper));
        },

        // ==================== Config ====================

        onOpenConfig() {
            this.activeTab = 'config';
            this.configLoaded = false;
            this.addingProfile = false;
            EABridge.getCliConfigs();
        },
        onCancelConfigLoading() {
            this.configLoaded = true;
        },
        saveConfig(cliType) {
            this.configSaving = cliType;
            var config = null;
            if (cliType === 'CLAUDE') {
                config = Object.assign({}, this.claudeForm);
            } else if (cliType === 'OPENCODE') {
                config = Object.assign({}, this.opencodeForm);
            } else if (cliType === 'CODEX') {
                config = Object.assign({}, this.codexForm);
            }
            EABridge.saveCliConfigs(cliType, config);
        },
        maskKey(key) {
            if (!key) return '-';
            if (key.length > 8) return key.substring(0, 4) + '****' + key.substring(key.length - 4);
            return '****';
        },
        isKeyVisible(profileId) {
            return !!this.profileVisibleKeys[profileId];
        },
        toggleKeyVisible(profileId) {
            this.profileVisibleKeys[profileId] = !this.profileVisibleKeys[profileId];
        },
        getProfileForm(profile) {
            if (this.configFilter === 'CLAUDE') return profile.claude || {};
            if (this.configFilter === 'OPENCODE') return profile.opencode || {};
            return profile.codex || {};
        },
        onShowAddProfile() {
            this.addingProfile = true;
            this.newProfileKeyVisible = false;
            if (this.configFilter === 'CLAUDE') {
                this.newProfileForm = { name: '', apiKey: this.claudeForm.apiKey, baseUrl: this.claudeForm.baseUrl, model: this.claudeForm.model, authToken: this.claudeForm.authToken, providerId: '' };
            } else if (this.configFilter === 'OPENCODE') {
                this.newProfileForm = { name: '', apiKey: this.opencodeForm.apiKey, baseUrl: this.opencodeForm.baseUrl, model: this.opencodeForm.model, authToken: '', providerId: this.opencodeForm.providerId };
            } else {
                this.newProfileForm = { name: '', apiKey: this.codexForm.apiKey, baseUrl: this.codexForm.baseUrl, model: this.codexForm.model, authToken: '', providerId: '' };
            }
        },
        onCancelAddProfile() {
            this.addingProfile = false;
        },
        onConfirmAddProfile() {
            var name = (this.newProfileForm.name || '').trim();
            if (!name) return;
            var cliType = this.configFilter;
            var config = null;
            if (cliType === 'CLAUDE') {
                config = { baseUrl: this.newProfileForm.baseUrl, apiKey: this.newProfileForm.apiKey, authToken: this.newProfileForm.authToken, model: this.newProfileForm.model };
            } else if (cliType === 'OPENCODE') {
                config = { providerId: this.newProfileForm.providerId, apiKey: this.newProfileForm.apiKey, baseUrl: this.newProfileForm.baseUrl, model: this.newProfileForm.model };
            } else if (cliType === 'CODEX') {
                config = { apiKey: this.newProfileForm.apiKey, baseUrl: this.newProfileForm.baseUrl, model: this.newProfileForm.model };
            }
            var profile = { id: '', name: name, cliType: cliType };
            if (cliType === 'CLAUDE') profile.claude = config;
            else if (cliType === 'OPENCODE') profile.opencode = config;
            else if (cliType === 'CODEX') profile.codex = config;
            this.profileSaving = cliType;
            EABridge.saveCliProfile(cliType, profile);
            this.addingProfile = false;
        },
        applyProfile(cliType, profileId) {
            EABridge.applyCliProfile(cliType, profileId);
        },
        deleteProfile(cliType, profileId) {
            if (!confirm(this.i18n.t('settings.profileConfirmDelete'))) return;
            EABridge.deleteCliProfile(cliType, profileId);
        },
        toggleProviderDropdown(key) {
            this.providerDropdownOpen = this.providerDropdownOpen === key ? '' : key;
            this.providerFilter = '';
        },
        filteredProviders() {
            var q = (this.providerFilter || '').toLowerCase();
            if (!q) return this.openCodeProviders;
            return this.openCodeProviders.filter(function (p) {
                return p.id.toLowerCase().indexOf(q) >= 0 || p.displayName.toLowerCase().indexOf(q) >= 0;
            });
        },
        selectProvider(key, providerId) {
            if (key === 'default') {
                this.opencodeForm.providerId = providerId;
            } else if (key === 'new') {
                this.newProfileForm.providerId = providerId;
            } else if (key === 'add') {
                this.addForm.providerId = providerId;
            }
            this.providerDropdownOpen = '';
            this.providerFilter = '';
        },
        getProviderField(key) {
            if (key === 'default') return this.opencodeForm.providerId;
            if (key === 'new') return this.newProfileForm.providerId;
            if (key === 'add') return this.addForm.providerId;
            return '';
        },
        onProviderInput(key, value) {
            if (key === 'default') this.opencodeForm.providerId = value;
            else if (key === 'new') this.newProfileForm.providerId = value;
            else if (key === 'add') this.addForm.providerId = value;
        },
        onProviderBlur() {
            setTimeout(function () { this.providerDropdownOpen = ''; }.bind(this), 150);
        },
        onOpenMcp() {
            this.activeTab = 'mcp';
            this.loadMcpList();
        },
        onMcpFilterChange(cliType) {
            this.mcpFilter = cliType;
            this.showMcpForm = false;
            this.mcpEditing = null;
            this.loadMcpList();
        },
        loadMcpList() {
            this.mcpLoading = true;
            this.mcpList = [];
            EABridge.send({ action: 'getMcpConfigs', cliType: this.mcpFilter });
        },
        onCancelMcpLoading() {
            this.mcpLoading = false;
        },
        onShowAddMcp() {
            this.showMcpForm = true;
            this.mcpEditing = null;
            this.mcpForm = { name: '', type: 'stdio', scope: 'user', command: '', url: '', argsStr: '', envStr: '' };
        },
        onEditMcp(item) {
            this.showMcpForm = true;
            this.mcpEditing = item.name;
            var argsStr = (item.args || []).join(', ');
            var envLines = [];
            if (item.env) {
                Object.keys(item.env).forEach(function (k) { envLines.push(k + '=' + item.env[k]); });
            }
            this.mcpForm = {
                name: item.name,
                type: item.type || 'stdio',
                scope: item.scope || 'user',
                command: item.command || '',
                url: item.url || '',
                argsStr: argsStr,
                envStr: envLines.join('\n')
            };
        },
        onCancelMcp() {
            this.showMcpForm = false;
            this.mcpEditing = null;
        },
        onConfirmMcp() {
            var form = this.mcpForm;
            if (!form.name.trim()) return;
            if (this.mcpEditing && this.mcpEditing !== form.name.trim()) {
                EABridge.send({
                    action: 'deleteMcpServer',
                    cliType: this.mcpFilter,
                    scope: form.scope,
                    serverName: this.mcpEditing
                });
            }
            var args = [];
            if (form.argsStr.trim()) {
                args = form.argsStr.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
            }
            var env = {};
            if (form.envStr.trim()) {
                form.envStr.split('\n').forEach(function (line) {
                    var eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        env[line.substring(0, eqIdx).trim()] = line.substring(eqIdx + 1).trim();
                    }
                });
            }
            var payload = {
                action: 'saveMcpServer',
                cliType: this.mcpFilter,
                scope: form.scope,
                name: form.name.trim(),
                type: form.type,
                command: form.type === 'stdio' ? form.command : '',
                url: form.type === 'http' ? form.url : '',
                args: args,
                env: env,
                enabled: true
            };
            EABridge.send(payload);
        },
        onDeleteMcp(item) {
            if (!confirm('Delete MCP server: ' + item.name + '?')) return;
            EABridge.send({
                action: 'deleteMcpServer',
                cliType: this.mcpFilter,
                scope: item.scope,
                serverName: item.name
            });
        },
        _onMcpConfigsLoaded(data) {
            this.mcpLoading = false;
            this.mcpList = Array.isArray(data) ? data : [];
        },
        _onMcpSaved(data) {
            if (data && data.success) {
                this.showMcpForm = false;
                this.mcpEditing = null;
            }
        },

        // ==================== MCP Test ====================

        onTestMcp(item) {
            var key = item.name + '-' + item.scope;
            this.mcpTestPanels[key] = {
                status: 'connecting',
                connectionId: null,
                serverInfo: '',
                error: '',
                tools: [],
                callingTool: null,
                transportType: '',
                env: {}
            };
            this.mcpTestDialogItem = item;
            EABridge.send({
                action: 'testMcpConnect',
                cliType: this.mcpFilter,
                scope: item.scope,
                serverName: item.name
            });
        },
        onCloseMcpTest(item) {
            var key = item.name + '-' + item.scope;
            var panel = this.mcpTestPanels[key];
            if (panel && panel.connectionId) {
                EABridge.send({ action: 'closeMcpTest', connectionId: panel.connectionId });
            }
            delete this.mcpTestPanels[key];
            if (this.mcpTestDialogItem && this.mcpTestDialogItem.name === item.name && this.mcpTestDialogItem.scope === item.scope) {
                this.mcpTestDialogItem = null;
            }
        },
        getMcpTestPanel(item) {
            if (!item) return null;
            return this.mcpTestPanels[item.name + '-' + item.scope] || null;
        },
        _onMcpTestConnectedHandler(data) {
            if (!data) return;
            for (var key in this.mcpTestPanels) {
                var panel = this.mcpTestPanels[key];
                if (panel.status === 'connecting') {
                    if (data.success) {
                        panel.status = 'connected';
                        panel.connectionId = data.connectionId;
                        panel.serverInfo = data.serverInfo || '';
                        panel.tools = data.tools || [];
                        panel.transportType = data.transportType || '';
                        panel.env = data.env || {};
                    } else {
                        panel.status = 'failed';
                        panel.error = data.serverInfo || 'Unknown error';
                    }
                    break;
                }
            }
        },
        _onMcpToolsHandler(data) {
            if (!data) return;
            var connId = data.connectionId;
            for (var key in this.mcpTestPanels) {
                if (this.mcpTestPanels[key].connectionId === connId) {
                    this.mcpTestPanels[key].tools = data.tools || [];
                    break;
                }
            }
        },
        onCallMcpTool(item, tool) {
            var key = item.name + '-' + item.scope;
            var panel = this.mcpTestPanels[key];
            if (!panel || !panel.connectionId) return;
            panel.callingTool = tool.name;
            var argsInput = this.getToolArgsInput(panel.connectionId, tool.name);
            var args = {};
            if (argsInput && argsInput.trim()) {
                try {
                    args = JSON.parse(argsInput);
                } catch (e) {
                    args = {};
                }
            }
            var resultKey = panel.connectionId + '::' + tool.name;
            delete this.mcpToolResults[resultKey];
            EABridge.send({
                action: 'callMcpTool',
                connectionId: panel.connectionId,
                toolName: tool.name,
                arguments: args
            });
        },
        _onMcpToolResultHandler(data) {
            if (!data) return;
            var connId = data.connectionId;
            var toolName = data.toolName;
            for (var key in this.mcpTestPanels) {
                var panel = this.mcpTestPanels[key];
                if (panel.connectionId === connId) {
                    panel.callingTool = null;
                    break;
                }
            }
            var resultKey = connId + '::' + toolName;
            var result = data.result || {};
            if (result.success && result.contents) {
                var textParts = [];
                result.contents.forEach(function (c) {
                    textParts.push(c.text || '');
                });
                this.mcpToolResults[resultKey] = textParts.join('\n');
            } else if (result.error) {
                this.mcpToolResults[resultKey] = 'Error: ' + result.error;
            } else {
                this.mcpToolResults[resultKey] = JSON.stringify(result, null, 2);
            }
        },
        getToolArgsInput(connectionId, toolName) {
            var key = connectionId + '::' + toolName;
            if (!this.mcpToolArgs[key]) {
                this.mcpToolArgs[key] = '';
            }
            return this.mcpToolArgs[key];
        },
        getToolResult(connectionId, toolName) {
            var key = connectionId + '::' + toolName;
            return this.mcpToolResults[key] || null;
        },

        // ==================== Skills ====================

        onOpenSkills() {
            this.activeTab = 'skills';
            this.loadSkillList();
        },
        onSkillFilterChange(cliType) {
            this.skillFilter = cliType;
            this.showSkillInstallForm = false;
            this.loadSkillList();
        },
        loadSkillList() {
            this.skillLoading = true;
            this.skillList = [];
            EABridge.getSkills(this.skillFilter);
        },
        onShowInstallSkill() {
            this.showSkillInstallForm = true;
            this.skillInstallForm = { githubUrl: '', skillPath: '', scope: 'user' };
        },
        onCancelInstallSkill() {
            this.showSkillInstallForm = false;
        },
        onConfirmInstallSkill() {
            var form = this.skillInstallForm;
            if (!form.githubUrl.trim()) return;
            this.skillInstalling = true;
            EABridge.installSkill(this.skillFilter, form.githubUrl.trim(), form.skillPath.trim(), form.scope);
        },
        onDeleteSkill(item) {
            if (!confirm(this.i18n.t('settings.skillDeleteConfirm', { name: item.name }))) return;
            EABridge.deleteSkill(this.skillFilter, item.name, item.skillPath);
        },
        onViewSkillContent(item) {
            this.skillContentDialog = { name: item.name, content: null, htmlContent: null };
            EABridge.readSkillContent(item.skillPath);
        }
    }
});
