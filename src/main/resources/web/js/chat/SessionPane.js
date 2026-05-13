/**
 * 单个会话面板组件。
 * <p>
 * 每个面板绑定一个 sessionId，独立管理消息列表、流式状态和输入栏。
 * 多个面板可以并排显示在分屏视图中。
 * </p>
 *
 * @component session-pane
 */

var EA_PANE_SCROLL_THRESHOLD = 80;

/** 虚拟滚动：视口上下各多渲染的消息数量缓冲区。 */
var EA_VIRTUAL_BUFFER = 5;

/** 虚拟滚动：每条消息估算高度（像素），用于占位计算。 */
var EA_VIRTUAL_ITEM_HEIGHT = 160;

/** 默认上下文窗口大小。 */
var EA_DEFAULT_CONTEXT_WINDOW = 128000;

/** 上下文使用率警告阈值（百分比）。 */
var EA_CONTEXT_WARN_THRESHOLD = 70;

/** 上下文使用率危险阈值（百分比）。 */
var EA_CONTEXT_DANGER_THRESHOLD = 90;

window.EARegisterComponent('session-pane', 'SessionPane', {
    props: {
        paneId: { type: String, required: true },
        sessionId: { type: String, default: '' },
        cliType: { type: String, default: 'CLAUDE' },
        paneTitle: { type: String, default: '' }
    },
    emits: ['close', 'focus', 'pane-drag-start'],
    data() {
        return {
            showScrollBottom: false,
            pendingQueue: [],
            pendingIdCounter: 0,
            virtualStart: 0,
            virtualEnd: 50,
            _virtualRaf: null
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        isFocused() { return this.store.focusedPaneId === this.paneId; },
        cliTypeLower() { return (this.cliType || 'claude').toLowerCase(); },
        isLoading() { return !!EA_LOADING_MAP[this.sessionId]; },
        isStreaming() { return !!EA_STREAMING_MAP[this.sessionId]; },
        paneMessages() {
            void this.store.messagesVersion;
            var sid = this.sessionId;
            if (sid === this.store.sessionId) return this.store.messages;
            var cached = EA_SESSION_CACHE[sid];
            return cached ? cached.messages : [];
        },
        virtualVisibleMessages() {
            var msgs = this.paneMessages;
            if (msgs.length <= 60) return msgs;
            var start = Math.max(0, this.virtualStart);
            var end = Math.min(msgs.length, this.virtualEnd);
            return msgs.slice(start, end);
        },
        virtualTopPadding() {
            if (this.paneMessages.length <= 60) return 0;
            return this.virtualStart * EA_VIRTUAL_ITEM_HEIGHT;
        },
        virtualBottomPadding() {
            var msgs = this.paneMessages;
            if (msgs.length <= 60) return 0;
            var rendered = this.virtualEnd - this.virtualStart;
            var below = msgs.length - this.virtualEnd;
            return Math.max(0, below) * EA_VIRTUAL_ITEM_HEIGHT;
        },
        _extractPaneTokenUsage() {
            var msgs = this.paneMessages;
            if (!msgs) return null;
            for (var i = msgs.length - 1; i >= 0; i--) {
                var usage = msgs[i].tokenUsage;
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
        tokenUsageInfo() {
            var lastUsage = this._extractPaneTokenUsage();
            if (!lastUsage) return null;
            var model = (this.store.selectedModelId || this.store.model || '').toLowerCase();
            var map = this.store.modelContextMap || {};
            var window_ = EA_DEFAULT_CONTEXT_WINDOW;
            if (map[this.store.selectedModelId]) {
                window_ = map[this.store.selectedModelId];
            } else if (!this.store.selectedModelId) {
                var cliType = this.cliType || 'CLAUDE';
                var defaultInfo = this.store.defaultModelInfoMap[cliType];
                if (defaultInfo && defaultInfo.contextWindow) {
                    window_ = defaultInfo.contextWindow;
                }
            } else {
                for (var key in map) {
                    if (model.indexOf(key.toLowerCase()) >= 0) {
                        window_ = map[key];
                        break;
                    }
                }
            }
            var inputTokens = lastUsage.input || lastUsage.total || 0;
            return { input: inputTokens, window: window_ };
        },
        contextProgress() {
            var info = this.tokenUsageInfo;
            if (!info || info.window <= 0) return 0;
            return Math.min(100, Math.round(info.input / info.window * 100));
        },
        contextProgressClass() {
            if (this.contextProgress >= EA_CONTEXT_DANGER_THRESHOLD) return 'danger';
            if (this.contextProgress >= EA_CONTEXT_WARN_THRESHOLD) return 'warn';
            return '';
        },
        contextProgressLabel() {
            var info = this.tokenUsageInfo;
            if (!info) return '';
            var input = info.input;
            var label = input >= 1000 ? (input / 1000).toFixed(1) + 'K' : input;
            return label + ' / ' + (info.window / 1000) + 'K';
        },
        canRetry() {
            if (this.isStreaming) return false;
            void this.store.messagesVersion;
            var msgs = this.paneMessages;
            var last = msgs[msgs.length - 1];
            if (!last) return false;
            return last.role === EA_ROLE_ASSISTANT && last.streamState === 'failed';
        },
        lastUserTurn() {
            void this.store.messagesVersion;
            var msgs = this.paneMessages;
            for (var i = msgs.length - 1; i >= 0; i--) {
                if (msgs[i].role === EA_ROLE_USER) return msgs[i];
            }
            return null;
        }
    },
    watch: {
        'store.messagesVersion'() {
            this.$nextTick(this.scrollToBottom);
        },
        sessionId() {
            this._restorePending();
            this.$nextTick(function () { setTimeout(this.scrollToBottom, 50); }.bind(this));
        },
        isStreaming(newVal, oldVal) {
            if (oldVal && !newVal && this.pendingQueue.length > 0) {
                this.$nextTick(this.sendNextPending);
            }
            this._persistPendingQueue();
        }
    },
    mounted() {
        this._restorePending();
    },
    beforeUnmount() {
        if (this._virtualRaf) {
            cancelAnimationFrame(this._virtualRaf);
            this._virtualRaf = null;
        }
    },
    methods: {
        onFocus() {
            this.store.focusPane(this.paneId);
            this.$emit('focus', this.paneId);
        },

        onHeaderMouseDown(e) {
            if (e.button !== 0) return;
            if (this.store.getPaneCount() <= 1) return;
            this.$emit('pane-drag-start', this.paneId);
        },

        onSend(payload) {
            var text = payload && payload.text ? payload.text : '';
            var fileReferences = payload && payload.fileReferences ? payload.fileReferences : [];
            var slashCommand = payload && payload.slashCommand ? payload.slashCommand : null;
            var sid = this.sessionId;
            if (!sid) {
                sid = 'new-' + Date.now();
                this.store.updatePaneSession(this.paneId, sid);
            }
            if (this.isStreaming) {
                this.addToPendingQueue({ text: text, fileReferences: fileReferences, slashCommand: slashCommand });
                return;
            }
            if (slashCommand) {
                this._executeSlashCommand({ text: text, fileReferences: fileReferences, slashCommand: slashCommand });
                return;
            }
            this._sendPlainMessage(text, fileReferences);
        },

        _sendPlainMessage(text, fileReferences) {
            this._ensurePaneActive();
            this.store.addUserMessage(text, fileReferences);
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            this.$nextTick(this.scrollToBottom);
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(text, modelId, fileReferences);
        },

        _ensurePaneActive() {
            var sid = this.sessionId;
            if (sid !== this.store.sessionId) {
                this.store._saveCurrentToCache();
                this.store.sessionId = sid;
                this.store.cliType = this.cliType;
                var cached = EA_SESSION_CACHE[sid];
                if (cached && cached.loaded) {
                    this.store.messages = cached.messages;
                    this.store.retryStatus = cached.retryStatus || null;
                    this.store.lastTokenUsage = cached.lastTokenUsage || null;
                } else {
                    this.store.messages = [];
                    this.store.retryStatus = null;
                    this.store.lastTokenUsage = null;
                }
                this.store.streamingText = '';
                this.store.messagesVersion++;
            }
        },

        _executeSlashCommand(payload) {
            this._ensurePaneActive();
            var requestId = 'pane-cmd-' + (++this.pendingIdCounter);
            EABridge.executeSlashCommand(this.cliType, payload.text || '', requestId);
        },

        onStop() {
            EABridge.stopGeneration();
            this.store.setStreaming(false, this.sessionId);
        },

        onRetry() {
            var lastUserTurn = this.lastUserTurn;
            if (!lastUserTurn) return;
            var text = lastUserTurn.rawText || '';
            var fileReferences = lastUserTurn.fileReferences || [];
            this._ensurePaneActive();
            var last = this.paneMessages[this.paneMessages.length - 1];
            if (last && last.role === EA_ROLE_ASSISTANT) {
                var msgs = this.store.messages === this.paneMessages ? this.store.messages : this.paneMessages;
                msgs.pop();
            }
            this.store.messagesVersion++;
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(text, modelId, fileReferences);
        },

        addToPendingQueue(payload) {
            this.pendingIdCounter++;
            this.pendingQueue.push({
                id: 'ppq-' + this.pendingIdCounter,
                text: payload.text,
                fileReferences: payload.fileReferences || [],
                slashCommand: payload.slashCommand || null
            });
            this._persistPendingQueue();
        },

        removePending(id) {
            this.pendingQueue = this.pendingQueue.filter(function (item) { return item.id !== id; });
            this._persistPendingQueue();
        },

        updatePending(payload) {
            var item = this.pendingQueue.find(function (i) { return i.id === payload.id; });
            if (item) {
                item.text = payload.text;
                item.slashCommand = null;
            }
            this._persistPendingQueue();
        },

        sendNextPending() {
            if (this.pendingQueue.length === 0) return;
            var next = this.pendingQueue.shift();
            if (next.slashCommand) {
                this._executeSlashCommand(next);
                this._persistPendingQueue();
                return;
            }
            this._sendPlainMessage(next.text, next.fileReferences || []);
            this._persistPendingQueue();
        },

        onMessagesScroll() {
            var area = this.$refs.messagesArea;
            if (!area) return;
            var distToBottom = area.scrollHeight - area.scrollTop - area.clientHeight;
            this.showScrollBottom = distToBottom > EA_PANE_SCROLL_THRESHOLD;
            this._scheduleVirtualUpdate();
        },

        _scheduleVirtualUpdate() {
            if (this._virtualRaf) return;
            this._virtualRaf = requestAnimationFrame(function () {
                this._virtualRaf = null;
                this._updateVirtualWindow();
            }.bind(this));
        },

        _updateVirtualWindow() {
            var msgs = this.paneMessages;
            if (msgs.length <= 60) {
                this.virtualStart = 0;
                this.virtualEnd = msgs.length;
                return;
            }
            var area = this.$refs.messagesArea;
            if (!area) return;
            var scrollTop = area.scrollTop;
            var viewportHeight = area.clientHeight;
            var itemH = EA_VIRTUAL_ITEM_HEIGHT;

            var visibleStart = Math.floor(scrollTop / itemH);
            var visibleEnd = Math.ceil((scrollTop + viewportHeight) / itemH);

            this.virtualStart = Math.max(0, visibleStart - EA_VIRTUAL_BUFFER);
            this.virtualEnd = Math.min(msgs.length, visibleEnd + EA_VIRTUAL_BUFFER);
        },

        scrollToBottom() {
            var area = this.$refs.messagesArea;
            if (area) area.scrollTop = area.scrollHeight;
            this.showScrollBottom = false;
        },

        _restorePending() {
            var sid = this.sessionId;
            if (!sid) { this.pendingQueue = []; return; }
            this.pendingQueue = this.store.restorePendingQueue(sid);
        },

        _persistPendingQueue() {
            var sid = this.sessionId;
            if (!sid) return;
            var items = this.pendingQueue.map(function (item) {
                return {
                    id: item.id,
                    text: item.text,
                    fileReferences: item.fileReferences || [],
                    slashCommand: item.slashCommand || null
                };
            });
            this.store.savePendingQueue(sid, this.pendingQueue);
            EABridge.savePendingQueue(sid, JSON.stringify(items));
        }
    }
});
