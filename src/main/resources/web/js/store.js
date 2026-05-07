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

/** 消息角色常量。 */
var EA_ROLE_USER = 'USER';
var EA_ROLE_ASSISTANT = 'ASSISTANT';
var EA_ROLE_SYSTEM = 'SYSTEM';
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
var EA_SYSTEM_KEYWORDS = ['system:', '<system-reminder>', 'This session is being continued from a previous conversation'];

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
     */
    setStreaming(val) {
        var sid = this.sessionId;
        if (val) {
            EA_STREAMING_MAP[sid] = true;
        } else {
            delete EA_STREAMING_MAP[sid];
            this.streamingText = '';
            this.retryStatus = null;
        }
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

        if (event.type === EA_EVENT_STEP_START) {
            EA_STREAMING_MAP[eventSid] = true;
            return;
        }
        if (event.type === EA_EVENT_STEP_FINISH) {
            delete EA_STREAMING_MAP[eventSid];
            if (eventSid === this.sessionId) {
                this.appendStepFinish(event);
                this.retryStatus = null;
                this.messagesVersion++;
            }
            return;
        }
        if (event.type === EA_EVENT_ERROR) {
            delete EA_STREAMING_MAP[eventSid];
            if (eventSid === this.sessionId) {
                this.appendError(event.message);
                this.retryStatus = null;
                this.messagesVersion++;
            }
            return;
        }

        if (eventSid !== this.sessionId) {
            this._bufferToCache(eventSid, event);
            return;
        }

        EA_STREAMING_MAP[eventSid] = true;
        if (event.type === EA_EVENT_MESSAGE) {
            this.appendMessage(event);
        } else if (event.type === EA_EVENT_TOOL_USE) {
            this.appendToolCall(event);
        } else if (event.type === EA_EVENT_COMPACT) {
            this.appendSystemMessage('Context compacted: ' + event.reason);
        } else if (event.type === EA_EVENT_RETRY_STATUS) {
            this.retryStatus = event;
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
        if (event.type === EA_EVENT_MESSAGE) {
            this._appendMsgTo(msgs, event);
        } else if (event.type === EA_EVENT_TOOL_USE) {
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
        var isThinking = event.messageType === EA_MSG_THINKING;
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
        if (isThinking) block.collapsed = false;

        lastMsg = this._ensureAssistantIn(msgs);
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
        var isComplete = event.status === EA_TOOL_COMPLETED || event.status === EA_TOOL_FAILED;

        if (isComplete) {
            var tool = this.findLastToolByName(lastMsg, event.toolName);
            if (tool) {
                tool.status = event.status;
                tool.output = event.output || tool.output;
                return;
            }
        }

        lastMsg.contents.push({
            type: EA_BLOCK_TOOL_USE,
            toolName: event.toolName,
            title: event.title,
            status: event.status,
            input: event.input,
            output: event.output,
            collapsed: false
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
        for (var i = msgs.length - 1; i >= 0; i--) {
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
            finishReason: null, tokenUsage: null
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
        var isThinking = event.messageType === EA_MSG_THINKING;
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
        if (isThinking) block.collapsed = false;

        lastMsg = this.ensureAssistantMessage();
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
        var isComplete = event.status === EA_TOOL_COMPLETED || event.status === EA_TOOL_FAILED;

        if (isComplete) {
            var tool = this.findLastToolByName(lastMsg, event.toolName);
            if (tool) {
                tool.status = event.status;
                tool.output = event.output || tool.output;
                return;
            }
        }

        lastMsg.contents.push({
            type: EA_BLOCK_TOOL_USE,
            toolName: event.toolName,
            title: event.title,
            status: event.status,
            input: event.input,
            output: event.output,
            collapsed: false
        });
        lastMsg.lastTextIndex = -1;
        lastMsg.lastThinkingIndex = -1;
    },

    /**
     * 追加步骤完成信息到当前助手消息。
     *
     * @param {{reason: string, tokenUsage?: Object}} event - 步骤完成事件
     */
    appendStepFinish(event) {
        var lastMsg = this.getLastAssistantMessage();
        if (!lastMsg || !event.reason) return;
        lastMsg.finishReason = event.reason;
        lastMsg.tokenUsage = event.tokenUsage || null;
        if (event.tokenUsage) {
            this.lastTokenUsage = {
                input: event.tokenUsage.input || 0,
                output: event.tokenUsage.output || 0,
                total: event.tokenUsage.total || 0
            };
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
    appendError(text) {
        this.messages.push({
            role: EA_ROLE_ERROR,
            contents: [{ type: EA_BLOCK_ERROR, text: text }]
        });
    },

    /**
     * 添加用户消息到消息列表。
     *
     * @param {string} text - 用户输入文本
     */
    addUserMessage(text) {
        this.messages.push({
            role: EA_ROLE_USER,
            contents: [{ type: EA_BLOCK_TEXT, text: text }]
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
            finishReason: null, tokenUsage: null
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
        for (var i = this.messages.length - 1; i >= 0; i--) {
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
    findLastToolByName(msg, toolName) {
        if (!toolName) return null;
        for (var i = msg.contents.length - 1; i >= 0; i--) {
            var c = msg.contents[i];
            if (c.type === EA_BLOCK_TOOL_USE && c.toolName === toolName && (c.status === EA_TOOL_CALLING || !c.output)) {
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
        var isSystemLike = msg.role === EA_ROLE_USER && this._isSystemMessage(msg.contents);
        var contents = isSystemLike
            ? [{ type: EA_BLOCK_SYSTEM_INFO, summary: this._extractSystemSummary(msg.contents), fullText: this._extractFullText(msg.contents), collapsed: true }]
            : this._convertContentBlocks(msg.contents || []);

        return {
            role: isSystemLike ? EA_ROLE_SYSTEM : msg.role,
            contents: contents,
            model: msg.model || '',
            timestamp: msg.timestamp || null,
            finishReason: null,
            tokenUsage: null,
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

        if (text.startsWith('system:')) return this._extractPromptSummary(text, limit);
        if (text.indexOf('This session is being continued') >= 0) return 'Context continuation from previous session';

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
        var type = block.type || EA_BLOCK_TEXT;

        if (type === EA_BLOCK_THINKING) {
            return { type: EA_BLOCK_THINKING, text: block.thinking || block.text || '', collapsed: true };
        }
        if (type === 'TOOL_USE') {
            return {
                type: EA_BLOCK_TOOL_USE, toolName: block.toolName || '', title: '',
                toolUseId: block.toolUseId || '', status: EA_TOOL_COMPLETED,
                input: this._formatToolInput(block.toolInput), output: block.toolOutput || '',
                collapsed: true
            };
        }
        if (type === 'TOOL_RESULT') {
            return {
                type: EA_BLOCK_TOOL_USE, toolName: '', title: '',
                toolUseId: block.toolUseId || '',
                status: block.isError ? EA_TOOL_FAILED : EA_TOOL_COMPLETED,
                input: '', output: block.toolOutput || block.text || '', collapsed: true
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
     * @param {Object} data - sessionId -> pendingItems 数组
     */
    restoreAllPendingQueues(data) {
        EA_PENDING_MAP = {};
        if (!data) return;
        for (var key in data) {
            if (data.hasOwnProperty(key) && Array.isArray(data[key])) {
                EA_PENDING_MAP[key] = data[key];
            }
        }
    }
});
