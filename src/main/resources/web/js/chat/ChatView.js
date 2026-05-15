/**
 * 对话主视图组件。
 * <p>
 * 组合 ChatHeader、TabBar、SplitContainer、SessionPane 等子组件，
 * 构成完整的对话界面。支持多面板分屏显示，每个面板独立管理消息和输入。
 * 默认显示欢迎页面，选择 CLI 后进入聊天模式。
 * </p>
 *
 * @component chat-view
 */

/** 分页加载阈值：距底部多少像素时触发加载。 */
var EA_DRAWER_LOAD_THRESHOLD = 60;

window.EARegisterComponent('chat-view', 'ChatView', {
    data() {
        return {
            showDrawer: false,
            showSettings: false,
            allSessions: [],
            sessions: [],
            sessionPage: 1,
            sessionPageSize: 10,
            selectedSessions: [],
            isDeletingSessions: false,
            pendingDeleteRedirect: false,
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
            if (this.store.activePanes.length > 0) return true;
            return this.store.messages.length > 0 || this.store.sessionId !== null || this.store.isStreaming;
        },
        hasMoreSessions() {
            return this.sessions.length < this.allSessions.length;
        },
        hasSelectedSessions() {
            return this.selectedSessions.length > 0;
        },
        currentSessionId() {
            return this.store.sessionId;
        }
    },
    mounted() {
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
            this.pendingDeleteRedirect = false;
        }.bind(this);
        this._onSlashCommandExecuted = function (e) {
            var detail = e.detail || {};
            if (detail.toastMessage) this.showToast(detail.toastMessage);
            if (detail.openFreshSession) {
                this.openFreshChatForCurrentCli();
            }
            if (!detail.prompt) return;
            var prompt = detail.prompt;
            this.store.addUserMessage(prompt, []);
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            this.$nextTick(function () {
                var activePane = this.store.getFocusedPane();
                if (activePane && activePane.$refs && activePane.$refs.sessionPane) {
                    activePane.$refs.sessionPane.scrollToBottom();
                }
            }.bind(this));
            var modelId = this.store.selectedModelId || null;
            EABridge.sendMessage(prompt, modelId, []);
            if (detail.refreshHistory) {
                this.pendingSlashRefreshSessionId = this.store.sessionId;
            }
        }.bind(this);
        this._onStreamComplete = function (e) {
            var detail = e.detail || {};
            var sessionId = detail.sessionId || this.store.sessionId;
            if (!sessionId || EAIsProvisionalSessionId(sessionId) || this.pendingSlashRefreshSessionId !== sessionId) return;
            this.pendingSlashRefreshSessionId = null;
            var cliType = this.store.cliType;
            setTimeout(function () {
                EABridge.loadHistory(sessionId, cliType, true);
            }, 60);
        }.bind(this);
        this._onStateRestored = function (e) {
            var detail = e.detail || {};
            if (!this._restorePaneLayout(detail.paneLayoutJson)) {
                this._restoreLastSession(detail.currentSessionId, detail.currentCliType);
            }
        }.bind(this);
        window.addEventListener('ea-session-list', this._onSessionList);
        window.addEventListener('ea-sessions-deleted', this._onSessionsDeleted);
        window.addEventListener('ea-slash-command-executed', this._onSlashCommandExecuted);
        window.addEventListener('ea-stream-complete', this._onStreamComplete);
        window.addEventListener('ea-state-restored', this._onStateRestored);
    },
    beforeUnmount() {
        if (this._onSessionList) window.removeEventListener('ea-session-list', this._onSessionList);
        if (this._onSessionsDeleted) window.removeEventListener('ea-sessions-deleted', this._onSessionsDeleted);
        if (this._onSlashCommandExecuted) window.removeEventListener('ea-slash-command-executed', this._onSlashCommandExecuted);
        if (this._onStreamComplete) window.removeEventListener('ea-stream-complete', this._onStreamComplete);
        if (this._onStateRestored) window.removeEventListener('ea-state-restored', this._onStateRestored);
        if (this.toastTimer) { clearTimeout(this.toastTimer); this.toastTimer = null; }
    },
    watch: {
        'store.cliType'(newType) {
            this.store.selectedModelId = '';
            if (newType === 'OPENCODE') {
                EABridge.queryCliModels('OPENCODE');
            }
        },
        'store.sessionId'(newSid) {
            if (newSid && this.store.activePanes.length === 0) {
                this.store.addPane(newSid, this.store.cliType, this.store.sessionTitle);
            }
        },
        'store.paneGrid': {
            handler() {
                this._savePaneLayout();
            },
            deep: true
        }
    },
    methods: {
        onSelectCLI(type) {
            this.store.cliType = type;
            this.store.selectedModelId = '';
            this.store.selectedReasoningLevel = '';
            var pane = this.store.addPane(null, type, '');
            this.store.sessionId = pane.sessionId;
            this.store.messages = [];
            this.store.sessionTitle = '';
            this.store.model = '';
            this.store.lastTokenUsage = null;
            this.store.messagesVersion++;
            EABridge.send('createSession', { cliType: type });
        },
        onNewChat() {
            this.openFreshChatForCurrentCli();
        },
        onSplitPane() {
            var pane = this.store.addPane(null, this.store.cliType, '');
            this.store._saveCurrentToCache();
            this.store.sessionId = pane.sessionId;
            this.store.messages = [];
            this.store.sessionTitle = '';
            this.store.model = '';
            this.store.lastTokenUsage = null;
            this.store.messagesVersion++;
        },
        onClosePane(paneId) {
            var wasFocused = this.store.focusedPaneId === paneId;
            var pane = this.store.getPaneById(paneId);
            if (pane && pane.sessionId) {
                this.store.savePendingQueue(pane.sessionId, []);
            }
            this.store.removePane(paneId);
            if (this.store.activePanes.length === 0) {
                this._resetToHome();
                return;
            }
            if (wasFocused) {
                var newFocused = this.store.getFocusedPane();
                if (newFocused) {
                    this.store.activatePaneAsPrimary(newFocused.paneId);
                }
            }
        },
        onSelectPane(paneId) {
            this.store.activatePaneAsPrimary(paneId);
        },
        onFocusPane(paneId) {
            this.store.activatePaneAsPrimary(paneId);
        },
        _resetToHome() {
            this.store._saveCurrentToCache();
            this.store.activePanes = [];
            this.store.paneGrid = [];
            this.store.focusedPaneId = null;
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.sessionTitle = '';
            this.store.model = '';
            this.store.lastTokenUsage = null;
            this.pendingDeleteRedirect = false;
            this.pendingSlashRefreshSessionId = null;
            this.store.messagesVersion++;
            this._savePaneLayout();
        },
        openFreshChatForCurrentCli() {
            this.store._saveCurrentToCache();
            var newSid = 'new-' + Date.now();
            var currentPane = this.store.getFocusedPane();
            if (currentPane) {
                currentPane.sessionId = newSid;
                currentPane.title = '';
            }
            this.store.sessionId = newSid;
            this.store.messages = [];
            this.store.sessionTitle = '';
            this.store.model = '';
            this.store.lastTokenUsage = null;
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
            if (this.toastTimer) clearTimeout(this.toastTimer);
            this.toastTimer = setTimeout(function () {
                this.toastMessage = '';
                this.toastTimer = null;
            }.bind(this), 2400);
        },
        onBackHome() {
            this.showDrawer = false;
            this.showSettings = false;
            if (this.store.isStreaming) {
                EABridge.stopGeneration();
                this.store.setStreaming(false);
            }
            this._resetToHome();
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
            this.showDrawer = false;
            this.store.cliType = session.cliType || this.store.cliType;
            this.pendingSlashRefreshSessionId = null;
            if (this.store.activePanes.length > 0) {
                var focused = this.store.getFocusedPane();
                if (focused) {
                    this.store._saveCurrentToCache();
                    this.store.updatePaneSession(focused.paneId, session.sessionId, session.title);
                }
            }
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
        selectAllSessions() {
            this.selectedSessions = this.sessions.map(function (s) { return s.sessionId; });
        },
        clearSelection() {
            this.selectedSessions = [];
        },
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
        onDrawerItemClick(s) {
            this.onSelectSession(s);
        },
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
        _restorePaneLayout(paneLayoutJson) {
            if (!paneLayoutJson) return false;
            try {
                var layout = JSON.parse(paneLayoutJson);
                if (!layout || !layout.panes || !layout.grid) return false;
                var panes = layout.panes;
                var grid = layout.grid;
                if (!Array.isArray(panes) || panes.length === 0) return false;
                if (!Array.isArray(grid) || grid.length === 0) return false;

                var allPaneIds = {};
                grid.forEach(function (row) {
                    (row || []).forEach(function (pid) { allPaneIds[pid] = true; });
                });
                var validPanes = panes.filter(function (p) {
                    return !!allPaneIds[p.paneId] && !!p.sessionId && !EAIsProvisionalSessionId(p.sessionId);
                });
                if (validPanes.length === 0) return false;

                var validGrid = grid.map(function (row) {
                    return (row || []).filter(function (pid) {
                        return validPanes.some(function (p) { return p.paneId === pid; });
                    });
                }).filter(function (row) { return row.length > 0; });

                if (validGrid.length === 0) return false;

                var maxSeq = 0;
                validPanes.forEach(function (p) {
                    var match = p.paneId.match(/^pane-(\d+)$/);
                    if (match) { var seq = parseInt(match[1]); if (seq > maxSeq) maxSeq = seq; }
                });

                this.store.activePanes = validPanes;
                this.store.paneGrid = validGrid;
                this.store._paneSeq = maxSeq;

                if (Array.isArray(layout.rowSizes) && layout.rowSizes.length === validGrid.length) {
                    this.store.rowSizes = layout.rowSizes.slice();
                    this.store.colSizes = {};
                    validGrid.forEach(function (row, idx) {
                        var saved = layout.colSizes && layout.colSizes[idx];
                        if (Array.isArray(saved) && saved.length === row.length) {
                            this.store.colSizes[idx] = saved.slice();
                        } else {
                            this.store._recalcSizes();
                            return;
                        }
                    }.bind(this));
                } else {
                    this.store._recalcSizes();
                }

                var focusedId = layout.focusedPaneId;
                if (focusedId && this.store.getPaneById(focusedId)) {
                    this.store.focusedPaneId = focusedId;
                } else if (validPanes.length > 0) {
                    this.store.focusedPaneId = validPanes[0].paneId;
                }

                var primaryPane = this.store.getFocusedPane();
                if (primaryPane) {
                    this.store.sessionId = primaryPane.sessionId;
                    this.store.cliType = primaryPane.cliType || this.store.cliType;
                    this.store.sessionTitle = primaryPane.title || '';
                    this.store.messages = [];
                    this.store.messagesVersion++;
                    EABridge.loadHistory(primaryPane.sessionId, primaryPane.cliType || this.store.cliType);
                }
                return true;
            } catch (e) {
                return false;
            }
        },
        _restoreLastSession(currentSessionId, currentCliType) {
            if (!currentSessionId || EAIsProvisionalSessionId(currentSessionId)) return;
            var cliType = currentCliType || this.store.cliType || 'CLAUDE';
            this.store.cliType = cliType;
            var pane = this.store.addPane(currentSessionId, cliType, '');
            this.store.sessionId = currentSessionId;
            this.store.sessionTitle = '';
            this.store.messages = [];
            this.store.messagesVersion++;
            EABridge.loadHistory(currentSessionId, cliType);
        },
        _savePaneLayout() {
            var panes = this.store.activePanes;
            var grid = this.store.paneGrid;
            if (panes.length === 0) {
                EABridge.savePaneLayout('');
                return;
            }
            var layout = {
                panes: panes.map(function (p) {
                    return { paneId: p.paneId, sessionId: p.sessionId, cliType: p.cliType, title: p.title || '' };
                }),
                grid: grid,
                focusedPaneId: this.store.focusedPaneId,
                rowSizes: this.store.rowSizes.slice(),
                colSizes: JSON.parse(JSON.stringify(this.store.colSizes))
            };
            EABridge.savePaneLayout(JSON.stringify(layout));
        }
    }
});
