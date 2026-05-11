/**
 * 全局响应式状态管理。
 * <p>
 * 使用 Vue3 的 {@code reactive()} 创建响应式数据对象，
 * 管理当前会话状态、消息列表、流式响应等核心数据。
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

/** 按会话 ID 存储的消息缓存 { messages, retryStatus, isStreaming, loaded }。 */
var EA_SESSION_CACHE = {};

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
            if (sid === this.sessionId) {
                this.beginAssistantTurn();
            }
        } else {
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
    },

    /**
     * 处理来自 Java 端的流式事件。
     * <p>
     * 当前会话的事件直接应用到 messages；非当前会话的事件缓冲到
     * {@code EA_SESSION_CACHE}，切换回来时自动恢复。
     * </p>
     *
     * @param {{type: string, messageType?: string, text?: string, toolName?: string,
     *         status?: string, input?: string, output?: string, reason?: string,
     *         sessionId?: string, tokenUsage?: Object}} event - 流式事件对象
     */
    handleStreamEvent(event) {
        if (!event) return;
        var eventSid = event.sessionId || this.sessionId;
        var eventType = normalizeEAType(event.type);

        if (eventSid && eventSid !== this.sessionId
            && (EAIsProvisionalSessionId(this.sessionId) || (!this.sessionId && this.messages.length > 0))) {
            this.bindResolvedSessionId(eventSid);
        }

        if (eventType === EA_EVENT_STEP_START) {
            EA_STREAMING_MAP[eventSid] = true;
            if (eventSid === this.sessionId) {
                this.beginAssistantTurn();
                this.messagesVersion++;
            } else {
                this._bufferToCache(eventSid, event);
            }
            return;
        }
        if (eventType === EA_EVENT_STEP_FINISH) {
            if (eventSid === this.sessionId) {
                this.accumulateTokenUsage(event);
                this.retryStatus = null;
                var currentAssistant = this.getLastAssistantMessage();
                if (currentAssistant) {
                    currentAssistant.pendingFinishReason = event.reason || 'stop';
                }
                this.messagesVersion++;
            } else {
                this._bufferToCache(eventSid, event);
            }
            return;
        }
        if (eventType === EA_EVENT_ERROR) {
            delete EA_STREAMING_MAP[eventSid];
            if (eventSid === this.sessionId) {
                this.appendAssistantError(event.message);
                this.retryStatus = null;
                var failedMsg = this.getLastAssistantMessage();
                if (failedMsg) {
                    failedMsg.streamState = EA_STATE_FAILED;
                }
                this.messagesVersion++;
            } else {
                this._bufferToCache(eventSid, event);
            }
            return;
        }

        if (eventSid !== this.sessionId) {
            this._bufferToCache(eventSid, event);
            return;
        }

        EA_STREAMING_MAP[eventSid] = true;
        if (eventType === EA_EVENT_MESSAGE) {
            this.appendMessage(event);
        } else if (eventType === EA_EVENT_TOOL_USE) {
            this.appendToolCall(event);
        } else if (eventType === EA_EVENT_COMPACT) {
            this.appendSystemMessage('Context compacted: ' + event.reason);
        } else if (eventType === EA_EVENT_RETRY_STATUS) {
            this.retryStatus = event;
            var retryMsg = this.ensureAssistantMessage();
            retryMsg.streamState = EA_STATE_RETRYING;
            retryMsg.retryStatus = event;
        }
        this.messagesVersion++;
    },

    /**
     * 将非当前会话的流式事件缓冲到缓存。
     *
     * @param {string} sid - 会话 ID
     * @param {Object} event - 流式事件
     */
    _bufferToCache(sid, event) {
        if (!EA_SESSION_CACHE[sid]) {
            EA_SESSION_CACHE[sid] = { messages: [], retryStatus: null, loaded: false };
        }
        EA_STREAMING_MAP[sid] = true;
        var msgs = EA_SESSION_CACHE[sid].messages;
        var eventType = normalizeEAType(event.type);
        if (eventType === EA_EVENT_STEP_START) {
            this._ensureAssistantIn(msgs).streamState = EA_STATE_GENERATING;
            return;
        }
        if (eventType === EA_EVENT_STEP_FINISH) {
            this._accumulateTokenUsageIn(msgs, event);
            var finishMsg = this._getLastAssistantIn(msgs);
            if (finishMsg) {
                finishMsg.pendingFinishReason = event.reason || 'stop';
            }
            return;
        }
        if (eventType === EA_EVENT_RETRY_STATUS) {
            var retryMsg = this._ensureAssistantIn(msgs);
            retryMsg.streamState = EA_STATE_RETRYING;
            retryMsg.retryStatus = event;
            EA_SESSION_CACHE[sid].retryStatus = event;
            return;
        }
        if (eventType === EA_EVENT_ERROR) {
            var errorMsg = this._ensureAssistantIn(msgs);
            errorMsg.streamState = EA_STATE_FAILED;
            errorMsg.contents.push({ type: EA_BLOCK_ERROR, text: event.message });
            delete EA_STREAMING_MAP[sid];
            EA_SESSION_CACHE[sid].isStreaming = false;
            return;
        }
        if (eventType === EA_EVENT_MESSAGE) {
            this._appendMsgTo(msgs, event);
        } else if (eventType === EA_EVENT_TOOL_USE) {
            this._appendToolTo(msgs, event);
        }
    },

    /**
     * 在指定消息数组中追加文本内容块。
     *
     * @param {Array} msgs - 目标消息数组
     * @param {Object} event - 消息事件
     */
    _appendMsgTo(msgs, event) {
        var isThinking = normalizeEAType(event.messageType) === EA_MSG_THINKING;
        var lastMsg = this._getLastAssistantIn(msgs);

        if (isThinking && lastMsg && lastMsg.lastThinkingIndex >= 0) {
            lastMsg.contents[lastMsg.lastThinkingIndex].text += event.text;
            return;
        }
        if (!isThinking && lastMsg && lastMsg.lastTextIndex >= 0) {
            lastMsg.contents[lastMsg.lastTextIndex].text += event.text;
            return;
        }

        var blockType = isThinking ? EA_BLOCK_THINKING : EA_BLOCK_TEXT;
        var block = { type: blockType, text: event.text };
        if (isThinking) block.collapsed = true;

        lastMsg = this._ensureAssistantIn(msgs);
        lastMsg.streamState = EA_STATE_GENERATING;
        lastMsg.contents.push(block);
        lastMsg.lastTextIndex = isThinking ? -1 : lastMsg.contents.length - 1;
        lastMsg.lastThinkingIndex = isThinking ? lastMsg.contents.length - 1 : -1;
    },

    /**
     * 在指定消息数组中追加或更新工具调用。
     *
     * @param {Array} msgs - 目标消息数组
     * @param {Object} event - 工具调用事件
     */
    _appendToolTo(msgs, event) {
        var lastMsg = this._getLastAssistantIn(msgs) || this._ensureAssistantIn(msgs);
        lastMsg.streamState = EA_STATE_GENERATING;
        var status = normalizeEAType(event.status);
        var isComplete = status === EA_TOOL_COMPLETED || status === EA_TOOL_FAILED;

        if (isComplete) {
            var tool = this.findLastToolCall(lastMsg, event);
            if (tool) {
                tool.status = status;
                tool.output = event.output || tool.output;
                tool.fileEdit = event.fileEdit || tool.fileEdit || null;
                return;
            }
        }

        lastMsg.contents.push({
            type: EA_BLOCK_TOOL_USE,
            toolName: event.toolName,
            title: event.title,
            toolUseId: event.toolUseId || '',
            status: status || EA_TOOL_CALLING,
            input: event.input,
            output: event.output,
            fileEdit: event.fileEdit || null,
            collapsed: true
        });
        lastMsg.lastTextIndex = -1;
        lastMsg.lastThinkingIndex = -1;
    },

    /**
     * 在指定消息数组中获取最后一条助手消息。
     *
     * @param {Array} msgs - 目标消息数组
     * @returns {Object|null} 助手消息对象
     */
    _getLastAssistantIn(msgs) {
        var lastUserIdx = -1;
        for (var i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === EA_ROLE_USER) {
                lastUserIdx = i;
                break;
            }
        }
        for (var i = msgs.length - 1; i > lastUserIdx; i--) {
            if (msgs[i].role === EA_ROLE_ASSISTANT) return msgs[i];
        }
        return null;
    },

    /**
     * 在指定消息数组中确保末尾有一条助手消息。
     *
     * @param {Array} msgs - 目标消息数组
     * @returns {Object} 助手消息对象
     */
    _ensureAssistantIn(msgs) {
        var last = msgs[msgs.length - 1];
        if (last && last.role === EA_ROLE_ASSISTANT) return last;

        var msg = {
            role: EA_ROLE_ASSISTANT, contents: [],
            lastTextIndex: -1, lastThinkingIndex: -1,
            finishReason: null, pendingFinishReason: null, tokenUsage: null,
            streamState: EA_STATE_GENERATING, retryStatus: null
        };
        msgs.push(msg);
        return msg;
    },

    /**
     * 追加消息文本到当前助手消息（思考或普通文本）。
     *
     * @param {{messageType: string, text: string}} event - 消息事件
     */
    appendMessage(event) {
        var isThinking = normalizeEAType(event.messageType) === EA_MSG_THINKING;
        var lastMsg = this.getLastAssistantMessage();

        if (isThinking && lastMsg && lastMsg.lastThinkingIndex >= 0) {
            lastMsg.contents[lastMsg.lastThinkingIndex].text += event.text;
            return;
        }

        if (!isThinking && lastMsg && lastMsg.lastTextIndex >= 0) {
            lastMsg.contents[lastMsg.lastTextIndex].text += event.text;
            return;
        }

        var blockType = isThinking ? EA_BLOCK_THINKING : EA_BLOCK_TEXT;
        var block = { type: blockType, text: event.text };
        if (isThinking) block.collapsed = true;

        lastMsg = this.ensureAssistantMessage();
        lastMsg.streamState = EA_STATE_GENERATING;
        lastMsg.contents.push(block);
        lastMsg.lastTextIndex = isThinking ? -1 : lastMsg.contents.length - 1;
        lastMsg.lastThinkingIndex = isThinking ? lastMsg.contents.length - 1 : -1;
    },

    /**
     * 追加或更新工具调用内容块。
     *
     * @param {{toolName: string, status: string, input?: string, output?: string}} event - 工具调用事件
     */
    appendToolCall(event) {
        var lastMsg = this.getLastAssistantMessage() || this.ensureAssistantMessage();
        lastMsg.streamState = EA_STATE_GENERATING;
        var status = normalizeEAType(event.status);
        var isComplete = status === EA_TOOL_COMPLETED || status === EA_TOOL_FAILED;

        if (isComplete) {
            var tool = this.findLastToolCall(lastMsg, event);
            if (tool) {
                tool.status = status;
                tool.output = event.output || tool.output;
                tool.fileEdit = event.fileEdit || tool.fileEdit || null;
                return;
            }
        }

        lastMsg.contents.push({
            type: EA_BLOCK_TOOL_USE,
            toolName: event.toolName,
            title: event.title,
            toolUseId: event.toolUseId || '',
            status: status || EA_TOOL_CALLING,
            input: event.input,
            output: event.output,
            fileEdit: event.fileEdit || null,
            collapsed: true
        });
        lastMsg.lastTextIndex = -1;
        lastMsg.lastThinkingIndex = -1;
    },

    /**
     * 累积步骤完成事件中的令牌使用统计。
     * <p>
     * 中间步骤和最终步骤的 token 信息都累积到当前助手消息中，
     * 不设置 finishReason，避免中途显示"已完成"状态。
     * </p>
     *
     * @param {{tokenUsage?: Object}} event - 步骤完成事件
     */
    accumulateTokenUsage(event) {
        var lastMsg = this.getLastAssistantMessage();
        if (!lastMsg || !event.tokenUsage) return;

        var usage = event.tokenUsage;
        var newInput = usage.input || 0;
        var newOutput = usage.output || 0;
        var newTotal = usage.total || 0;

        if (!lastMsg.tokenUsage) {
            lastMsg.tokenUsage = { input: newInput, output: newOutput, total: newTotal };
        } else {
            lastMsg.tokenUsage.input += newInput;
            lastMsg.tokenUsage.output += newOutput;
            lastMsg.tokenUsage.total += newTotal;
        }

        this.lastTokenUsage = {
            input: lastMsg.tokenUsage.input,
            output: lastMsg.tokenUsage.output,
            total: lastMsg.tokenUsage.total
        };
    },

    /**
     * 在指定消息数组中累积 token 使用量。
     *
     * @param {Array} msgs - 消息数组
     * @param {{tokenUsage?: Object}} event - 步骤完成事件
     */
    _accumulateTokenUsageIn(msgs, event) {
        var lastMsg = this._getLastAssistantIn(msgs);
        if (!lastMsg || !event.tokenUsage) return;
        var usage = event.tokenUsage;
        var newInput = usage.input || 0;
        var newOutput = usage.output || 0;
        var newTotal = usage.total || 0;
        if (!lastMsg.tokenUsage) {
            lastMsg.tokenUsage = { input: newInput, output: newOutput, total: newTotal };
        } else {
            lastMsg.tokenUsage.input += newInput;
            lastMsg.tokenUsage.output += newOutput;
            lastMsg.tokenUsage.total += newTotal;
        }
    },

    /**
     * 追加系统消息。
     *
     * @param {string} text - 系统消息文本
     */
    appendSystemMessage(text) {
        this.messages.push({
            role: EA_ROLE_SYSTEM,
            contents: [{ type: EA_BLOCK_TEXT, text: text }]
        });
    },

    /**
     * 追加错误消息。
     *
     * @param {string} text - 错误消息文本
     */
    appendAssistantError(text) {
        var msg = this.ensureAssistantMessage();
        msg.contents.push({ type: EA_BLOCK_ERROR, text: text });
        msg.streamState = EA_STATE_FAILED;
    },

    /**
     * 添加用户消息到消息列表。
     *
     * @param {string} text - 用户输入文本
     */
    addUserMessage(text, fileReferences, slashCommand) {
        this.messages.push({
            role: EA_ROLE_USER,
            rawText: text,
            fileReferences: fileReferences || [],
            slashCommand: slashCommand || null,
            contents: buildUserMessageContents(text, fileReferences)
        });
        var lastAssistant = this.getLastAssistantMessage();
        if (lastAssistant) {
            lastAssistant.lastTextIndex = -1;
            lastAssistant.lastThinkingIndex = -1;
        }
        this.messagesVersion++;
    },

    /**
     * 确保消息列表末尾有一条助手消息，没有则创建。
     *
     * @returns {Object} 助手消息对象
     */
    ensureAssistantMessage() {
        var last = this.messages[this.messages.length - 1];
        if (last && last.role === EA_ROLE_ASSISTANT) return last;

        var msg = {
            role: EA_ROLE_ASSISTANT, contents: [],
            lastTextIndex: -1, lastThinkingIndex: -1,
            finishReason: null, pendingFinishReason: null, tokenUsage: null,
            streamState: EA_STATE_GENERATING, retryStatus: null
        };
        this.messages.push(msg);
        return msg;
    },

    /**
     * 获取最后一条助手消息。
     *
     * @returns {Object|null} 最后一条助手消息，不存在则返回 null
     */
    getLastAssistantMessage() {
        var lastUserIdx = -1;
        for (var i = this.messages.length - 1; i >= 0; i--) {
            if (this.messages[i].role === EA_ROLE_USER) {
                lastUserIdx = i;
                break;
            }
        }
        for (var i = this.messages.length - 1; i > lastUserIdx; i--) {
            if (this.messages[i].role === EA_ROLE_ASSISTANT) return this.messages[i];
        }
        return null;
    },

    /**
     * 在消息中查找最后一个匹配名称且未完成的工具调用。
     *
     * @param {Object} msg - 消息对象
     * @param {string} toolName - 工具名称
     * @returns {Object|null} 匹配的工具调用内容块
     */
    findLastToolCall(msg, event) {
        var toolUseId = event.toolUseId || '';
        var toolName = event.toolName || '';
        for (var i = msg.contents.length - 1; i >= 0; i--) {
            var c = msg.contents[i];
            if (c.type !== EA_BLOCK_TOOL_USE) continue;
            if (toolUseId && c.toolUseId === toolUseId) {
                return c;
            }
            if (toolName && c.toolName === toolName && (c.status === EA_TOOL_CALLING || !c.output)) {
                return c;
            }
        }
        return null;
    },

    /**
     * 按用户轮次合并连续的助手消息。
     * <p>
     * 当同一用户消息之后跟随多条助手消息时（Claude 的多步推理、OpenCode 的多轮工具调用），
     * 将它们合并为一个气泡，避免一个用户问题产生多个助手气泡。
     * </p>
     *
     * @param {Array} messages - 已转换的消息列表
     * @returns {Array} 合并后的消息列表
     */
    _groupByTurns(messages) {
        if (!messages || messages.length === 0) return messages;

        var result = [];
        var currentGroup = null;

        messages.forEach(function (msg) {
            if (msg.role === EA_ROLE_ASSISTANT && currentGroup && currentGroup.role === EA_ROLE_ASSISTANT) {
                currentGroup.contents = currentGroup.contents.concat(msg.contents);
                if (msg.tokenUsage) currentGroup.tokenUsage = msg.tokenUsage;
                 if (msg.finishReason) currentGroup.finishReason = msg.finishReason;
                currentGroup.streamState = msg.streamState || currentGroup.streamState;
                return;
            }

            if (msg.role === EA_ROLE_ASSISTANT) {
                currentGroup = {
                    role: EA_ROLE_ASSISTANT,
                    contents: msg.contents.slice(),
                    model: msg.model || '',
                    timestamp: msg.timestamp || null,
                    finishReason: msg.finishReason || null,
                    tokenUsage: msg.tokenUsage || null,
                    pendingFinishReason: null,
                    streamState: msg.streamState || EA_STATE_COMPLETED,
                    retryStatus: null,
                    lastTextIndex: -1,
                    lastThinkingIndex: -1
                };
                result.push(currentGroup);
                return;
            }

            currentGroup = null;
            result.push(msg);
        });

        return result;
    },

    /**
     * 将 Java 端的 SessionMessage 转换为前端消息对象。
     *
     * @param {{role: string, contents: Array, model?: string, timestamp?: number}} msg - 后端消息
     * @returns {{role: string, contents: Array, model: string, timestamp: number|null}} 前端消息对象
     */
    convertMessage(msg) {
        var isSystemLike = msg.role === EA_ROLE_SYSTEM
            || msg.role === EA_ROLE_DEVELOPER
            || (msg.role === EA_ROLE_USER && this._isSystemMessage(msg.contents));
        var contents = isSystemLike
            ? [{ type: EA_BLOCK_SYSTEM_INFO, summary: this._extractSystemSummary(msg.contents), fullText: this._extractFullText(msg.contents), collapsed: true }]
            : this._convertContentBlocks(msg.contents || []);

        return {
            role: isSystemLike ? EA_ROLE_SYSTEM : msg.role,
            contents: contents,
            model: msg.model || '',
            timestamp: msg.timestamp || null,
            rawText: this._extractFullText(msg.contents || []),
            fileReferences: [],
            finishReason: msg.role === EA_ROLE_ASSISTANT ? 'stop' : null,
            pendingFinishReason: null,
            tokenUsage: msg.tokenUsage || null,
            streamState: msg.role === EA_ROLE_ASSISTANT ? EA_STATE_COMPLETED : null,
            retryStatus: null,
            lastTextIndex: -1,
            lastThinkingIndex: -1
        };
    },

    /**
     * 转换内容块列表，处理 TOOL_RESULT 合并。
     *
     * @param {Array} blocks - 原始内容块列表
     * @returns {Array} 转换后的内容块列表
     */
    _convertContentBlocks(blocks) {
        var contents = [];
        var pendingToolUses = {};

        blocks.forEach(function (block) {
            var converted = this.convertContentBlock(block);
            if (block.type === 'TOOL_RESULT' && block.toolUseId && pendingToolUses[block.toolUseId]) {
                var toolBlock = pendingToolUses[block.toolUseId];
                toolBlock.status = block.isError ? EA_TOOL_FAILED : EA_TOOL_COMPLETED;
                toolBlock.output = block.text || block.toolOutput || '';
                return;
            }
            if (converted) {
                contents.push(converted);
                if (block.type === 'TOOL_USE' && block.toolUseId) {
                    pendingToolUses[block.toolUseId] = converted;
                }
            }
        }, this);

        return contents;
    },

    /**
     * 从后端消息列表中提取最后一条包含 tokenUsage 的统计数据。
     *
     * @param {Array} messages - 后端原始消息列表
     * @returns {Object|null} 最后一条 tokenUsage，格式 { input, output, total }
     */
    _extractLastTokenUsage(messages) {
        if (!messages) return null;
        for (var i = messages.length - 1; i >= 0; i--) {
            var usage = messages[i].tokenUsage;
            if (usage && (usage.inputTokens || usage.totalTokens || usage.input)) {
                return {
                    input: usage.inputTokens || usage.input || 0,
                    output: usage.outputTokens || usage.output || 0,
                    total: usage.totalTokens || usage.total || 0
                };
            }
        }
        return null;
    },

    /**
     * 检测消息内容是否为系统类消息。
     *
     * @param {Array} contents - 内容块列表
     * @returns {boolean} 是否为系统类消息
     */
    _isSystemMessage(contents) {
        if (!contents || contents.length === 0) return false;
        var text = contents.reduce(function (acc, b) { return acc + (b.text || ''); }, '');
        if (!text) return false;
        return EA_SYSTEM_KEYWORDS.some(function (kw) { return text.indexOf(kw) >= 0; });
    },

    /**
     * 提取系统消息的摘要文本。
     *
     * @param {Array} contents - 内容块列表
     * @returns {string} 摘要文本
     */
    _extractSystemSummary(contents) {
        var text = this._extractFullText(contents);
        var limit = EA_SYSTEM_SUMMARY_MAX;

        if (text.indexOf('<permissions instructions>') >= 0) return 'Permissions instructions';
        if (text.indexOf('<collaboration_mode>') >= 0) return 'Collaboration mode';
        if (text.indexOf('<skills_instructions>') >= 0) return 'Skills instructions';
        if (text.indexOf('<environment_context>') >= 0) return 'Environment context';
        if (/^#\s*AGENTS\.md instructions/i.test(text)) return 'AGENTS.md instructions';
        if (text.startsWith('system:')) return this._extractPromptSummary(text, limit);
        if (text.indexOf('This session is being continued') >= 0) return 'Context continuation from previous session';
        if (/plugins?\s*:|mcp|servers?\s*:|skills?\s*:|tools?\s*:/i.test(text)) {
            var metaLine = text.split('\n').map(function (line) { return line.trim(); }).find(function (line) {
                return /plugins?\s*:|mcp|servers?\s*:|skills?\s*:|tools?\s*:/i.test(line);
            });
            if (metaLine) {
                return metaLine.length > limit ? metaLine.substring(0, limit) + '...' : metaLine;
            }
        }

        var match = text.match(/<system-reminder>\s*([\s\S]*?)<\/system-reminder>/);
        if (match) {
            var firstLine = match[1].trim().split('\n')[0].trim();
            return firstLine.length > limit ? firstLine.substring(0, limit) + '...' : firstLine;
        }
        return text.length > limit ? text.substring(0, limit) + '...' : text;
    },

    /**
     * 提取内容块的完整文本。
     *
     * @param {Array} contents - 内容块列表
     * @returns {string} 合并后的完整文本
     */
    _extractFullText(contents) {
        if (!contents) return '';
        return contents.reduce(function (acc, b) { return acc + (b.text || ''); }, '');
    },

    /**
     * 从 system: 开头的 prompt 文本中提取摘要。
     *
     * @param {string} text - 完整文本
     * @param {number} limit - 最大长度
     * @returns {string} 摘要文本
     */
    _extractPromptSummary(text, limit) {
        var userLine = text.lastIndexOf('\nuser:');
        if (userLine > 0) return text.substring(userLine + 6).trim().substring(0, limit);

        var lines = text.split('\n');
        for (var i = 1; i < lines.length; i++) {
            var line = lines[i].trim();
            if (line.length > 10) return line.substring(0, limit);
        }
        return text.substring(0, limit);
    },

    /**
     * 将 Java 端的 ContentBlock 转换为前端内容块对象。
     *
     * @param {{type: string, text?: string, thinking?: string, toolName?: string,
     *         toolInput?: Object, toolOutput?: string, isError?: boolean, toolUseId?: string}} block - 后端内容块
     * @returns {Object|null} 前端内容块，STEP_START/STEP_FINISH 返回 null
     */
    convertContentBlock(block) {
        var type = normalizeEAType(block.type || '');

        if (type === EA_BLOCK_THINKING || (!type && (block.thinking || block.messageType === EA_MSG_THINKING))) {
            return { type: EA_BLOCK_THINKING, text: block.thinking || block.text || '', collapsed: true };
        }
        if (type === EA_BLOCK_TOOL_USE || (!type && (block.toolName || block.toolInput || block.fileEdit))) {
            return {
                type: EA_BLOCK_TOOL_USE, toolName: block.toolName || '', title: '',
                toolUseId: block.toolUseId || '', status: EA_TOOL_COMPLETED,
                input: this._formatToolInput(block.toolInput), output: block.toolOutput || '',
                fileEdit: block.fileEdit || null,
                collapsed: true
            };
        }
        if (type === 'TOOL_RESULT' || (!type && (block.toolOutput || block.isError))) {
            return {
                type: EA_BLOCK_TOOL_USE, toolName: '', title: '',
                toolUseId: block.toolUseId || '',
                status: block.isError ? EA_TOOL_FAILED : EA_TOOL_COMPLETED,
                input: '', output: block.toolOutput || block.text || '', fileEdit: block.fileEdit || null, collapsed: true
            };
        }
        if (type === EA_BLOCK_TODO_LIST) {
            return { type: EA_BLOCK_TODO_LIST, items: block.items || [] };
        }
        if (type === 'STEP_START' || type === 'STEP_FINISH') {
            return null;
        }
        return { type: EA_BLOCK_TEXT, text: block.text || '' };
    },

    /**
     * 将助手消息标记为完成。
     *
     * @param {Array} msgs - 消息数组
     * @param {string} fallbackReason - 默认完成原因
     */
    _finalizeAssistantTurn(msgs, fallbackReason) {
        var lastMsg = msgs === this.messages ? this.getLastAssistantMessage() : this._getLastAssistantIn(msgs);
        if (!lastMsg) return;
        if (lastMsg.streamState !== EA_STATE_FAILED) {
            lastMsg.streamState = EA_STATE_COMPLETED;
        }
        lastMsg.finishReason = lastMsg.pendingFinishReason || lastMsg.finishReason || fallbackReason || 'stop';
        lastMsg.pendingFinishReason = null;
        lastMsg.retryStatus = null;
    },

    /**
     * 格式化工具输入为字符串。
     *
     * @param {*} input - 工具输入
     * @returns {string} 格式化后的字符串
     */
    _formatToolInput(input) {
        if (!input) return '';
        return typeof input === 'string' ? input : JSON.stringify(input, null, 2);
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
