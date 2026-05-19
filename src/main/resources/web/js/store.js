/**
 * 全局响应式状态管理。
 * <p>
 * 使用 Vue3 的 {@code reactive()} 创建响应式数据对象，
 * 管理当前会话状态、消息列表、流式响应等核心数据。
 * 流式事件处理和消息转换方法在 {@link store-stream.js} 中扩展。
 * 面板管理方法在 {@link store-pane.js} 中扩展。
 * </p>
 *
 * @namespace EAStore
 */

/** 内容块类型常量。 */
var EA_BLOCK_TEXT = 'TEXT';
var EA_BLOCK_THINKING = 'THINKING';
var EA_BLOCK_TOOL_USE = 'TOOL_USE';
var EA_BLOCK_TODO_LIST = 'TODO_LIST';
var EA_BLOCK_ERROR = 'ERROR';
var EA_BLOCK_SYSTEM_INFO = 'SYSTEM_INFO';
var EA_BLOCK_REFERENCE = 'REFERENCE';
var EA_BLOCK_COMPACT = 'COMPACT';

/** 消息角色常量。 */
var EA_ROLE_USER = 'USER';
var EA_ROLE_ASSISTANT = 'ASSISTANT';
var EA_ROLE_SYSTEM = 'SYSTEM';
var EA_ROLE_DEVELOPER = 'DEVELOPER';
var EA_ROLE_ERROR = 'ERROR';

/** 流式事件类型常量。 */
var EA_EVENT_MESSAGE = 'MESSAGE';
var EA_EVENT_TOOL_USE = 'TOOL_USE';
var EA_EVENT_STEP_START = 'STEP_START';
var EA_EVENT_STEP_FINISH = 'STEP_FINISH';
var EA_EVENT_COMPACT = 'COMPACT';
var EA_EVENT_ERROR = 'ERROR';
var EA_EVENT_RETRY_STATUS = 'RETRY_STATUS';

/** 工具调用状态常量。 */
var EA_TOOL_CALLING = 'CALLING';
var EA_TOOL_COMPLETED = 'COMPLETED';
var EA_TOOL_FAILED = 'FAILED';

/** 消息类型常量。 */
var EA_MSG_THINKING = 'THINKING';

/**
 * 统一将类型/状态值转为大写，兼容不同来源的大小写差异。
 *
 * @param {string} value 原始值
 * @returns {string} 归一化后的值
 */
function normalizeEAType(value) {
    return (value || '').toString().toUpperCase();
}

/** 助手气泡状态。 */
var EA_STATE_GENERATING = 'generating';
var EA_STATE_RETRYING = 'retrying';
var EA_STATE_FAILED = 'failed';
var EA_STATE_COMPLETED = 'completed';

/**
 * 判断会话 ID 是否为前端临时占位 ID。
 *
 * @param {string|null} sessionId 会话 ID
 * @returns {boolean} 是否为临时会话
 */
function EAIsProvisionalSessionId(sessionId) {
    return !!sessionId && (sessionId.indexOf('new-') === 0 || sessionId.indexOf('plan-') === 0);
}

/**
 * 从用户可见文本中移除引用占位符，避免在气泡正文里重复显示。
 *
 * @param {string} text 用户输入原文
 * @param {Array} fileReferences 结构化引用
 * @returns {string} 去掉占位符后的可读文本
 */
function stripReferenceTokens(text, fileReferences) {
    var output = text || '';
    (fileReferences || []).forEach(function (reference) {
        if (reference && reference.inlineToken) {
            output = output.split(reference.inlineToken).join('');
        }
    });
    return output.replace(/\s{2,}/g, ' ').trim();
}

/**
 * 按占位符出现顺序还原用户消息中的文本和引用。
 *
 * @param {string} text 用户输入原文
 * @param {Array} fileReferences 结构化引用
 * @returns {Array} 交错后的内容块
 */
