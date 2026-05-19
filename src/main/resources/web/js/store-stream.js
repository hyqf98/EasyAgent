/**
 * 流式事件处理与消息转换方法。
 * <p>
 * 从 {@link store.js} 拆分，将流式事件分发、消息缓存缓冲、
 * 内容块转换、token 使用量统计等职责集中到本文件。
 * </p>
 *
 * @namespace EAStore
 */

Object.assign(window.EAStore, {

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

        switch (eventType) {
            case EA_EVENT_STEP_START:
                EA_STREAMING_MAP[eventSid] = true;
                this._refreshStreamingTimeout(eventSid);
                if (eventSid === this.sessionId) {
                    this.beginAssistantTurn();
                    this.messagesVersion++;
                } else {
                    this._bufferToCache(eventSid, event);
                }
                return;
            case EA_EVENT_STEP_FINISH:
                this._refreshStreamingTimeout(eventSid);
                if (eventSid === this.sessionId) {
                    this.accumulateTokenUsage(event);
                    this.retryStatus = null;
                    var currentAssistant = this.getLastAssistantMessage();
                    if (currentAssistant) {
                        if (currentAssistant.contents.length === 0 && currentAssistant.streamState === EA_STATE_GENERATING) {
                            this.messages.splice(this.messages.indexOf(currentAssistant), 1);
                        } else {
                            currentAssistant.pendingFinishReason = event.reason || 'stop';
                        }
                    }
                    this.messagesVersion++;
                } else {
                    this._bufferToCache(eventSid, event);
                }
                return;
            case EA_EVENT_ERROR:
                this._clearStreamingTimeout(eventSid);
                delete EA_STREAMING_MAP[eventSid];
                if (eventSid === this.sessionId) {
                    this.appendAssistantError(event.message);
                    this.retryStatus = null;
                    var failedMsg = this.getLastAssistantMessage();
                    if (failedMsg) failedMsg.streamState = EA_STATE_FAILED;
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
        this._refreshStreamingTimeout(eventSid);
        switch (eventType) {
            case EA_EVENT_MESSAGE:
                this.appendMessage(event);
                break;
            case EA_EVENT_TOOL_USE:
                this.appendToolCall(event);
                break;
            case EA_EVENT_COMPACT:
                console.log('[EA] COMPACT event | full=', JSON.stringify(event));
                this.lastTokenUsage = null;
                var compactMsg = this.getLastAssistantMessage();
                if (compactMsg) compactMsg.tokenUsage = null;
                var compactBlock = this._findCompactBlockInAssistant();
                if (compactBlock) {
                    compactBlock.completed = true;
                    if (event.preTokens != null) compactBlock.preTokens = event.preTokens;
                    if (event.postTokens != null) compactBlock.postTokens = event.postTokens;
                    if (event.durationMs != null) compactBlock.durationMs = event.durationMs;
                    if (event.trigger) compactBlock.trigger = event.trigger;
                    console.log('[EA] COMPACT block updated | preTokens=', compactBlock.preTokens, 'postTokens=', compactBlock.postTokens);
                } else if (compactMsg) {
                    compactMsg.contents.push({
                        type: EA_BLOCK_COMPACT, text: 'Compaction', completed: true,
                        preTokens: event.preTokens || null,
                        postTokens: event.postTokens || null,
                        durationMs: event.durationMs || null,
                        trigger: event.trigger || null
                    });
                    compactMsg.lastTextIndex = -1;
                    compactMsg.lastThinkingIndex = -1;
                    console.log('[EA] COMPACT block appended | preTokens=', event.preTokens, 'postTokens=', event.postTokens, 'trigger=', event.trigger);
                } else {
                    console.log('[EA] COMPACT no assistant message found!');
                }
                break;
            case EA_EVENT_RETRY_STATUS:
                this.retryStatus = event;
                var retryMsg = this.ensureAssistantMessage();
                retryMsg.streamState = EA_STATE_RETRYING;
                retryMsg.retryStatus = event;
                break;
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

        switch (eventType) {
            case EA_EVENT_STEP_START:
                this._ensureAssistantIn(msgs).streamState = EA_STATE_GENERATING;
                break;
            case EA_EVENT_STEP_FINISH:
                this._accumulateTokenUsageIn(msgs, event);
                var finishMsg = this._getLastAssistantIn(msgs);
                if (finishMsg) {
                    if (finishMsg.contents.length === 0 && finishMsg.streamState === EA_STATE_GENERATING) {
                        msgs.splice(msgs.indexOf(finishMsg), 1);
                    } else {
                        finishMsg.pendingFinishReason = event.reason || 'stop';
                    }
                }
                break;
            case EA_EVENT_RETRY_STATUS:
                var retryMsg = this._ensureAssistantIn(msgs);
                retryMsg.streamState = EA_STATE_RETRYING;
                retryMsg.retryStatus = event;
                EA_SESSION_CACHE[sid].retryStatus = event;
                break;
            case EA_EVENT_ERROR:
                var errorMsg = this._ensureAssistantIn(msgs);
                errorMsg.streamState = EA_STATE_FAILED;
                errorMsg.contents.push({ type: EA_BLOCK_ERROR, text: event.message });
                delete EA_STREAMING_MAP[sid];
                EA_SESSION_CACHE[sid].isStreaming = false;
                break;
            case EA_EVENT_MESSAGE:
                this._appendMsgTo(msgs, event);
                break;
            case EA_EVENT_TOOL_USE:
                this._appendToolTo(msgs, event);
                break;
            default:
                return;
        }
        this.messagesVersion++;
    },

    _getMsgArray(msgs) {
        return msgs || this.messages;
    },

    _getLastAssistantIn(msgs) {
        var lastUserIdx = -1;
        for (var i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === EA_ROLE_USER) {
                lastUserIdx = i;
                break;
            }
        }
        for (var j = msgs.length - 1; j > lastUserIdx; j--) {
            if (msgs[j].role === EA_ROLE_ASSISTANT) return msgs[j];
        }
        return null;
    },

    _findCompactBlockInAssistant() {
        var last = this._getLastAssistantIn(this.messages);
        if (!last || !last.contents) return null;
        for (var i = last.contents.length - 1; i >= 0; i--) {
            if (last.contents[i].type === EA_BLOCK_COMPACT) return last.contents[i];
        }
        return null;
    },

    _ensureAssistantIn(msgs) {
        var last = msgs[msgs.length - 1];
        if (last && last.role === EA_ROLE_ASSISTANT) {
            console.log('[EA] _ensureAssistantIn REUSE existing | msgsLen=' + msgs.length + ' | contentsLen=' + last.contents.length);
            return last;
        }

        var msg = {
            role: EA_ROLE_ASSISTANT, contents: [],
            lastTextIndex: -1, lastThinkingIndex: -1,
            finishReason: null, pendingFinishReason: null, tokenUsage: null,
            streamState: EA_STATE_GENERATING, retryStatus: null
        };
        msgs.push(msg);
        console.log('[EA] _ensureAssistantIn CREATE new | msgsLen=' + msgs.length + ' | lastRole=' + (last ? last.role : 'null'));
        return msg;
    },

    _appendMsgTo(msgs, event) {
        var text = event.text;
        if (text === null || text === undefined || text === '') return;

        var isThinking = normalizeEAType(event.messageType) === EA_MSG_THINKING;
        if (isThinking) {
            console.log('[EA] THINKING msg | textLen=' + text.length + ' | preview=' + text.substring(0, 60));
        }
        var lastMsg = this._getLastAssistantIn(msgs);

        if (isThinking && lastMsg && lastMsg.lastThinkingIndex >= 0) {
            lastMsg.contents[lastMsg.lastThinkingIndex].text += text;
            return;
        }
        if (!isThinking && lastMsg && lastMsg.lastTextIndex >= 0) {
            lastMsg.contents[lastMsg.lastTextIndex].text += text;
            return;
        }

        var blockType = isThinking ? EA_BLOCK_THINKING : EA_BLOCK_TEXT;
        var block = { type: blockType, text: text };
        if (isThinking) block.collapsed = true;

        lastMsg = this._ensureAssistantIn(msgs);
        lastMsg.streamState = EA_STATE_GENERATING;
        lastMsg.contents.push(block);
        lastMsg.lastTextIndex = isThinking ? -1 : lastMsg.contents.length - 1;
        lastMsg.lastThinkingIndex = isThinking ? lastMsg.contents.length - 1 : -1;
    },

    _appendToolTo(msgs, event) {
        var lastMsg = this._getLastAssistantIn(msgs) || this._ensureAssistantIn(msgs);
        lastMsg.streamState = EA_STATE_GENERATING;
        var status = normalizeEAType(event.status);
        var isComplete = status === EA_TOOL_COMPLETED || status === EA_TOOL_FAILED;
        var toolName = event.toolName || '';

        if (isComplete) {
            var tool = this.findLastToolCall(lastMsg, event);
            if (tool) {
                tool.status = status;
                tool.output = event.output || tool.output;
                tool.fileEdit = event.fileEdit || tool.fileEdit || null;
                if (this._isTodoWriteTool(toolName)) {
                    var todoItems = this._parseTodoItems(event.input || tool.input);
                    if (todoItems && todoItems.length > 0) {
                        this._appendTodoBlock(lastMsg, todoItems);
                    }
                }
                return;
            }
        }

        lastMsg.contents.push({
            type: EA_BLOCK_TOOL_USE,
            toolName: toolName,
            title: event.title,
            toolUseId: event.toolUseId || '',
            status: status || EA_TOOL_CALLING,
            input: event.input,
            output: event.output,
            fileEdit: event.fileEdit || null,
            collapsed: true
        });
        if (isComplete && this._isTodoWriteTool(toolName)) {
            var todoItems = this._parseTodoItems(event.input);
            if (todoItems && todoItems.length > 0) {
                this._appendTodoBlock(lastMsg, todoItems);
            }
        }
        lastMsg.lastTextIndex = -1;
        lastMsg.lastThinkingIndex = -1;
    },

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
            lastMsg.tokenUsage.input = newInput;
            lastMsg.tokenUsage.output = newOutput;
            lastMsg.tokenUsage.total = Math.max(lastMsg.tokenUsage.total, newTotal);
        }
    },

    appendMessage(event) { this._appendMsgTo(this.messages, event); },

    appendToolCall(event) { this._appendToolTo(this.messages, event); },

    getLastAssistantMessage() { return this._getLastAssistantIn(this.messages); },

    ensureAssistantMessage() { return this._ensureAssistantIn(this.messages); },

    accumulateTokenUsage(event) {
        this._accumulateTokenUsageIn(this.messages, event);
        var lastMsg = this._getLastAssistantIn(this.messages);
        if (lastMsg && lastMsg.tokenUsage) {
            this.lastTokenUsage = {
                input: lastMsg.tokenUsage.input,
                output: lastMsg.tokenUsage.output,
                total: lastMsg.tokenUsage.total
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

            if (msg.role === EA_ROLE_SYSTEM && currentGroup && currentGroup.role === EA_ROLE_ASSISTANT) {
                var hasCompact = msg.contents && msg.contents.some(function (b) { return b.type === EA_BLOCK_COMPACT; });
                if (hasCompact) {
                    currentGroup.contents = currentGroup.contents.concat(msg.contents);
                    return;
                }
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
        var hasCompactBlock = false;
        if (isSystemLike && msg.contents) {
            for (var ci = 0; ci < msg.contents.length; ci++) {
                if (normalizeEAType(msg.contents[ci].type || '') === EA_BLOCK_COMPACT) {
                    hasCompactBlock = true;
                    break;
                }
            }
        }
        var contents;
        if (hasCompactBlock) {
            contents = this._convertContentBlocks(msg.contents || []);
        } else if (isSystemLike) {
            contents = [{ type: EA_BLOCK_SYSTEM_INFO, summary: this._extractSystemSummary(msg.contents), fullText: this._extractFullText(msg.contents), collapsed: true }];
        } else {
            contents = this._convertContentBlocks(msg.contents || []);
        }

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
            if (this._isTodoWriteTool(block.toolName)) {
                var todoItems = this._parseTodoItems(block.toolInput);
                if (todoItems && todoItems.length > 0) {
                    return { type: EA_BLOCK_TODO_LIST, items: todoItems };
                }
            }
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
        if (type === EA_BLOCK_COMPACT) {
            return { type: EA_BLOCK_COMPACT, text: block.text || 'Compaction', completed: true };
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
        var isCurrent = msgs === this.messages;
        var lastMsg = isCurrent ? this.getLastAssistantMessage() : this._getLastAssistantIn(msgs);
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

    _isTodoWriteTool(toolName) {
        if (!toolName) return false;
        var n = toolName.toLowerCase().replace(/[-_]/g, '');
        return n === 'todowrite';
    },

    _parseTodoItems(input) {
        if (!input) return null;
        try {
            var obj = typeof input === 'string' ? JSON.parse(input) : input;
            var todos = obj && obj.todos;
            if (!Array.isArray(todos) || todos.length === 0) return null;
            var statusMap = {
                'in_progress': 'IN_PROGRESS', 'inprogress': 'IN_PROGRESS',
                'pending': 'PENDING', 'queued': 'PENDING',
                'completed': 'COMPLETED', 'done': 'COMPLETED',
                'cancelled': 'CANCELLED', 'canceled': 'CANCELLED'
            };
            return todos.filter(function (t) { return t && (t.content || t.title || t.text); }).map(function (t) {
                var raw = (t.status || 'pending').toLowerCase().trim();
                return {
                    id: t.id || String(Math.random()),
                    title: t.content || t.title || t.text || '',
                    status: statusMap[raw] || 'PENDING'
                };
            });
        } catch (e) {
            return null;
        }
    },

    _appendTodoBlock(lastMsg, items) {
        var existing = lastMsg.contents.filter(function (b) { return b.type === EA_BLOCK_TODO_LIST; });
        if (existing.length > 0) {
            existing[existing.length - 1].items = items;
            return;
        }
        lastMsg.contents.push({ type: EA_BLOCK_TODO_LIST, items: items });
        lastMsg.lastTextIndex = -1;
        lastMsg.lastThinkingIndex = -1;
    }
});
