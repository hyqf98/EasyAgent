/**
 * 国际化（i18n）管理工具。
 * <p>
 * 提供中英文双语支持，默认中文。语言偏好保存到 localStorage。
 * 通过 {@code EAi18n.t(key)} 获取翻译文本。
 * </p>
 *
 * @namespace EAi18n
 */
window.EAi18n = {
    /** 当前语言代码。 */
    locale: 'zh',

    /** 版本号，每次语言切换递增，用于触发 Vue 响应式更新。 */
    _version: 0,

    /** 中文翻译表。 */
    zh: {
        'app.title': 'EasyAgent',
        'app.desc': 'AI 智能助手，聚合多种 CLI 工具',
        'welcome.title': 'EasyAgent',
        'welcome.desc': '选择一个 CLI 类型开始新的对话',
        'welcome.hint': '选择 CLI 工具后即可开始对话',
        'header.history': '历史',
        'header.home': '返回首页',
        'header.newChat': '新建',
        'header.theme': '主题',
        'header.settings': '设置',
        'session.title': '会话列表',
        'session.empty': '暂无会话',
        'session.loadMore': '点击加载更多...',
        'session.untitled': '未命名会话',
        'session.edit': '管理',
        'session.delete': '删除',
        'session.selectAll': '全选',
        'session.cancel': '取消',
        'session.clearSelection': '取消全部',
        'session.deleteConfirm': '确认删除选中的 {n} 个会话？',
        'session.deleted': '已删除 {n} 个会话',
        'session.deletedAndReset': '已删除 {n} 个会话，已切换到新的 {cli} 会话',
        'session.current': '当前',
        'input.placeholder': '输入消息... (Enter 发送)',
        'input.generating': '正在生成...',
        'input.hint': 'Shift+Enter 换行',
        'input.send': '发送',
        'input.stop': '停止',
        'input.fileSearchTitle': '引用项目文件',
        'input.fileSearchHint': '输入文件名或路径，Enter 选中',
        'input.fileSearchLoading': '正在筛选文件...',
        'input.fileSearchEmpty': '没有匹配的文件',
        'input.fileSearchCount': '{n} 个结果',
        'input.fileSearchNavigation': '↑↓ 切换 · Enter 选中 · Esc 关闭',
        'input.commandSearchTitle': '斜杠命令',
        'input.commandSearchHint': '输入命令名进行模糊匹配',
        'input.commandSearchEmpty': '没有匹配的命令',
        'input.commandSearchCount': '{n} 个结果',
        'input.commandSearchNavigation': '↑↓ 切换 · Enter 选中 · Esc 关闭',
        'chat.title': '对话',
        'chat.welcomeTitle': '开始对话',
        'chat.welcomeDesc': '在下方输入消息，我来帮你解答问题',
        'chat.retry': '重新发送',
        'chat.generating': '生成中...',
        'chat.failed': '生成失败',
        'chat.completed': '已完成',
        'chat.stop': '停止',
        'chat.retrying': '重试中 ({current}/{total})...',
        'chat.retryStop': '停止重试',
        'chat.loadingHistory': '正在加载会话消息...',
        'chat.selectModel': '选择模型',
        'chat.defaultModel': '默认模型',
        'settings.title': '设置',
        'settings.language': '语言',
        'settings.language.zh': '中文',
        'settings.language.en': 'English',
        'settings.theme': '主题',
        'settings.theme.light': '浅色',
        'settings.theme.dark': '深色',
        'settings.cliType': '默认 CLI',
        'settings.close': '关闭',
        'settings.general': '通用',
        'settings.themeLight': '浅色',
        'settings.themeDark': '深色',
        'settings.cliManager': 'CLI 管理',
        'settings.cliManagerTitle': 'CLI 工具管理',
        'settings.cliManagerDesc': '此功能正在开发中，敬请期待',
        'settings.retryTitle': 'AI 重试策略',
        'settings.retryCount': '重试次数',
        'settings.retryTimeout': '超时时间',
        'settings.seconds': '秒',
        'settings.retryHint': '设置 CLI 调用失败后的自动重试次数和超时时间（0 表示不限制）',
        'settings.models': '模型管理',
        'settings.modelsSync': '同步远程',
        'settings.modelsSyncing': '同步中...',
        'settings.modelId': '模型 ID',
        'settings.modelName': '显示名称',
        'settings.modelContext': '上下文窗口',
        'settings.modelEdit': '编辑',
        'settings.modelDelete': '删除',
        'settings.modelEmpty': '暂无模型配置，请点击同步远程或查询 CLI',
        'settings.modelsLoading': '正在查询 CLI 模型列表...',
        'settings.modelContextPlaceholder': '如 128K, 2M',
        'settings.defaultModel': '默认模型',
        'settings.defaultModelPlaceholder': '输入默认模型 ID',
        'settings.defaultModelNone': '未设置',
        'welcome.noCliTitle': '尚未安装 CLI 工具',
        'welcome.noCliDesc': '请先安装 Claude Code、OpenCode 或 Codex CLI',
        'welcome.goSettings': '前往设置',
        'thinking.label': '思考中',
        'thinking.title': '思考中',
        'meta.context': '上下文',
        'meta.contextTitle': '上下文信息',
        'meta.plugin': '插件',
        'meta.pluginTitle': '插件加载',
        'meta.skill': '技能',
        'meta.skillTitle': '技能加载',
        'meta.tooling': '工具',
        'meta.toolTitle': '工具状态',
        'tool.label': '工具调用',
        'tool.input': '输入',
        'tool.output': '输出',
        'tool.diff': '查看 Diff',
        'tool.revert': '回撤',
        'tool.status.calling': '运行中...',
        'tool.status.completed': '已完成',
        'tool.status.failed': '失败',
        'todo.label': '待办任务',
        'todo.title': '待办任务',
        'pending.title': '待发送',
        'pending.edit': '编辑',
        'pending.delete': '删除',
        'pending.empty': '暂无待发送消息',
        'role.user': '你',
        'role.assistant': 'AI 助手',
        'time.justNow': '刚刚',
        'time.minutesAgo': '{n} 分钟前',
        'time.hoursAgo': '{n} 小时前',
        'time.daysAgo': '{n} 天前',
        'stepInfo.tokenIn': '输入 {n}',
        'stepInfo.tokenOut': '输出 {n}',
        'stepInfo.tokenTotal': '合计 {n}',
        'tooltip.stop': '停止生成',
        'tooltip.retry': '重新发送'
    },

    /** 英文翻译表。 */
    en: {
        'app.title': 'EasyAgent',
        'app.desc': 'AI Assistant, aggregating multiple CLI tools',
        'welcome.title': 'EasyAgent',
        'welcome.desc': 'Select a CLI type to start a new conversation',
        'welcome.hint': 'Choose a CLI tool to begin chatting',
        'header.history': 'History',
        'header.home': 'Back Home',
        'header.newChat': 'New',
        'header.theme': 'Theme',
        'header.settings': 'Settings',
        'session.title': 'Sessions',
        'session.empty': 'No sessions',
        'session.loadMore': 'Click to load more...',
        'session.untitled': 'Untitled',
        'session.edit': 'Manage',
        'session.delete': 'Delete',
        'session.selectAll': 'Select All',
        'session.cancel': 'Cancel',
        'session.clearSelection': 'Clear All',
        'session.deleteConfirm': 'Delete {n} selected sessions?',
        'session.deleted': 'Deleted {n} sessions',
        'session.deletedAndReset': 'Deleted {n} sessions and switched to a new {cli} session',
        'session.current': 'Current',
        'input.placeholder': 'Type a message... (Enter to send)',
        'input.generating': 'Generating...',
        'input.hint': 'Shift+Enter for new line',
        'input.send': 'Send',
        'input.stop': 'Stop',
        'input.fileSearchTitle': 'Reference a Project File',
        'input.fileSearchHint': 'Type a file name or path, then press Enter',
        'input.fileSearchLoading': 'Filtering files...',
        'input.fileSearchEmpty': 'No matching files',
        'input.fileSearchCount': '{n} results',
        'input.fileSearchNavigation': 'Use ↑↓ to move, Enter to select, Esc to close',
        'input.commandSearchTitle': 'Slash Commands',
        'input.commandSearchHint': 'Type a command name to fuzzy match',
        'input.commandSearchEmpty': 'No matching commands',
        'input.commandSearchCount': '{n} results',
        'input.commandSearchNavigation': 'Use ↑↓ to move, Enter to select, Esc to close',
        'chat.title': 'Chat',
        'chat.welcomeTitle': 'Start a Conversation',
        'chat.welcomeDesc': 'Type a message below and I\'ll help you out',
        'chat.retry': 'Retry',
        'chat.generating': 'Generating...',
        'chat.failed': 'Failed',
        'chat.completed': 'Completed',
        'chat.stop': 'Stop',
        'chat.retrying': 'Retrying ({current}/{total})...',
        'chat.retryStop': 'Stop Retry',
        'chat.loadingHistory': 'Loading messages...',
        'chat.selectModel': 'Select Model',
        'chat.defaultModel': 'Default Model',
        'settings.title': 'Settings',
        'settings.language': 'Language',
        'settings.language.zh': 'Chinese',
        'settings.language.en': 'English',
        'settings.theme': 'Theme',
        'settings.theme.light': 'Light',
        'settings.theme.dark': 'Dark',
        'settings.cliType': 'Default CLI',
        'settings.close': 'Close',
        'settings.general': 'General',
        'settings.themeLight': 'Light',
        'settings.themeDark': 'Dark',
        'settings.cliManager': 'CLI Manager',
        'settings.cliManagerTitle': 'CLI Tool Management',
        'settings.cliManagerDesc': 'This feature is under development, stay tuned',
        'settings.retryTitle': 'AI Retry Strategy',
        'settings.retryCount': 'Retry Count',
        'settings.retryTimeout': 'Timeout',
        'settings.seconds': 'sec',
        'settings.retryHint': 'Set auto-retry count and timeout for CLI calls (0 means no limit)',
        'settings.models': 'Models',
        'settings.modelsSync': 'Sync',
        'settings.modelsSyncing': 'Syncing...',
        'settings.modelId': 'Model ID',
        'settings.modelName': 'Display Name',
        'settings.modelContext': 'Context Window',
        'settings.modelEdit': 'Edit',
        'settings.modelDelete': 'Delete',
        'settings.modelEmpty': 'No models configured. Sync from remote or query CLI.',
        'settings.modelsLoading': 'Querying CLI model list...',
        'settings.modelContextPlaceholder': 'e.g. 128K, 2M',
        'settings.defaultModel': 'Default Model',
        'settings.defaultModelPlaceholder': 'Enter default model ID',
        'settings.defaultModelNone': 'Not set',
        'welcome.noCliTitle': 'No CLI Tools Installed',
        'welcome.noCliDesc': 'Please install Claude Code, OpenCode, or Codex CLI first',
        'welcome.goSettings': 'Go to Settings',
        'thinking.label': 'Thinking',
        'thinking.title': 'Thinking',
        'meta.context': 'Context',
        'meta.contextTitle': 'Context Info',
        'meta.plugin': 'Plugin',
        'meta.pluginTitle': 'Plugin Loading',
        'meta.skill': 'Skill',
        'meta.skillTitle': 'Skill Loading',
        'meta.tooling': 'Tools',
        'meta.toolTitle': 'Tool Status',
        'tool.label': 'Tool Call',
        'tool.input': 'Input',
        'tool.output': 'Output',
        'tool.diff': 'View Diff',
        'tool.revert': 'Revert',
        'tool.status.calling': 'Running...',
        'tool.status.completed': 'Completed',
        'tool.status.failed': 'Failed',
        'todo.label': 'Tasks',
        'todo.title': 'Tasks',
        'pending.title': 'Pending',
        'pending.edit': 'Edit',
        'pending.delete': 'Delete',
        'pending.empty': 'No pending messages',
        'role.user': 'You',
        'role.assistant': 'AI Assistant',
        'time.justNow': 'just now',
        'time.minutesAgo': '{n}m ago',
        'time.hoursAgo': '{n}h ago',
        'time.daysAgo': '{n}d ago',
        'stepInfo.tokenIn': 'In {n}',
        'stepInfo.tokenOut': 'Out {n}',
        'stepInfo.tokenTotal': 'Total {n}',
        'tooltip.stop': 'Stop',
        'tooltip.retry': 'Retry'
    },

    /**
     * 初始化国际化模块，从 localStorage 读取语言偏好。
     */
    init() {
        const saved = window.EAStorage ? EAStorage.getItem('ea-locale') : null;
        this.locale = saved || 'zh';
        if (window.EAStore) { window.EAStore.currentLocale = this.locale; }
    },

    /**
     * 获取翻译文本。
     *
     * @param {string} key - 翻译键名
     * @param {Object} [params] - 模板参数，如 {n: 5}
     * @returns {string} 翻译后的文本
     */
    t(key, params) {
        const dict = this[this.locale] || this.zh;
        let text = dict[key] || this.zh[key] || key;
        if (params) {
            for (const k in params) {
                text = text.replace('{' + k + '}', params[k]);
            }
        }
        return text;
    },

    /**
     * 切换语言。
     *
     * @param {string} locale - 语言代码（'zh' 或 'en'）
     */
    setLocale(locale) {
        this.locale = locale;
        this._version++;
        if (window.EAStorage) {
            EAStorage.setItem('ea-locale', locale);
        }
        if (window.EAStore) {
            window.EAStore.i18nVersion = this._version;
            window.EAStore.currentLocale = locale;
        }
    }
};