function buildUserMessageContents(text, fileReferences) {
    var prompt = text || '';
    var references = (fileReferences || []).filter(function (reference) {
        return !!reference;
    });
    if (!prompt && references.length === 0) {
        return [];
    }

    var contents = [];
    var cursor = 0;
    var remaining = references.slice();

    while (cursor < prompt.length) {
        var nextIndex = -1;
        var nextRefIndex = -1;

        for (var i = 0; i < remaining.length; i++) {
            var token = remaining[i].inlineToken;
            if (!token) {
                continue;
            }
            var index = prompt.indexOf(token, cursor);
            if (index >= 0 && (nextIndex === -1 || index < nextIndex)) {
                nextIndex = index;
                nextRefIndex = i;
            }
        }

        if (nextRefIndex < 0) {
            break;
        }
        if (nextIndex > cursor) {
            contents.push({ type: EA_BLOCK_TEXT, text: prompt.slice(cursor, nextIndex) });
        }
        contents.push({ type: EA_BLOCK_REFERENCE, reference: remaining[nextRefIndex] });
        cursor = nextIndex + remaining[nextRefIndex].inlineToken.length;
        remaining.splice(nextRefIndex, 1);
    }

    if (cursor < prompt.length) {
        contents.push({ type: EA_BLOCK_TEXT, text: prompt.slice(cursor) });
    }

    remaining.forEach(function (reference) {
        contents.push({ type: EA_BLOCK_REFERENCE, reference: reference });
    });

    return contents.filter(function (block) {
        return block.type === EA_BLOCK_REFERENCE || !!block.text;
    });
}

/** CLI 元数据映射。 */
var EA_CLI_META = {
    CLAUDE:  { color: '#8B5CF6', emoji: '\u2728', label: 'Claude' },
    OPENCODE: { color: '#10B981', emoji: '\u2318', label: 'OpenCode' },
    CODEX:   { color: '#3B82F6', emoji: '\u27E8/\u27E9', label: 'Codex' }
};
var EA_CLI_DEFAULT = { color: '#8B5CF6', emoji: '\u2728', label: 'EasyAgent' };

/** 会话 ID 按长度推断 CLI 类型的阈值。 */
var EA_SESSION_ID_SHORT_MAX = 10;
var EA_SESSION_ID_LONG_MIN = 30;

/** 系统消息摘要最大长度。 */
var EA_SYSTEM_SUMMARY_MAX = 120;

/** 系统消息关键词。 */
var EA_SYSTEM_KEYWORDS = [
    'system:',
    '<system-reminder>',
    'This session is being continued from a previous conversation',
    '<permissions instructions>',
    '<collaboration_mode>',
    '<skills_instructions>',
    '<environment_context>',
    '# AGENTS.md instructions for',
    'AGENTS.md instructions'
];

/** 按会话 ID 存储的待发送队列映射。 */
var EA_PENDING_MAP = {};

/** 按会话 ID 存储的流式状态映射（响应式）。 */
var EA_STREAMING_MAP = Vue.reactive({});

/** 流式超时保护定时器映射。 */
var EA_STREAMING_TIMEOUT_MAP = {};

/** 流式超时阈值（毫秒），超过此时间未收到任何事件则自动结束。 */
var EA_STREAMING_TIMEOUT_MS = 300000;

/** 按会话 ID 存储的消息缓存 { messages, retryStatus, isStreaming, loaded }。 */
var EA_SESSION_CACHE = Vue.reactive({});

/** 是否正在加载历史消息。 */
var EA_LOADING_MAP = Vue.reactive({});

