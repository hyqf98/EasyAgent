/**
 * JCEF Java-JavaScript 双向通信桥。
 * <p>
 * 封装 Java 后端与前端 Vue3 应用之间的消息传递。
 * JS -> Java 通过 cefQuery 发送 JSON 消息，
 * Java -> JS 通过 window.__ea_onXxx 全局回调函数推送事件。
 * </p>
 *
 * @namespace EABridge
 */
window.EABridge = {
    _queryId: 0,

    /**
     * 初始化通信桥。注册 Java -> JS 的全局回调函数。
     */
    init() {
        window.__ea_onThemeChanged = (data) => {
            EATheme.apply(data.isDark);
        };

        window.__ea_onHistoryLoaded = (data) => {
            if (window.EAStore) {
                if (EAStore.sessionId === data.sessionId || !EAStore.isLoading) {
                    EAStore.loadHistory(data);
                } else {
                    EAStore.cacheBackendData(data);
                }
            }
        };

        window.__ea_onStreamEvent = (event) => {
            if (window.EAStore) {
                EAStore.handleStreamEvent(event);
            }
        };

        window.__ea_onStreamComplete = (data) => {
            if (window.EAStore) {
                EAStore.setStreaming(false, data && data.sessionId ? data.sessionId : null);
            }
        };

        window.__ea_onSessionCreated = (data) => {
            if (window.EAStore && data.cliType) {
                EAStore.cliType = data.cliType;
            }
        };

        window.__ea_onSessionList = (data) => {
            if (window.__eaDrawerResolve) {
                window.__eaDrawerResolve(data.sessions || data || []);
                window.__eaDrawerResolve = null;
            }
            window.dispatchEvent(new CustomEvent('ea-session-list', {
                detail: { sessions: data.sessions || data || [] }
            }));
        };

        window.__ea_onAvailableCLIs = (data) => {
            window.dispatchEvent(new CustomEvent('ea-available-clis', {
                detail: { clis: data }
            }));
        };

        window.__ea_onStateRestored = (data) => {
            if (window.EAStore) {
                EAStore.restoreAllPendingQueues(data.pendingQueues || []);
                if (data.retryMaxCount !== undefined) {
                    EAStore.retryMaxCount = data.retryMaxCount;
                    EAStore.retryTimeoutMs = data.retryTimeoutMs || 0;
                }
            }
            window.dispatchEvent(new CustomEvent('ea-state-restored', { detail: data }));
        };

        window.__ea_onRetryConfig = (data) => {
            if (window.EAStore) {
                EAStore.retryMaxCount = data.retryMaxCount;
                EAStore.retryTimeoutMs = data.retryTimeoutMs || 0;
            }
        };

        window.__ea_onModels = (data) => {
            if (window.EAStore) {
                var models = Array.isArray(data) ? data : (data.models || []);
                var defaults = (data && data.defaultModels) ? data.defaultModels : null;
                var map = {};
                var cliMap = {};
                models.forEach(function (m) {
                    if (m.modelId && m.contextWindow) {
                        map[m.modelId] = m.contextWindow;
                    }
                    if (m.cliType) {
                        if (!cliMap[m.cliType]) cliMap[m.cliType] = [];
                        cliMap[m.cliType].push(m);
                    }
                });
                EAStore.modelContextMap = map;
                EAStore.modelsList = models;
                if (defaults) {
                    EAStore.defaultModels = defaults;
                }
            }
            window.dispatchEvent(new CustomEvent('ea-models-loaded', { detail: Array.isArray(data) ? data : (data.models || []) }));
        };

        window.__ea_onCliModels = (data) => {
            if (window.EAStore) {
                var cliModels = Array.isArray(data) ? data : (data && data.models ? data.models : []);
                var merged = (EAStore.modelsList || []).slice();
                var existingIds = {};
                merged.forEach(function (m) {
                    if (m && m.modelId) {
                        existingIds[m.modelId] = true;
                    }
                });
                cliModels.forEach(function (model) {
                    if (model && model.modelId && !existingIds[model.modelId]) {
                        merged.push(model);
                        existingIds[model.modelId] = true;
                    }
                    if (model && model.modelId && model.contextWindow) {
                        EAStore.modelContextMap[model.modelId] = model.contextWindow;
                    }
                });
                EAStore.modelsList = merged;
            }
            window.dispatchEvent(new CustomEvent('ea-cli-models-loaded', { detail: data }));
        };

        window.__ea_onInsertReferences = (data) => {
            window.dispatchEvent(new CustomEvent('ea-insert-file-references', {
                detail: { references: data || [] }
            }));
        };

        window.__ea_onFileReferenceCandidates = (data) => {
            window.dispatchEvent(new CustomEvent('ea-file-reference-candidates', {
                detail: data || {}
            }));
        };

        window.__ea_onSessionsDeleted = (data) => {
            window.dispatchEvent(new CustomEvent('ea-sessions-deleted', {
                detail: data || {}
            }));
        };

        window.__ea_onSlashCommands = (data) => {
            window.dispatchEvent(new CustomEvent('ea-slash-commands', {
                detail: data || {}
            }));
        };

        window.__ea_onSlashCommandExecuted = (data) => {
            window.dispatchEvent(new CustomEvent('ea-slash-command-executed', {
                detail: data || {}
            }));
        };

        if (window.cefQuery) {
            this.send('pageReady');
        }
    },

    /**
     * 向 Java 后端发送消息。
     *
     * @param {string} action - 动作名称
     * @param {Object} [data] - 附加数据对象
     */
    send(action, data) {
        const payload = Object.assign({ action: action }, data || {});
        const msg = JSON.stringify(payload);
        if (window.cefQuery) {
            window.cefQuery({ request: msg, onSuccess: () => {}, onFailure: () => {} });
        }
    },

    /**
     * 请求加载所有 CLI 类型的会话列表。
     */
    listAllSessions() {
        this.send('listAllSessions');
    },

    /**
     * 请求加载指定 CLI 类型的会话列表。
     *
     * @param {string} cliType - CLI 类型名称
     */
    listSessions(cliType) {
        this.send('listSessions', { cliType: cliType });
    },

    /**
     * 请求加载历史会话消息。
     * <p>
     * 如果前端已有缓存，直接使用缓存数据通知 store，跳过后端请求。
     * 否则设置加载状态并向后端请求数据。
     * </p>
     *
     * @param {string} sessionId - 会话 ID
     * @param {string} cliType - CLI 类型名称
     */
    loadHistory(sessionId, cliType) {
        if (window.EAStore) {
            EAStore._saveCurrentToCache();
            EAStore.setLoading(sessionId);
            if (EAStore.isSessionCached(sessionId)) {
                EAStore.loadHistory({ sessionId: sessionId, messages: [] });
                return;
            }
            EAStore.sessionId = sessionId;
            EAStore.messages = [];
            EAStore.messagesVersion++;
        }
        this.send('loadHistory', { sessionId: sessionId, cliType: cliType });
    },

    /**
     * 发送用户消息到 AI Provider。
     *
     * @param {string} text - 用户输入的文本内容
     * @param {string} [modelId] - 可选的模型 ID
     */
    sendMessage(text, modelId, fileReferences) {
        var sid = window.EAStore ? EAStore.sessionId : null;
        var data = { text: text, cliType: window.EAStore ? EAStore.cliType : 'CLAUDE', sessionId: sid };
        if (modelId) data.modelId = modelId;
        if (fileReferences && fileReferences.length > 0) data.fileReferences = fileReferences;
        this.send('sendMessage', data);
    },

    /**
     * 停止指定会话的 AI 生成。
     */
    stopGeneration() {
        var sid = window.EAStore ? EAStore.sessionId : null;
        this.send('stopGeneration', { sessionId: sid });
    },

    /**
     * 请求 Java 端推送当前主题状态。
     */
    getTheme() {
        this.send('getTheme');
    },

    /**
     * 批量删除指定会话。
     *
     * @param {string[]} sessionIds - 要删除的会话 ID 数组
     */
    deleteSessions(sessionIds) {
        this.send('deleteSessions', { sessionIds: sessionIds.join(',') });
    },

    /**
     * 保存指定会话的待发送队列到后端持久化。
     *
     * @param {string} sessionId - 会话 ID
     * @param {string} pendingQueueJson - 待发送队列的 JSON 字符串
     */
    savePendingQueue(sessionId, pendingQueueJson) {
        this.send('savePendingQueue', { sessionId: sessionId, pendingQueue: pendingQueueJson });
    },

    /**
     * 获取 AI 重试策略配置。
     */
    getRetryConfig() {
        this.send('getRetryConfig');
    },

    /**
     * 保存 AI 重试策略配置。
     *
     * @param {number} retryMaxCount - 最大重试次数
     * @param {number} retryTimeoutMs - 单次执行超时（毫秒）
     */
     saveRetryConfig(retryMaxCount, retryTimeoutMs) {
         this.send('saveRetryConfig', { retryMaxCount: retryMaxCount, retryTimeoutMs: retryTimeoutMs });
     },

     /**
      * 获取模型配置列表。
      */
     getModels() {
         this.send('getModels');
     },

     /**
      * 从远程同步最新的模型配置。
      */
     syncModels() {
         this.send('syncModels');
     },

     /**
      * 保存编辑后的模型配置。
      *
      * @param {string} modelsJson - 模型配置 JSON 字符串
      */
     saveModels(modelsJson) {
         this.send('saveModels', { models: modelsJson });
     },

     /**
      * 查询指定 CLI 的可用模型列表。
      *
      * @param {string} cliType - CLI 类型名称
      */
     queryCliModels(cliType) {
         this.send('queryCliModels', { cliType: cliType });
     },

     /**
      * 搜索项目内文件引用候选。
      *
      * @param {string} query - 模糊搜索关键字
      * @param {number} limit - 最大返回数量
      * @param {string} requestId - 当前请求 ID
      */
     searchFileReferences(query, limit, requestId) {
         this.send('searchFileReferences', {
             query: query || '',
             limit: limit || 12,
             requestId: requestId || ''
         });
     },

     /**
      * 根据路径解析一个完整的文件引用。
      *
      * @param {string} path - 文件绝对路径
      */
     resolveFileReference(path) {
         this.send('resolveFileReference', { path: path });
     },

     /**
      * 保存剪贴板图片到项目临时目录，并作为引用回填到输入框。
      *
      * @param {string} dataUrl - 图片 data URL
      * @param {string} fileName - 建议文件名
      */
     saveClipboardImage(dataUrl, fileName) {
         this.send('saveClipboardImage', {
             dataUrl: dataUrl,
             fileName: fileName
         });
     },

     /**
      * 打开指定 AI 文件编辑的 diff。
      *
      * @param {string} editId - 编辑 ID
      */
     openFileEditDiff(editId) {
         this.send('openFileEditDiff', { editId: editId });
     },

     /**
      * 回撤指定 AI 文件编辑。
      *
      * @param {string} editId - 编辑 ID
      */
     revertFileEdit(editId) {
         this.send('revertFileEdit', { editId: editId });
     },

     /**
      * 获取当前 CLI 的斜杠命令列表。
      *
      * @param {string} cliType - CLI 类型名称
      * @param {string} requestId - 请求 ID
      */
     getSlashCommands(cliType, requestId) {
         this.send('getSlashCommands', {
             cliType: cliType,
             requestId: requestId || ''
         });
     },

     /**
      * 执行一个斜杠命令。
      *
      * @param {string} cliType - CLI 类型名称
      * @param {string} rawText - 原始命令文本
      * @param {string} requestId - 请求 ID
      */
     executeSlashCommand(cliType, rawText, requestId) {
         this.send('executeSlashCommand', {
             cliType: cliType,
             rawText: rawText,
             requestId: requestId || ''
         });
     }
 };
