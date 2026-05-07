/**
 * 空状态欢迎页面组件。
 * <p>
 * 默认进入页面时显示。根据系统中安装的 CLI 工具动态显示可用选项。
 * 如果没有安装任何 CLI 工具，显示设置引导。
 * </p>
 *
 * @component empty-state
 */
window.EARegisterComponent('empty-state', 'EmptyState', {
    emits: ['select-cli', 'open-settings'],
    data() {
        return {
            availableCLIs: [],
            cliChecked: false
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        hasCLIs() { return this.availableCLIs.some(function(c) { return c.available; }); },
        cliCards() {
            var self = this;
            var all = [
                { type: 'CLAUDE', emoji: '✨', label: 'Claude' },
                { type: 'OPENCODE', emoji: '⌘', label: 'OpenCode' },
                { type: 'CODEX', emoji: '⟨/⟩', label: 'Codex' }
            ];
            return all.map(function(cli) {
                var found = self.availableCLIs.find(function(c) { return c.type === cli.type; });
                return Object.assign({}, cli, { available: found ? found.available : false });
            });
        }
    },
    mounted() {
        var self = this;
        this._onAvailableCLIs = function(e) {
            var detail = e.detail || {};
            self.availableCLIs = detail.clis || [];
            self.cliChecked = true;
        };
        window.addEventListener('ea-available-clis', this._onAvailableCLIs);
        window.EABridge.send('getAvailableCLIs');
    },
    beforeUnmount() {
        if (this._onAvailableCLIs) {
            window.removeEventListener('ea-available-clis', this._onAvailableCLIs);
        }
    },
    methods: {
        selectCLI(type) { this.$emit('select-cli', type); },
        openSettings() { this.$emit('open-settings'); }
    }
});