window.EAStore = Vue.reactive({
    theme: 'light',
    cliType: 'CLAUDE',
    sessionId: null,
    sessionTitle: '',
    model: '',
    messages: [],
    streamingText: '',
    streamingTokenType: EA_BLOCK_TEXT,
    i18nVersion: 0,
    currentLocale: 'zh',
    themeMode: 'light',
    retryMaxCount: 5,
    retryTimeoutMs: 600000,
    retryStatus: null,
    messagesVersion: 0,
    lastTokenUsage: null,
    modelContextMap: {},
    modelsList: [],
    selectedModelId: '',
    reasoningLevelsMap: {},
    selectedReasoningLevel: '',
    defaultModelInfoMap: {},
    appMode: 'welcome',
    planConcurrentTasks: 1,
    pendingOpenSettings: false,

    planMode: false,
    planModeReady: false,

    activePanes: [],
    paneGrid: [],
    focusedPaneId: null,
    _paneSeq: 0,

    /** 行高度比例数组，每项为百分比数值（如 50 表示 50%）。 */
    rowSizes: [],
    /** 按行索引存储的列宽度比例数组，每项为百分比数值。 */
    colSizes: {},

    get isStreaming() {
        return !!EA_STREAMING_MAP[this.sessionId];
    },

    get isLoading() {
        return !!EA_LOADING_MAP[this.sessionId];
    },

    /**
     * 将当前会话的消息缓存到映射中。
     */
    _saveCurrentToCache() {
        var sid = this.sessionId;
        if (!sid) return;
        if (this.messages.length === 0 && !EA_STREAMING_MAP[sid]) return;
        EA_SESSION_CACHE[sid] = {
            messages: this.messages,
            retryStatus: this.retryStatus,
            isStreaming: !!EA_STREAMING_MAP[sid],
            loaded: true,
            lastTokenUsage: this.lastTokenUsage
        };
    },

    /**
     * 加载历史会话消息。
     * <p>
     * 切换会话前先缓存旧会话消息，优先从缓存恢复目标会话（保留流式进度）。
     * 缓存命中时不调用后端，直接使用前端缓存数据。
     * </p>
     *
     * @param {{sessionId: string, messages: Array}} data - 历史消息数据
     */
    loadHistory(data) {
        var targetSid = data.sessionId;
        if (this.sessionId !== targetSid) {
            this._saveCurrentToCache();
        }
        this.sessionId = targetSid;
        this.streamingText = '';
        delete EA_LOADING_MAP[targetSid];

        var cached = EA_SESSION_CACHE[data.sessionId];
        if (cached && cached.loaded) {
            this.messages = cached.messages;
            this.retryStatus = cached.retryStatus || null;
            this.lastTokenUsage = cached.lastTokenUsage || null;
            if (cached.isStreaming) {
                EA_STREAMING_MAP[data.sessionId] = true;
            }
        } else {
            var msgs = data.messages || [];
            this.messages = msgs.length > 0 ? this._groupByTurns(msgs.map(this.convertMessage, this)) : [];
            this.retryStatus = null;
            this.lastTokenUsage = this._extractLastTokenUsage(data.messages);
            EA_SESSION_CACHE[data.sessionId] = {
                messages: this.messages,
                retryStatus: null,
                isStreaming: false,
                loaded: true,
                lastTokenUsage: this.lastTokenUsage
            };
        }
        this.messagesVersion++;
    },

    /**
     * 标记指定会话为加载中状态。
     *
     * @param {string} sessionId - 会话 ID
     */
    setLoading(sessionId) {
        EA_LOADING_MAP[sessionId] = true;
    },

    /**
     * 清除指定会话的加载中状态。
     *
     * @param {string} sessionId - 会话 ID
     */
    clearLoading(sessionId) {
        delete EA_LOADING_MAP[sessionId];
    },

    /**
     * 检查指定会话是否已在前端缓存中。
     *
     * @param {string} sessionId - 会话 ID
     * @returns {boolean} 是否已缓存
     */
    isSessionCached(sessionId) {
        return !!EA_SESSION_CACHE[sessionId] && EA_SESSION_CACHE[sessionId].loaded;
    },

    /**
     * 将后端返回的历史数据缓存到前端，不切换当前会话。
     * <p>
     * 用于后台加载完成但用户已切换到其他会话的场景。
     * </p>
     *
     * @param {{sessionId: string, messages: Array}} data - 后端返回的历史消息数据
     */
    cacheBackendData(data) {
        var msgs = data.messages || [];
        var converted = msgs.length > 0 ? this._groupByTurns(msgs.map(this.convertMessage, this)) : [];
        var tokenUsage = this._extractLastTokenUsage(msgs);
        EA_SESSION_CACHE[data.sessionId] = {
            messages: converted,
            retryStatus: null,
            isStreaming: false,
            loaded: true,
            lastTokenUsage: tokenUsage
        };
        delete EA_LOADING_MAP[data.sessionId];
    },

    /**
     * 设置新会话状态，清空消息列表。
     *
     * @param {{cliType: string}} data - 新会话信息
     */
    setSession(data) {
        this._saveCurrentToCache();
        this.sessionId = null;
        this.sessionTitle = '';
        this.model = '';
        this.cliType = data.cliType || 'CLAUDE';
        this.messages = [];
        this.streamingText = '';
        this.lastTokenUsage = null;
        this.messagesVersion++;
    },

    /**
     * 设置流式响应状态。
     *
     * @param {boolean} val - {@code true} 表示正在流式响应
     * @param {string} sessionId - 目标会话 ID
     */
    setStreaming(val, sessionId) {
        var sid = sessionId || this.sessionId;
        if (!sid) return;
        if (val) {
            EA_STREAMING_MAP[sid] = true;
            this._startStreamingTimeout(sid);
            if (sid === this.sessionId) {
                this.beginAssistantTurn();
            }
        } else {
            this._clearStreamingTimeout(sid);
            delete EA_STREAMING_MAP[sid];
            if (sid === this.sessionId) {
                this.streamingText = '';
                this.retryStatus = null;
                this._finalizeAssistantTurn(this.messages, 'stop');
                this.messagesVersion++;
            } else if (EA_SESSION_CACHE[sid]) {
                EA_SESSION_CACHE[sid].isStreaming = false;
                this._finalizeAssistantTurn(EA_SESSION_CACHE[sid].messages || [], 'stop');
            }
        }
    },

    _startStreamingTimeout(sid) {
        this._clearStreamingTimeout(sid);
        EA_STREAMING_TIMEOUT_MAP[sid] = setTimeout(function () {
            if (EA_STREAMING_MAP[sid]) {
                console.warn('[EA] Streaming timeout for session:', sid, '- auto finalizing');
                delete EA_STREAMING_MAP[sid];
                delete EA_STREAMING_TIMEOUT_MAP[sid];
                if (window.EAStore) {
                    if (sid === EAStore.sessionId) {
                        EAStore.streamingText = '';
                        EAStore.retryStatus = null;
                        EAStore._finalizeAssistantTurn(EAStore.messages, 'stop');
                        EAStore.messagesVersion++;
                    } else if (EA_SESSION_CACHE[sid]) {
                        EA_SESSION_CACHE[sid].isStreaming = false;
                        EAStore._finalizeAssistantTurn(EA_SESSION_CACHE[sid].messages || [], 'stop');
                    }
                    window.dispatchEvent(new CustomEvent('ea-stream-complete', { detail: { sessionId: sid } }));
                }
            }
        }, EA_STREAMING_TIMEOUT_MS);
    },

    _clearStreamingTimeout(sid) {
        if (EA_STREAMING_TIMEOUT_MAP[sid]) {
            clearTimeout(EA_STREAMING_TIMEOUT_MAP[sid]);
            delete EA_STREAMING_TIMEOUT_MAP[sid];
        }
    },

    _refreshStreamingTimeout(sid) {
        if (EA_STREAMING_MAP[sid]) {
            this._startStreamingTimeout(sid);
        }
    },

    /**
     * 开启一个新的助手回合气泡。
     */
    beginAssistantTurn() {
        var msg = this.ensureAssistantMessage();
        msg.streamState = EA_STATE_GENERATING;
        msg.finishReason = null;
        msg.pendingFinishReason = null;
        msg.retryStatus = null;
        return msg;
    },

    /**
     * 将前端临时会话绑定为后端返回的真实会话 ID。
     * <p>
     * 发送首条消息时，前端会先使用 {@code new-*} 占位会话 ID 维持流式状态、
     * 停止按钮和待发送队列。当 CLI 返回真实会话 ID 后，需要把当前消息、
     * 缓存和待发送队列整体迁移到真实会话下，避免消息被缓存在不可见会话里。
     * </p>
     *
     * @param {string} resolvedSessionId 后端返回的真实会话 ID
     */
    bindResolvedSessionId(resolvedSessionId) {
        if (!resolvedSessionId || resolvedSessionId === this.sessionId) {
            return;
        }

        var previousSessionId = this.sessionId;
        var streaming = previousSessionId ? !!EA_STREAMING_MAP[previousSessionId] : false;
        var loading = previousSessionId ? !!EA_LOADING_MAP[previousSessionId] : false;

        EA_SESSION_CACHE[resolvedSessionId] = {
            messages: this.messages,
            retryStatus: this.retryStatus,
            isStreaming: streaming,
            loaded: true,
            lastTokenUsage: this.lastTokenUsage
        };

        if (previousSessionId && previousSessionId !== resolvedSessionId) {
            delete EA_SESSION_CACHE[previousSessionId];
            if (EA_PENDING_MAP[previousSessionId]) {
                EA_PENDING_MAP[resolvedSessionId] = EA_PENDING_MAP[previousSessionId];
                delete EA_PENDING_MAP[previousSessionId];
            }
            if (streaming) {
                EA_STREAMING_MAP[resolvedSessionId] = true;
                delete EA_STREAMING_MAP[previousSessionId];
            }
            if (loading) {
                EA_LOADING_MAP[resolvedSessionId] = true;
                delete EA_LOADING_MAP[previousSessionId];
            }
        }

        this.sessionId = resolvedSessionId;

        for (var i = 0; i < this.activePanes.length; i++) {
            if (this.activePanes[i].sessionId === previousSessionId) {
                this.activePanes[i].sessionId = resolvedSessionId;
            }
        }
    },

    /**
     * 根据会话 ID 自动推断 CLI 类型。
     *
     * @param {string} sessionId - 会话 ID
     * @returns {string} CLI 类型名称
     */
    inferCliTypeFromSessionId(sessionId) {
        if (!sessionId) return 'CLAUDE';
        if (sessionId.length <= EA_SESSION_ID_SHORT_MAX) return 'OPENCODE';
        if (sessionId.includes('-') && sessionId.length > EA_SESSION_ID_LONG_MIN) return 'CODEX';
        return 'CLAUDE';
    },

    get isEmpty() {
        return this.messages.length === 0;
    },

    get cliColor() {
        return (EA_CLI_META[this.cliType] || EA_CLI_DEFAULT).color;
    },

    get cliEmoji() {
        return (EA_CLI_META[this.cliType] || EA_CLI_DEFAULT).emoji;
    },

    get cliLabel() {
        return (EA_CLI_META[this.cliType] || EA_CLI_DEFAULT).label;
    },

    /**
     * 保存当前会话的待发送队列到映射中。
     *
     * @param {string} sessionId - 会话 ID
     * @param {Array} queue - 待发送队列数组
     */
    savePendingQueue(sessionId, queue) {
        if (!sessionId) return;
        if (queue && queue.length > 0) {
            EA_PENDING_MAP[sessionId] = queue.slice();
        } else {
            delete EA_PENDING_MAP[sessionId];
        }
    },

    /**
     * 恢复指定会话的待发送队列。
     *
     * @param {string} sessionId - 会话 ID
     * @returns {Array} 待发送队列数组
     */
    restorePendingQueue(sessionId) {
        return EA_PENDING_MAP[sessionId] ? EA_PENDING_MAP[sessionId].slice() : [];
    },

    /**
     * 获取所有会话的待发送队列映射（用于持久化）。
     *
     * @returns {Object} sessionId -> pendingItems 数组
     */
    getAllPendingQueues() {
        var result = {};
        for (var key in EA_PENDING_MAP) {
            result[key] = EA_PENDING_MAP[key];
        }
        return result;
    },

    /**
     * 从持久化数据恢复所有待发送队列。
     *
     * @param {Array|Object} data - sessionId -> pendingItems 数组，或待发送队列状态列表
     */
    restoreAllPendingQueues(data) {
        EA_PENDING_MAP = {};
        if (!data) return;
        if (Array.isArray(data)) {
            data.forEach(function (item) {
                if (!item || !item.sessionId) {
                    return;
                }
                try {
                    var queue = item.pendingQueue ? JSON.parse(item.pendingQueue) : [];
                    EA_PENDING_MAP[item.sessionId] = Array.isArray(queue) ? queue : [];
                } catch (e) {
                    EA_PENDING_MAP[item.sessionId] = [];
                }
            });
            return;
        }
        for (var key in data) {
            if (!data.hasOwnProperty(key)) {
                continue;
            }
            if (Array.isArray(data[key])) {
                EA_PENDING_MAP[key] = data[key];
                continue;
            }
            if (typeof data[key] === 'string') {
                try {
                    var parsed = JSON.parse(data[key]);
                    EA_PENDING_MAP[key] = Array.isArray(parsed) ? parsed : [];
                } catch (e2) {
                    EA_PENDING_MAP[key] = [];
                }
            }
        }
    }
});
