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

function shortModelId(modelId) {
    if (!modelId) return '';
    var idx = modelId.indexOf('/');
    return idx >= 0 ? modelId.substring(idx + 1) : modelId;
}

window.EARegisterComponent('settings-page', 'SettingsPage', {
    emits: ['close'],
    data() {
        return {
            activeTab: 'general',
            sidebarCollapsed: true,
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
            showAddModelModal: false,
            addModelTab: 'builtin',
            addForm: { modelId: '', displayName: '', contextDisplay: '128K', contextWindow: 128000, provider: '', providerId: '' },
            showAddCtxDropdown: false,
            addModelProviders: [],
            addModelProvidersLoading: false,
            addModelProviderModels: [],
            addModelProviderModelsLoading: false,
            addModelProviderDropdown: false,
            addModelProviderFilter: '',
            addModelModelDropdown: false,
            addModelModelFilter: '',
            configFilter: 'CLAUDE',
            configLoaded: false,
            configSaving: '',
            claudeForm: { baseUrl: '', apiKey: '', authToken: '', model: '' },
            claudeKeyVisible: false,
            opencodeForm: { providerId: 'anthropic', apiKey: '', baseUrl: '', model: '' },
            opencodeKeyVisible: false,
            codexForm: { apiKey: '', baseUrl: '', model: '' },
            codexKeyVisible: false,
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
            collapsedProviders: {},
            modelDisplayLimit: 200,
            modelsReady: false,
            cliQueriedModelIds: {},
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
            skillInstallForm: { githubUrl: '', skillName: '', scope: 'user' },
            skillContentDialog: null,
            _skillEditor: null,
            knownRepos: [],
            remoteSkills: [],
            skillRepoDropdown: false,
            skillNameDropdown: false,
            skillsBrowsing: false,
            pluginFilter: 'CLAUDE',
            pluginList: [],
            pluginLoading: false,
            showPluginInstallForm: false,
            pluginInstalling: false,
            pluginInstallForm: { githubUrl: '', pluginName: '', scope: 'user' },
            pluginContentDialog: null,
            _pluginEditor: null,
            knownPluginRepos: [],
            remotePlugins: [],
            pluginRepoDropdown: false,
            pluginNameDropdown: false
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
            if (!this.modelsReady && this.activeTab === 'models') {
                return [{ rowType: 'default', cliType: this.modelFilter }];
            }
            var rows = [{
                rowType: 'default',
                cliType: this.modelFilter
            }];
            var models = this.filteredModels;
            var limit = this.modelDisplayLimit;
            var modelCount = 0;
            var search = (this.modelSearch || '').toLowerCase().trim();

            if (this.modelFilter === 'OPENCODE') {
                var sorted = models.slice().sort(function (a, b) {
                    var pa = (a.provider || '').toLowerCase();
                    var pb = (b.provider || '').toLowerCase();
                    if (pa < pb) return -1;
                    if (pa > pb) return 1;
                    return 0;
                });
                sorted.forEach(function (model) {
                    if (modelCount >= limit) return;
                    var realIdx = models.indexOf(model);
                    rows.push({ rowType: 'model', modelIndex: realIdx, model: model });
                    modelCount++;
                });
            } else {
                models.forEach(function (model, index) {
                    if (modelCount >= limit) return;
                    rows.push({ rowType: 'model', modelIndex: index, model: model });
                    modelCount++;
                });
            }

            if (modelCount < models.length) {
                rows.push({ rowType: 'show-more', remaining: models.length - modelCount });
            }

            return rows;
        },
        ctxOptions() {
            return EA_CTX_OPTIONS;
        },
        defaultModelInfo() {
            return this.store.defaultModelInfoMap[this.modelFilter] || { displayName: '', modelId: '', contextWindow: 128000 };
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
                else if (this.configFilter === 'OPENCODE') return this.opencodeKeyVisible;
                else return this.codexKeyVisible;
            },
            set(val) {
                if (this.configFilter === 'CLAUDE') this.claudeKeyVisible = val;
                else if (this.configFilter === 'OPENCODE') this.opencodeKeyVisible = val;
                else this.codexKeyVisible = val;
            }
        },
        filteredKnownRepos() {
            var q = (this.skillInstallForm.githubUrl || '').toLowerCase();
            if (!q) return this.knownRepos;
            return this.knownRepos.filter(function (r) {
                return (r.ownerRepo || '').toLowerCase().indexOf(q) >= 0 ||
                       (r.displayName || '').toLowerCase().indexOf(q) >= 0;
            });
        },
        filteredRemoteSkills() {
            var q = (this.skillInstallForm.skillName || '').toLowerCase();
            if (!q) return this.remoteSkills;
            return this.remoteSkills.filter(function (s) {
                return (s.name || '').toLowerCase().indexOf(q) >= 0;
            });
        },
        filteredKnownPluginRepos() {
            var q = (this.pluginInstallForm.githubUrl || '').toLowerCase();
            if (!q) return this.knownPluginRepos;
            return this.knownPluginRepos.filter(function (r) {
                return (r.ownerRepo || '').toLowerCase().indexOf(q) >= 0 ||
                       (r.displayName || '').toLowerCase().indexOf(q) >= 0;
            });
        },
        filteredRemotePlugins() {
            var q = (this.pluginInstallForm.pluginName || '').toLowerCase();
            if (!q) return this.remotePlugins;
            return this.remotePlugins.filter(function (p) {
                return (p.name || '').toLowerCase().indexOf(q) >= 0;
            });
        }
    },
    watch: {
        'store.retryTimeoutMs'(ms) {
            this.timeoutSeconds = Math.round(ms / 1000);
        },
        modelFilter() {
            this.editingIndex = -1;
            this.showAddModel = false;
            this.showAddModelModal = false;
            this.modelDisplayLimit = 200;
            if (this.modelFilter === 'OPENCODE' && this.activeTab === 'models') {
                this._queryOpenCodeCLIMModels();
            }
        }
    },
    mounted() {
        this.timeoutSeconds = Math.round(this.store.retryTimeoutMs / 1000);
        this.models = (this.store.modelsList || []).slice();
        this._onModelsLoaded = function (e) {
            this.models = Array.isArray(e.detail) ? e.detail.slice() : [];
            this.modelDisplayLimit = 200;
            this.modelsReady = true;
            if (this.isSyncing) {
                this.isSyncing = false;
                this.cliModelsLoading = false;
            }
        }.bind(this);
        this._onCliModelsLoaded = function (e) {
            this.cliModelsLoading = false;
            this.modelDisplayLimit = 200;
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
        this._onOpenCodeModelsLoaded = function (e) {
            this.cliModelsLoading = false;
            this.modelDisplayLimit = 200;
            var cliModels = Array.isArray(e.detail) ? e.detail : [];
            var existing = this.models.filter(function (m) { return m.cliType !== 'OPENCODE'; });
            var existingIds = {};
            existing.forEach(function (m) { existingIds[m.modelId] = true; });
            var cliModelIds = {};
            cliModels.forEach(function (m) {
                m.cliType = 'OPENCODE';
                if (!existingIds[m.modelId]) {
                    existing.push(m);
                    existingIds[m.modelId] = true;
                }
                cliModelIds[m.modelId] = true;
            });
            this.cliQueriedModelIds = cliModelIds;
            var userAdded = this.models.filter(function (m) {
                return m.cliType === 'OPENCODE' && !cliModelIds[m.modelId];
            });
            userAdded.forEach(function (m) {
                if (!existingIds[m.modelId]) {
                    existing.push(m);
                    existingIds[m.modelId] = true;
                }
            });
            this.models = existing;
            if (this.store) {
                this.store.modelsList = existing.slice();
            }
            this.modelsReady = true;
            if (this.isSyncing) {
                this.isSyncing = false;
            }
            var providers = [];
            var providerSet = {};
            cliModels.forEach(function (m) {
                if (m.provider && !providerSet[m.provider]) {
                    providerSet[m.provider] = true;
                    providers.push({ id: m.provider, displayName: m.provider });
                }
            });
            if (providers.length > 0) {
                this.addModelProviders = providers;
            }
        }.bind(this);
        this._onProviderModelsLoaded = function (e) {
            this.addModelProviderModelsLoading = false;
            this.addModelProviderModels = Array.isArray(e.detail) ? e.detail : [];
            if (this.addModelProviderModels.length > 0 && this.showAddModelModal && this.addModelTab === 'builtin') {
                this.addModelModelDropdown = true;
            } else {
                this.addModelModelDropdown = false;
            }
        }.bind(this);
        window.addEventListener('ea-opencode-models-loaded', this._onOpenCodeModelsLoaded);
        window.addEventListener('ea-provider-models-loaded', this._onProviderModelsLoaded);
        this._onAllProvidersLoaded = function (e) {
            var providers = Array.isArray(e.detail) ? e.detail : [];
            if (providers.length > 0) {
                this.addModelProviders = providers;
                this.openCodeProviders = providers;
            }
            this.addModelProvidersLoading = false;
        }.bind(this);
        window.addEventListener('ea-all-providers-loaded', this._onAllProvidersLoaded);
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
                model: claude.model || ''
            };
            this.opencodeForm = {
                providerId: opencode.providerId || 'anthropic',
                apiKey: opencode.apiKey || '',
                baseUrl: opencode.baseUrl || '',
                model: opencode.model || ''
            };
            this.codexForm = {
                apiKey: codex.apiKey || '',
                baseUrl: codex.baseUrl || '',
                model: codex.model || ''
            };
            this.openCodeProviders = detail.providers || [];
            this.cliProfiles = detail.profiles || {};
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
        this._onSkillContent = function (e) {
            var detail = e.detail || {};
            if (this.skillContentDialog) {
                var content = detail.content || '';
                this.skillContentDialog.content = content;
                this.skillContentDialog.htmlContent = content ? (window.EAMarkdown ? EAMarkdown.render(content) : content) : '';
                this._initEditor('skill', content);
            }
        }.bind(this);
        this._onKnownRepos = function (e) {
            this.knownRepos = Array.isArray(e.detail) ? e.detail : [];
            this.skillRepoDropdown = true;
        }.bind(this);
        this._onRemoteSkills = function (e) {
            this.remoteSkills = Array.isArray(e.detail) ? e.detail : [];
            this.skillsBrowsing = false;
            this.skillNameDropdown = this.remoteSkills.length > 0;
        }.bind(this);
        window.addEventListener('ea-skills', this._onSkills);
        window.addEventListener('ea-skill-installed', this._onSkillInstalled);
        window.addEventListener('ea-skill-content', this._onSkillContent);
        window.addEventListener('ea-known-repos', this._onKnownRepos);
        window.addEventListener('ea-remote-skills', this._onRemoteSkills);

        this._onSkillContentSaved = function (e) {
            var detail = e.detail || {};
            if (this.skillContentDialog) {
                this.skillContentDialog.saving = false;
            }
        }.bind(this);
        window.addEventListener('ea-skill-content-saved', this._onSkillContentSaved);

        this._onPlugins = function (e) {
            this.pluginLoading = false;
            this.pluginList = Array.isArray(e.detail) ? e.detail : [];
        }.bind(this);
        this._onPluginInstalled = function (e) {
            this.pluginInstalling = false;
            var detail = e.detail || {};
            if (detail.success) {
                this.showPluginInstallForm = false;
            }
        }.bind(this);
        this._onPluginContent = function (e) {
            var detail = e.detail || {};
            if (this.pluginContentDialog) {
                var content = detail.content || '';
                this.pluginContentDialog.content = content;
                this.pluginContentDialog.htmlContent = content ? (window.EAMarkdown ? EAMarkdown.render(content) : content) : '';
                if (this.pluginContentDialog.activeTab === 'content') {
                    this._initEditor('plugin', content);
                }
            }
        }.bind(this);
        this._onPluginCommands = function (e) {
            var detail = e.detail || {};
            if (this.pluginContentDialog) {
                this.pluginContentDialog.commands = Array.isArray(detail.commands) ? detail.commands : [];
                this.pluginContentDialog.commandsLoading = false;
            }
        }.bind(this);
        this._onKnownPluginRepos = function (e) {
            this.knownPluginRepos = Array.isArray(e.detail) ? e.detail : [];
            this.pluginRepoDropdown = true;
        }.bind(this);
        this._onRemotePlugins = function (e) {
            this.remotePlugins = Array.isArray(e.detail) ? e.detail : [];
            this.pluginNameDropdown = this.remotePlugins.length > 0;
        }.bind(this);
        window.addEventListener('ea-plugins', this._onPlugins);
        window.addEventListener('ea-plugin-installed', this._onPluginInstalled);
        window.addEventListener('ea-plugin-content', this._onPluginContent);
        window.addEventListener('ea-plugin-commands', this._onPluginCommands);
        window.addEventListener('ea-known-plugin-repos', this._onKnownPluginRepos);
        window.addEventListener('ea-remote-plugins', this._onRemotePlugins);

        this._onPluginContentSaved = function (e) {
            var detail = e.detail || {};
            if (this.pluginContentDialog) {
                this.pluginContentDialog.saving = false;
            }
        }.bind(this);
        window.addEventListener('ea-plugin-content-saved', this._onPluginContentSaved);
    },
    beforeUnmount() {
        this._destroyEditor('skill');
        this._destroyEditor('plugin');
        if (this._syncTimeout) { clearTimeout(this._syncTimeout); this._syncTimeout = null; }
        if (this._onModelsLoaded) window.removeEventListener('ea-models-loaded', this._onModelsLoaded);
        if (this._onCliModelsLoaded) window.removeEventListener('ea-cli-models-loaded', this._onCliModelsLoaded);
        if (this._onOpenCodeModelsLoaded) window.removeEventListener('ea-opencode-models-loaded', this._onOpenCodeModelsLoaded);
        if (this._onProviderModelsLoaded) window.removeEventListener('ea-provider-models-loaded', this._onProviderModelsLoaded);
        if (this._onAllProvidersLoaded) window.removeEventListener('ea-all-providers-loaded', this._onAllProvidersLoaded);
        if (this._onCliConfigs) window.removeEventListener('ea-cli-configs', this._onCliConfigs);
        if (this._onCliConfigsSaved) window.removeEventListener('ea-cli-configs-saved', this._onCliConfigsSaved);
        if (this._onMcpConfigs) window.removeEventListener('ea-mcp-configs', this._onMcpConfigs);
        if (this._onMcpSaved) window.removeEventListener('ea-mcp-saved', this._onMcpSaved);
        if (this._onMcpTestConnected) window.removeEventListener('ea-mcp-test-connected', this._onMcpTestConnected);
        if (this._onMcpTools) window.removeEventListener('ea-mcp-tools', this._onMcpTools);
        if (this._onMcpToolResult) window.removeEventListener('ea-mcp-tool-result', this._onMcpToolResult);
        if (this._onSkills) window.removeEventListener('ea-skills', this._onSkills);
        if (this._onSkillInstalled) window.removeEventListener('ea-skill-installed', this._onSkillInstalled);
        if (this._onSkillContent) window.removeEventListener('ea-skill-content', this._onSkillContent);
        if (this._onKnownRepos) window.removeEventListener('ea-known-repos', this._onKnownRepos);
        if (this._onRemoteSkills) window.removeEventListener('ea-remote-skills', this._onRemoteSkills);
        if (this._onSkillContentSaved) window.removeEventListener('ea-skill-content-saved', this._onSkillContentSaved);
        if (this._onPlugins) window.removeEventListener('ea-plugins', this._onPlugins);
        if (this._onPluginInstalled) window.removeEventListener('ea-plugin-installed', this._onPluginInstalled);
        if (this._onPluginContent) window.removeEventListener('ea-plugin-content', this._onPluginContent);
        if (this._onPluginCommands) window.removeEventListener('ea-plugin-commands', this._onPluginCommands);
        if (this._onKnownPluginRepos) window.removeEventListener('ea-known-plugin-repos', this._onKnownPluginRepos);
        if (this._onRemotePlugins) window.removeEventListener('ea-remote-plugins', this._onRemotePlugins);
        if (this._onPluginContentSaved) window.removeEventListener('ea-plugin-content-saved', this._onPluginContentSaved);
    },
    methods: {
        setLocale(locale) { window.EAi18n.setLocale(locale); },
        _initEditor(type, content) {
            var self = this;
            this.$nextTick(function () {
                var refKey = type + 'EditorContainer';
                var container = self.$refs[refKey];
                if (!container || !window.EAMarkdownEditor) return;
                self._destroyEditor(type);
                var editor = EAMarkdownEditor.create(container, content);
                if (type === 'skill') self._skillEditor = editor;
                else self._pluginEditor = editor;
            });
        },
        _destroyEditor(type) {
            var refKey = type + 'EditorContainer';
            var container = this.$refs[refKey];
            if (container && window.EAMarkdownEditor) {
                EAMarkdownEditor.destroy(container);
            }
            if (type === 'skill') this._skillEditor = null;
            else this._pluginEditor = null;
        },
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
            else if (target === 'addModelModal') this.onCancelAddModel();
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
            this.modelsReady = false;
            this.modelDisplayLimit = 200;
            this.models = (this.store.modelsList || []).slice();
            this.modelsReady = true;
            EABridge.getModels();
            if (this.modelFilter === 'OPENCODE') {
                this._queryOpenCodeCLIMModels();
            }
        },
        _queryOpenCodeCLIMModels() {
            this.cliModelsLoading = true;
            EABridge.queryOpenCodeModels();
        },
        onSyncModels() {
            this.isSyncing = true;
            this.cliModelsLoading = true;
            EABridge.syncModels();
            var self = this;
            this._syncTimeout = setTimeout(function () {
                self.isSyncing = false;
                self.cliModelsLoading = false;
            }, 30000);
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

        toggleProvider(provider) {
            this.collapsedProviders[provider] = this.collapsedProviders[provider] === false;
        },
        expandAllProviders() {
            var all = {};
            this.models.forEach(function (m) {
                if (m.cliType === 'OPENCODE') {
                    all[m.provider || 'other'] = false;
                }
            });
            this.collapsedProviders = all;
        },
        collapseAllProviders() {
            var all = {};
            this.models.forEach(function (m) {
                if (m.cliType === 'OPENCODE') {
                    all[m.provider || 'other'] = true;
                }
            });
            this.collapsedProviders = all;
        },
        loadMoreModels() {
            this.modelDisplayLimit += 200;
        },

        // ==================== Add Model ====================

        onShowAddModel() {
            this.showAddModelModal = true;
            this.addModelTab = this.isAddProviderMode ? 'builtin' : 'custom';
            this.addForm = {
                modelId: '',
                displayName: '',
                contextDisplay: '128K',
                contextWindow: 128000,
                provider: '',
                providerId: '',
                npmPackage: '@ai-sdk/openai-compatible'
            };
            this.showAddCtxDropdown = false;
            if (this.isAddProviderMode) {
                this.addModelProviders = this.openCodeProviders && this.openCodeProviders.length > 0
                    ? this.openCodeProviders.slice() : [];
                if (this.addModelProviders.length === 0) {
                    this.addModelProvidersLoading = true;
                    EABridge.queryAllProviders();
                }
            } else {
                this.addModelProviders = [];
            }
            this.addModelProviderModels = [];
            this.addModelProviderDropdown = false;
            this.addModelModelDropdown = false;
            this.addModelProviderFilter = '';
            this.addModelModelFilter = '';
            this.addModelProviderModelsLoading = false;
            this.addModelProvidersLoading = false;
        },
        _extractProvidersFromModels() {
            var providerMap = {};
            this.models.forEach(function (m) {
                if (m.cliType === 'OPENCODE' && m.provider) {
                    providerMap[m.provider] = m.provider;
                }
            });
            var result = [];
            for (var id in providerMap) {
                if (providerMap.hasOwnProperty(id)) {
                    result.push({ id: id, displayName: providerMap[id] });
                }
            }
            return result;
        },
        onCancelAddModel() {
            this.showAddModelModal = false;
            this.showAddCtxDropdown = false;
            this.addModelProviderDropdown = false;
            this.addModelModelDropdown = false;
        },
        onConfirmAddModel() {
            var modelId = (this.addForm.modelId || '').trim();
            var displayName = (this.addForm.displayName || '').trim();
            var providerId = (this.addForm.providerId || '').trim();
            if (!modelId) return;
            var bareModelId = modelId;
            if (this.isAddProviderMode && providerId && modelId.indexOf('/') < 0) {
                modelId = providerId + '/' + modelId;
            }
            if (bareModelId.indexOf('/') >= 0) {
                bareModelId = bareModelId.substring(bareModelId.indexOf('/') + 1);
            }
            var ctxVal = parseContextWindow(this.addForm.contextDisplay);
            var npmPackage = (this.addForm.npmPackage || '').trim();
            this.models.push({
                modelId: modelId,
                displayName: displayName || modelId,
                cliType: this.modelFilter,
                contextWindow: ctxVal,
                provider: providerId,
                npmPackage: npmPackage
            });
            if (this.isAddProviderMode && providerId && bareModelId) {
                EABridge.saveOpenCodeModel(providerId, bareModelId, displayName || bareModelId, npmPackage);
            }
            this.showAddModelModal = false;
            this.showAddCtxDropdown = false;
            this.addModelProviderDropdown = false;
            this.addModelModelDropdown = false;
            this._persistModels();
        },
        onAddModelSelectProvider(providerId) {
            this.addForm.providerId = providerId;
            this.addForm.modelId = '';
            this.addForm.displayName = '';
            this.addModelProviderDropdown = false;
            this.addModelProviderFilter = '';
            this.addModelProviderModels = [];
            this.addModelModelDropdown = false;
            this.addModelProviderModelsLoading = true;
            EABridge.queryProviderModels(providerId);
        },
        onAddModelSelectModel(model) {
            var bareId = model.modelId || '';
            var slashIdx = bareId.indexOf('/');
            if (slashIdx >= 0) bareId = bareId.substring(slashIdx + 1);
            this.addForm.modelId = bareId;
            this.addForm.displayName = model.displayName || bareId;
            if (model.contextWindow) {
                this.addForm.contextWindow = model.contextWindow;
                this.addForm.contextDisplay = formatContextWindow(model.contextWindow);
            }
            this.addModelModelDropdown = false;
            this.addModelModelFilter = '';
        },
        filteredAddModelProviders() {
            var q = (this.addModelProviderFilter || '').toLowerCase();
            if (!q) return this.addModelProviders;
            return this.addModelProviders.filter(function (p) {
                return p.id.toLowerCase().indexOf(q) >= 0 || (p.displayName || '').toLowerCase().indexOf(q) >= 0;
            });
        },
        filteredAddModelModels() {
            var q = (this.addModelModelFilter || '').toLowerCase();
            if (!q) return this.addModelProviderModels;
            return this.addModelProviderModels.filter(function (m) {
                var bareId = (m.modelId || '').split('/').pop();
                return bareId.toLowerCase().indexOf(q) >= 0 || (m.displayName || '').toLowerCase().indexOf(q) >= 0;
            });
        },
        toggleAddModelProviderDropdown() {
            this.addModelProviderDropdown = !this.addModelProviderDropdown;
            this.addModelProviderFilter = '';
        },
        toggleAddModelModelDropdown() {
            if (this.addModelProviderModels.length === 0 && !this.addModelProviderModelsLoading && this.addForm.providerId) {
                this.addModelProviderModelsLoading = true;
                EABridge.queryProviderModels(this.addForm.providerId);
            }
            this.addModelModelDropdown = !this.addModelModelDropdown;
            this.addModelModelFilter = '';
        },
        onBlurAddModelProvider() {
            var self = this;
            setTimeout(function () { self.addModelProviderDropdown = false; }, 150);
        },
        onBlurAddModelModel() {
            var self = this;
            setTimeout(function () { self.addModelModelDropdown = false; }, 150);
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
            var model = filtered[idx];
            var realIdx = this.models.indexOf(model);
            if (realIdx >= 0) {
                this.models.splice(realIdx, 1);
            }
            if (model && model.cliType === 'OPENCODE' && model.provider) {
                var bareId = model.modelId || '';
                if (bareId.indexOf('/') >= 0) bareId = bareId.substring(bareId.indexOf('/') + 1);
                EABridge.deleteOpenCodeModel(model.provider, bareId);
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
        shortModelId: shortModelId,

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
            var self = this;
            var wrapper = { version: 2, cliGroups: {} };
            var cliTypes = ['CLAUDE', 'OPENCODE', 'CODEX'];
            for (var i = 0; i < cliTypes.length; i++) {
                var ct = cliTypes[i];
                var group = {
                    models: this.models.filter(function (m) {
                        if (m.cliType !== ct) return false;
                        if (ct === 'OPENCODE' && self.cliQueriedModelIds[m.modelId]) return false;
                        return true;
                    })
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
            EAConfirm.show({
                title: this.i18n.t('settings.profileDelete', 'Delete Profile'),
                message: this.i18n.t('settings.profileConfirmDelete'),
                confirmText: this.i18n.t('settings.profileDelete', 'Delete'),
                cancelText: this.i18n.t('settings.cancel', 'Cancel'),
                danger: true
            }).then(function (confirmed) {
                if (confirmed) EABridge.deleteCliProfile(cliType, profileId);
            });
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
            var self = this;
            EAConfirm.show({
                title: this.i18n.t('settings.mcpDelete', 'Delete MCP Server'),
                message: 'Delete MCP server: ' + item.name + '?',
                confirmText: this.i18n.t('settings.mcpDelete', 'Delete'),
                cancelText: this.i18n.t('settings.cancel', 'Cancel'),
                danger: true
            }).then(function (confirmed) {
                if (!confirmed) return;
                EABridge.send({
                    action: 'deleteMcpServer',
                    cliType: self.mcpFilter,
                    scope: item.scope,
                    serverName: item.name
                });
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
            this.skillInstallForm = { githubUrl: '', skillName: '', scope: 'user' };
            this.knownRepos = [];
            this.remoteSkills = [];
            this.skillRepoDropdown = false;
            this.skillNameDropdown = false;
        },
        onCancelInstallSkill() {
            this.showSkillInstallForm = false;
            this.skillRepoDropdown = false;
            this.skillNameDropdown = false;
        },
        onConfirmInstallSkill() {
            var form = this.skillInstallForm;
            if (!form.githubUrl.trim()) return;
            this.skillInstalling = true;
            EABridge.installSkill(this.skillFilter, form.githubUrl.trim(), form.skillName.trim(), form.scope);
        },
        onDeleteSkill(item) {
            EAConfirm.show({
                title: this.i18n.t('settings.skillDelete', 'Delete Skill'),
                message: this.i18n.t('settings.skillDeleteConfirm', { name: item.name }),
                confirmText: this.i18n.t('settings.skillDelete', 'Delete'),
                cancelText: this.i18n.t('settings.cancel', 'Cancel'),
                danger: true
            }).then(function (confirmed) {
                if (confirmed) EABridge.deleteSkill(this.skillFilter, item.name, item.skillPath);
            }.bind(this));
        },
        onViewSkillContent(item) {
            this.skillContentDialog = { name: item.name, content: null, htmlContent: null, skillPath: item.skillPath, saving: false };
            this._skillEditor = null;
            EABridge.readSkillContent(item.skillPath);
        },
        onCloseSkillContent() {
            this._destroyEditor('skill');
            this.skillContentDialog = null;
        },
        onSaveSkillContent() {
            if (!this.skillContentDialog || this.skillContentDialog.saving) return;
            var content = '';
            if (this._skillEditor && window.EAMarkdownEditor) {
                content = EAMarkdownEditor.getMarkdown(this._skillEditor);
            } else if (this.skillContentDialog.content !== null) {
                content = this.skillContentDialog.content;
            }
            this.skillContentDialog.saving = true;
            EABridge.saveSkillContent(this.skillContentDialog.skillPath, content);
        },
        onSkillRepoFocus() {
            EABridge.listKnownRepos(this.skillFilter);
        },
        onSkillRepoInput() {
            this.skillRepoDropdown = false;
            this.remoteSkills = [];
            this.skillNameDropdown = false;
            this.skillsBrowsing = false;
        },
        onSkillRepoSelected(repo) {
            this.skillInstallForm.githubUrl = repo.ownerRepo || repo.url;
            this.skillRepoDropdown = false;
            this.skillsBrowsing = true;
            this.remoteSkills = [];
            EABridge.listRemoteSkills(repo.ownerRepo || repo.url);
        },
        onSkillNameFocus() {
            if (this.remoteSkills.length > 0) {
                this.skillNameDropdown = true;
                return;
            }
            var repo = (this.skillInstallForm.githubUrl || '').trim();
            if (repo.indexOf('/') > 0 && !this.skillsBrowsing) {
                this.skillsBrowsing = true;
                this.remoteSkills = [];
                EABridge.listRemoteSkills(repo);
            }
        },
        onSkillNameBlur() {
            this.skillNameDropdown = false;
        },
        onSkillNameInput() {
            this.skillNameDropdown = false;
        },
        onRemoteSkillSelected(skill) {
            this.skillInstallForm.skillName = skill.name;
            this.skillNameDropdown = false;
        },

        // ==================== Plugins ====================

        onOpenPlugins() {
            this.activeTab = 'plugins';
            this.loadPluginList();
        },
        onPluginFilterChange(cliType) {
            this.pluginFilter = cliType;
            this.showPluginInstallForm = false;
            this.loadPluginList();
        },
        loadPluginList() {
            this.pluginLoading = true;
            this.pluginList = [];
            EABridge.getPlugins(this.pluginFilter);
        },
        onShowInstallPlugin() {
            this.showPluginInstallForm = true;
            this.pluginInstallForm = { githubUrl: '', pluginName: '', scope: 'user' };
            this.knownPluginRepos = [];
            this.remotePlugins = [];
            this.pluginRepoDropdown = false;
            this.pluginNameDropdown = false;
        },
        onCancelInstallPlugin() {
            this.showPluginInstallForm = false;
            this.pluginRepoDropdown = false;
            this.pluginNameDropdown = false;
        },
        onConfirmInstallPlugin() {
            var form = this.pluginInstallForm;
            if (!form.githubUrl.trim()) return;
            this.pluginInstalling = true;
            EABridge.installPlugin(this.pluginFilter, form.githubUrl.trim(), form.pluginName.trim(), form.scope);
        },
        onDeletePlugin(item) {
            EAConfirm.show({
                title: this.i18n.t('settings.pluginDelete', 'Delete Plugin'),
                message: this.i18n.t('settings.pluginDeleteConfirm', { name: item.name }),
                confirmText: this.i18n.t('settings.pluginDelete', 'Delete'),
                cancelText: this.i18n.t('settings.cancel', 'Cancel'),
                danger: true
            }).then(function (confirmed) {
                if (confirmed) EABridge.deletePlugin(this.pluginFilter, item.name, item.installPath);
            }.bind(this));
        },
        onClosePluginContent() {
            this._destroyEditor('plugin');
            this.pluginContentDialog = null;
        },
        onSavePluginContent() {
            if (!this.pluginContentDialog || this.pluginContentDialog.saving) return;
            var content = '';
            if (this._pluginEditor && window.EAMarkdownEditor) {
                content = EAMarkdownEditor.getMarkdown(this._pluginEditor);
            } else if (this.pluginContentDialog.content !== null) {
                content = this.pluginContentDialog.content;
            }
            this.pluginContentDialog.saving = true;
            EABridge.savePluginContent(this.pluginContentDialog.installPath, content);
        },
        onViewPluginContent(item) {
            this.pluginContentDialog = {
                name: item.name,
                content: null,
                htmlContent: null,
                activeTab: 'content',
                commands: null,
                commandsLoading: false,
                saving: false,
                installPath: item.installPath
            };
            this._pluginEditor = null;
            EABridge.readPluginContent(item.installPath);
        },
        onPluginDetailTab(tab) {
            if (!this.pluginContentDialog) return;
            var prevTab = this.pluginContentDialog.activeTab;
            this.pluginContentDialog.activeTab = tab;
            if (tab === 'content' && this.pluginContentDialog.content) {
                this._initEditor('plugin', this.pluginContentDialog.content);
            } else if (tab === 'commands') {
                this._destroyEditor('plugin');
            }
            if (tab === 'commands' && this.pluginContentDialog.commands === null && !this.pluginContentDialog.commandsLoading) {
                this.pluginContentDialog.commandsLoading = true;
                EABridge.readPluginCommands(this.pluginContentDialog.installPath);
            }
        },
        onPluginRepoFocus() {
            EABridge.listKnownPluginRepos(this.pluginFilter);
        },
        onPluginRepoInput() {
            this.pluginRepoDropdown = false;
            this.remotePlugins = [];
            this.pluginNameDropdown = false;
        },
        onPluginRepoSelected(repo) {
            this.pluginInstallForm.githubUrl = repo.ownerRepo || repo.url;
            this.pluginRepoDropdown = false;
            this.remotePlugins = [];
            EABridge.listRemotePlugins(repo.ownerRepo || repo.url);
        },
        onPluginNameFocus() {
            if (this.remotePlugins.length > 0) {
                this.pluginNameDropdown = true;
            }
        },
        onPluginNameBlur() {
            this.pluginNameDropdown = false;
        },
        onPluginNameInput() {
            this.pluginNameDropdown = false;
        },
        onRemotePluginSelected(plugin) {
            this.pluginInstallForm.pluginName = plugin.name;
            this.pluginNameDropdown = false;
        }
    }
});
