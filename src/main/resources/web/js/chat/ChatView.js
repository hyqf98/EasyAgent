/**
 * 对话主视图组件。
 * <p>
 * 组合 ChatHeader、MessageBubble、InputBar、EmptyState 等子组件，
 * 构成完整的对话界面。默认显示欢迎页面，选择 CLI 后进入聊天模式。
 * 支持待发送消息队列，AI 响应完成后自动发送队列中的下一条消息。
 * </p>
 *
 * @component chat-view
 */

/** 分页加载阈值：距底部多少像素时触发加载。 */
var EA_DRAWER_LOAD_THRESHOLD = 60;

/** 消息区滚动到底部阈值：距底部超过此值显示按钮。 */
var EA_SCROLL_BOTTOM_THRESHOLD = 80;

/** 默认上下文窗口大小。 */
var EA_DEFAULT_CONTEXT_WINDOW = 128000;

/** 上下文进度警告阈值（百分比）。 */
var EA_CONTEXT_WARN_THRESHOLD = 70;
var EA_CONTEXT_DANGER_THRESHOLD = 90;

window.EARegisterComponent('chat-view', 'ChatView', {
    data() {
        return {
            showDrawer: false,
            showSettings: false,
            allSessions: [],
            sessions: [],
            sessionPage: 1,
            sessionPageSize: 10,
            showScrollBottom: false,
            pendingQueue: [],
            pendingIdCounter: 0,
            selectedSessions: [],
            previousSessionId: null,
            pendingDeleteRedirect: false,
            isDeletingSessions: false,
            pendingSlashExecutions: {},
            pendingSlashRefreshSessionId: null,
            toastMessage: '',
            toastTimer: null
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        inChat() {
            void this.store.messagesVersion;
            return this.store.messages.length > 0 || this.store.sessionId !== null || this.store.isStreaming;
        },
        latestTodo() {
            void this.store.messagesVersion;
            var messages = this.store.messages;
            for (var i = messages.length - 1; i >= 0; i--) {
                var found = messages[i].contents.findLast(function (b) { return b.type === 'TODO_LIST'; });
                if (found) return found;
            }
            return null;
        },
        hasMoreSessions() {
            return this.sessions.length < this.allSessions.length;
        },
        hasSelectedSessions() {
            return this.selectedSessions.length > 0;
        },
        currentSessionId() {
            return this.store.sessionId;
        },
        allSessionsSelected() {
            return this.sessions.length > 0 && this.selectedSessions.length === this.sessions.length;
        },
        canRetry() {
            if (this.store.isStreaming) return false;
            void this.store.messagesVersion;
            var last = this.store.messages[this.store.messages.length - 1];
            if (!last) return false;
            return last.role === EA_ROLE_ASSISTANT && last.streamState === 'failed';
        },
        lastUserTurn() {
            void this.store.messagesVersion;
            var messages = this.store.messages;
            for (var i = messages.length - 1; i >= 0; i--) {
                if (messages[i].role === EA_ROLE_USER) {
                    return messages[i];
                }
            }
            return null;
        },
        tokenUsageInfo() {
            void this.store.messagesVersion;
            var lastUsage = this.store.lastTokenUsage;
            if (!lastUsage) return null;
            var model = (this.store.selectedModelId || this.store.model || '').toLowerCase();
            var map = this.store.modelContextMap || {};
            var window = EA_DEFAULT_CONTEXT_WINDOW;
            if (map[this.store.selectedModelId]) {
                window = map[this.store.selectedModelId];
            } else if (!this.store.selectedModelId) {
                var cliType = this.store.cliType || 'CLAUDE';
                var defaultInfo = this.store.defaultModelInfoMap[cliType];
                if (defaultInfo && defaultInfo.contextWindow) {
                    window = defaultInfo.contextWindow;
                }
            } else {
                for (var key in map) {
                    if (model.indexOf(key.toLowerCase()) >= 0) {
                        window = map[key];
                        break;
                    }
                }
            }
            var inputTokens = lastUsage.input || lastUsage.total || 0;
            return { input: inputTokens, window: window };
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
        currentModels() {
            var list = this.store.modelsList || [];
            var cliType = this.store.cliType;
            return list.filter(function (m) { return m.cliType === cliType; });
        }
    },
     mounted() {
         this.previousSessionId = this.store.sessionId;
         if (this.store.pendingOpenSettings) {
             this.store.pendingOpenSettings = false;
             this.showSettings = true;
         }
        this._onSessionList = function (e) {
            var detail = e.detail || {};
            this.allSessions = detail.sessions || [];
            this.sessionPage = 1;
            this.sessions = this.allSessions.slice(0, this.sessionPageSize);
            var sessionIdMap = {};
            this.allSessions.forEach(function (s) {
                sessionIdMap[s.sessionId] = true;
            });
            this.selectedSessions = this.selectedSessions.filter(function (sid) {
                return !!sessionIdMap[sid];
            });
        }.bind(this);
        this._onSessionsDeleted = function (e) {
            var detail = e.detail || {};
            this.isDeletingSessions = false;
            if (detail.deletedCount > 0) {
                var deletedCurrent = this.store.sessionId && detail.sessionIds && detail.sessionIds.indexOf(this.store.sessionId) >= 0;
                if (deletedCurrent) {
                    this.openFreshChatForCurrentCli();
                }
                this.showToast(this.buildDeletedToast(detail.deletedCount, deletedCurrent));
            }
            if (this.pendingDeleteRedirect) {
                this.pendingDeleteRedirect = false;
            }
        }.bind(this);
        this._onSlashCommandExecuted = function (e) {
            var detail = e.detail || {};
            var requestId = detail.requestId || '';
            var pending = requestId ? this.pendingSlashExecutions[requestId] : null;
            if (requestId && !pending) {
                return;
            }
            if (requestId) {
                delete this.pendingSlashExecutions[requestId];
            }
            if (detail.toastMessage) {
                this.showToast(detail.toastMessage);
            }
            if (detail.openFreshSession) {
                this.openFreshChatForCurrentCli();
                if (!detail.prompt) {
                    return;
                }
            }
            if (!detail.prompt) {
                return;
            }
            if (detail.refreshHistory) {
                this.pendingSlashRefreshSessionId = this.store.sessionId;
            }
            var payload = pending || { text: detail.commandName || '', fileReferences: [] };
            this._sendCommandPrompt(detail.prompt, payload.text, payload.fileReferences || []);
        }.bind(this);
        this._onStreamComplete = function (e) {
            var detail = e.detail || {};
            var sessionId = detail.sessionId || this.store.sessionId;
            if (!sessionId || this.pendingSlashRefreshSessionId !== sessionId) {
                return;
            }
            this.pendingSlashRefreshSessionId = null;
            var cliType = this.store.cliType;
            setTimeout(function () {
                EABridge.loadHistory(sessionId, cliType, true);
            }, 60);
        }.bind(this);
        window.addEventListener('ea-session-list', this._onSessionList);
        window.addEventListener('ea-sessions-deleted', this._onSessionsDeleted);
        window.addEventListener('ea-slash-command-executed', this._onSlashCommandExecuted);
        window.addEventListener('ea-stream-complete', this._onStreamComplete);
    },
    beforeUnmount() {
        if (this._onSessionList) {
            window.removeEventListener('ea-session-list', this._onSessionList);
        }
        if (this._onSessionsDeleted) {
            window.removeEventListener('ea-sessions-deleted', this._onSessionsDeleted);
        }
        if (this._onSlashCommandExecuted) {
            window.removeEventListener('ea-slash-command-executed', this._onSlashCommandExecuted);
        }
        if (this._onStreamComplete) {
            window.removeEventListener('ea-stream-complete', this._onStreamComplete);
        }
        if (this.toastTimer) {
            clearTimeout(this.toastTimer);
            this.toastTimer = null;
        }
    },
    watch: {
        'store.messagesVersion'() {
            this.$nextTick(this.scrollToBottom);
        },
        'store.sessionId'() {
            this._restorePendingForCurrent();
            this.$nextTick(function () { setTimeout(this.scrollToBottom, 50); }.bind(this));
        },
        'store.cliType'(newType) {
            this.store.selectedModelId = '';
            if (newType === 'OPENCODE') {
                EABridge.queryCliModels('OPENCODE');
            }
        },
        'store.isStreaming'(newVal, oldVal) {
            if (oldVal && !newVal && this.pendingQueue.length > 0) {
                this.$nextTick(this.sendNextPending);
            }
            this._persistPendingQueue();
        }
    },
    methods: {
        onSend(payload) {
            var text = payload && payload.text ? payload.text : '';
            var fileReferences = payload && payload.fileReferences ? payload.fileReferences : [];
            var slashCommand = payload && payload.slashCommand ? payload.slashCommand : null;
            if (!this.store.sessionId && (!slashCommand || slashCommand.executionType !== 'OPEN_NEW_SESSION')) {
                this.store.sessionId = 'new-' + Date.now();
            }
            if (this.store.isStreaming) {
                this.addToPendingQueue({ text: text, fileReferences: fileReferences, slashCommand: slashCommand });
                return;
            }
            if (slashCommand) {
                this.executeSlashCommand({ text: text, fileReferences: fileReferences, slashCommand: slashCommand });
                return;
            }
            this.sendPlainMessage(text, fileReferences);
        },
        sendPlainMessage(text, fileReferences) {
            this.store.addUserMessage(text, fileReferences);
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            this.$nextTick(this.scrollToBottom);
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(text, modelId, fileReferences);
        },
        _sendCommandPrompt(prompt, displayText, fileReferences) {
            this.store.addUserMessage(displayText, fileReferences);
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            this.$nextTick(this.scrollToBottom);
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(prompt, modelId, fileReferences);
        },
        executeSlashCommand(payload) {
            if (!payload || !payload.slashCommand) {
                return;
            }
            var requestId = 'cmd-' + (++this.pendingIdCounter);
            this.pendingSlashExecutions[requestId] = {
                text: payload.text || '',
                fileReferences: payload.fileReferences || []
            };
            EABridge.executeSlashCommand(this.store.cliType, payload.text || '', requestId);
        },
        onStop() {
            EABridge.stopGeneration();
            this.store.setStreaming(false);
            this.pendingSlashRefreshSessionId = null;
        },
        onRetry() {
            var lastUserTurn = this.lastUserTurn;
            if (!lastUserTurn) return;
            var text = lastUserTurn.rawText || '';
            var fileReferences = lastUserTurn.fileReferences || [];
            var last = this.store.messages[this.store.messages.length - 1];
            if (last && last.role === EA_ROLE_ASSISTANT) {
                this.store.messages.pop();
            }
            this.store.messagesVersion++;
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(text, modelId, fileReferences);
        },
        onSelectCLI(type) {
            this._saveCurrentPending();
            this.store._saveCurrentToCache();
            this.store.cliType = type;
            this.store.sessionId = 'new-' + Date.now();
            this.store.messages = [];
            this.store.sessionTitle = '';
            this.store.model = '';
            this.store.selectedModelId = '';
            this.store.selectedReasoningLevel = '';
            this.pendingQueue = [];
            this.pendingSlashRefreshSessionId = null;
            this.store.messagesVersion++;
            EABridge.send('createSession', { cliType: type });
            this._loadLatestSession(type);
        },
        onNewChat() {
            this._saveCurrentPending();
            this.store._saveCurrentToCache();
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.sessionTitle = '';
            this.store.model = '';
            this.pendingQueue = [];
            this.pendingDeleteRedirect = false;
            this.pendingSlashRefreshSessionId = null;
            this.store.messagesVersion++;
        },
        openFreshChatForCurrentCli() {
            this._saveCurrentPending();
            this.store._saveCurrentToCache();
            this.store.messages = [];
            this.store.sessionTitle = '';
            this.store.model = '';
            this.pendingQueue = [];
            this.store.sessionId = 'new-' + Date.now();
            this.pendingDeleteRedirect = false;
            this.pendingSlashRefreshSessionId = null;
            this.store.messagesVersion++;
        },
        buildDeletedToast(deletedCount, deletedCurrent) {
            var cliLabel = this.store.cliType || 'CLI';
            if (deletedCurrent) {
                return this.i18n.t('session.deletedAndReset', { n: deletedCount, cli: cliLabel });
            }
            return this.i18n.t('session.deleted', { n: deletedCount });
        },
        showToast(message) {
            if (!message) return;
            this.toastMessage = message;
            if (this.toastTimer) {
                clearTimeout(this.toastTimer);
            }
            this.toastTimer = setTimeout(function () {
                this.toastMessage = '';
                this.toastTimer = null;
            }.bind(this), 2400);
        },
        onBackHome() {
            this.showDrawer = false;
            this.showSettings = false;
            if (this.store.isStreaming) {
                this.onStop();
            }
            this.onNewChat();
        },
        onOpenPlanMode() {
            this.store.appMode = 'plan';
        },
        toggleDrawer() {
            this.showDrawer = !this.showDrawer;
            if (!this.showDrawer) return;
            this.loadSessionsForCurrentCLI();
        },
        onSelectSession(session) {
            this._saveCurrentPending();
            this.showDrawer = false;
            this.store.cliType = session.cliType || this.store.cliType;
            this.pendingSlashRefreshSessionId = null;
            EABridge.loadHistory(session.sessionId, this.store.cliType);
        },
        loadSessionsForCurrentCLI() {
            EABridge.listSessions(this.store.cliType);
        },
        onDrawerScroll(e) {
            var el = e.target;
            if (!el || !this.hasMoreSessions) return;
            var distToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
            if (distToBottom >= EA_DRAWER_LOAD_THRESHOLD) return;
            this.loadMoreSessions();
        },
        loadMoreSessions() {
            if (!this.hasMoreSessions) return;
            this.sessionPage++;
            this.sessions = this.allSessions.slice(0, this.sessionPage * this.sessionPageSize);
        },
        formatTime(ts) { return EATimeFormat.relative(ts); },
        onMessagesScroll() {
            var area = this.$refs.messagesArea;
            if (!area) return;
            var distToBottom = area.scrollHeight - area.scrollTop - area.clientHeight;
            this.showScrollBottom = distToBottom > EA_SCROLL_BOTTOM_THRESHOLD;
        },
        scrollToBottom() {
            var area = this.$refs.messagesArea;
            if (area) area.scrollTop = area.scrollHeight;
            this.showScrollBottom = false;
        },

        /**
         * 全选当前已加载的会话。
         */
        selectAllSessions() {
            this.selectedSessions = this.sessions.map(function (s) { return s.sessionId; });
        },

        /**
         * 取消当前所有勾选。
         */
        clearSelection() {
            this.selectedSessions = [];
        },

        /**
         * 切换选中某个会话。
         *
         * @param {string} sessionId - 会话 ID
         */
        toggleSelect(sessionId) {
            var idx = this.selectedSessions.indexOf(sessionId);
            if (idx >= 0) {
                this.selectedSessions.splice(idx, 1);
            } else {
                this.selectedSessions.push(sessionId);
            }
        },
        isCurrentSession(session) {
            return !!session && !!this.currentSessionId && session.sessionId === this.currentSessionId;
        },

        /**
         * 选择 CLI 后加载最新会话，没有则保持空白等待用户输入。
         *
         * @param {string} cliType - CLI 类型
         */
        _loadLatestSession(cliType) {
            var self = this;
            var handler = function (e) {
                window.removeEventListener('ea-session-list', handler);
                var sessions = (e.detail && e.detail.sessions) || [];
                if (sessions.length > 0) {
                    self.store.cliType = cliType;
                    EABridge.loadHistory(sessions[0].sessionId, cliType);
                }
            };
            window.addEventListener('ea-session-list', handler);
            EABridge.listSessions(cliType);
        },

        /**
         * 抽屉列表项点击事件，打开指定会话。
         *
         * @param {Object} s - 会话对象
         */
        onDrawerItemClick(s) {
            this.onSelectSession(s);
        },

        /**
         * 确认删除选中的会话。
         */
        confirmDelete() {
            if (this.selectedSessions.length === 0) return;
            if (this.isDeletingSessions) return;
            if (!confirm(this.i18n.t('session.deleteConfirm', { n: this.selectedSessions.length }))) return;
            this.isDeletingSessions = true;
            this.pendingDeleteRedirect = this.selectedSessions.indexOf(this.store.sessionId) >= 0;
            var idsToDelete = this.selectedSessions.slice();
            var self = this;
            setTimeout(function () { self.isDeletingSessions = false; }, 15000);
            EABridge.deleteSessions(idsToDelete);
            this.clearSelection();
        },

        /**
         * 将消息添加到待发送队列。
         *
         * @param {string} text - 待发送消息文本
         */
        addToPendingQueue(payload) {
            this.pendingIdCounter++;
            this.pendingQueue.push({
                id: 'pq-' + this.pendingIdCounter,
                text: payload.text,
                fileReferences: payload.fileReferences || [],
                slashCommand: payload.slashCommand || null
            });
            this._persistPendingQueue();
        },

        /**
         * 从待发送队列中移除指定消息。
         *
         * @param {string} id - 消息 ID
         */
        removePending(id) {
            this.pendingQueue = this.pendingQueue.filter(function (item) { return item.id !== id; });
            this._persistPendingQueue();
        },

        /**
         * 更新待发送队列中的消息内容。
         *
         * @param {{id: string, text: string}} payload - 包含 ID 和新文本的对象
         */
        updatePending(payload) {
            var item = this.pendingQueue.find(function (i) { return i.id === payload.id; });
            if (item) {
                item.text = payload.text;
                item.slashCommand = null;
            }
            this._persistPendingQueue();
        },

        /**
         * 自动发送待发送队列中的下一条消息。
         */
        sendNextPending() {
            if (this.pendingQueue.length === 0) return;
            var next = this.pendingQueue.shift();
            if (next.slashCommand) {
                this.executeSlashCommand(next);
                this._persistPendingQueue();
                return;
            }
            this.sendPlainMessage(next.text, next.fileReferences || []);
            this._persistPendingQueue();
        },

        /**
         * 保存当前会话的待发送队列。
         */
        _saveCurrentPending() {
            this.store.savePendingQueue(this.previousSessionId, this.pendingQueue);
        },

        /**
         * 恢复当前会话的待发送队列。
         */
        _restorePendingForCurrent() {
            this.pendingQueue = this.store.restorePendingQueue(this.store.sessionId);
            this.previousSessionId = this.store.sessionId;
        },

        /**
         * 持久化当前待发送队列到后端。
         */
        _persistPendingQueue() {
            var sid = this.store.sessionId;
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
