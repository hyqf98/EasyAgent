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
            window.dispatchEvent(new CustomEvent('ea-stream-complete', {
                detail: data || {}
            }));
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
                var models = [];
                var levelsMap = {};
                var defaultInfoMap = {};

                if (data && data.cliGroups) {
                    var groups = data.cliGroups;
                    for (var cliKey in groups) {
                        if (!groups.hasOwnProperty(cliKey)) continue;
                        var group = groups[cliKey];
                        var groupModels = group.models || [];
                        groupModels.forEach(function (m) {
                            m.cliType = cliKey;
                            models.push(m);
                        });
                        if (group.reasoningLevels && group.reasoningLevels.length > 0) {
                            levelsMap[cliKey] = group.reasoningLevels;
                        }
                        if (group.defaultModelInfo) {
                            defaultInfoMap[cliKey] = group.defaultModelInfo;
                        }
                    }
                }

                var map = {};
                models.forEach(function (m) {
                    if (m.modelId && m.contextWindow) {
                        map[m.modelId] = m.contextWindow;
                    }
                });
                EAStore.modelContextMap = map;
                EAStore.modelsList = models;
                EAStore.reasoningLevelsMap = levelsMap;
                EAStore.defaultModelInfoMap = defaultInfoMap;
            }
            window.dispatchEvent(new CustomEvent('ea-models-loaded', { detail: models }));
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

        window.__ea_onCliConfigs = (data) => {
            window.dispatchEvent(new CustomEvent('ea-cli-configs', {
                detail: data || {}
            }));
        };

        window.__ea_onCliConfigsSaved = (data) => {
            window.dispatchEvent(new CustomEvent('ea-cli-configs-saved', {
                detail: data || {}
            }));
        };

        window.__ea_onMcpConfigs = (data) => {
            window.dispatchEvent(new CustomEvent('ea-mcp-configs', {
                detail: data || {}
            }));
        };

        window.__ea_onMcpSaved = (data) => {
            window.dispatchEvent(new CustomEvent('ea-mcp-saved', {
                detail: data || {}
            }));
        };

        window.__ea_onMcpTestConnected = (data) => {
            window.dispatchEvent(new CustomEvent('ea-mcp-test-connected', {
                detail: data || {}
            }));
        };

        window.__ea_onMcpTools = (data) => {
            window.dispatchEvent(new CustomEvent('ea-mcp-tools', {
                detail: data || {}
            }));
        };

        window.__ea_onMcpToolResult = (data) => {
            window.dispatchEvent(new CustomEvent('ea-mcp-tool-result', {
                detail: data || {}
            }));
        };

        window.__ea_onSkills = (data) => {
            window.dispatchEvent(new CustomEvent('ea-skills', {
                detail: data || []
            }));
        };

        window.__ea_onSkillInstalled = (data) => {
            window.dispatchEvent(new CustomEvent('ea-skill-installed', {
                detail: data || {}
            }));
        };

        window.__ea_onSkillDeleted = (data) => {
            window.dispatchEvent(new CustomEvent('ea-skill-deleted', {
                detail: data || {}
            }));
        };

        window.__ea_onSkillContent = (data) => {
            window.dispatchEvent(new CustomEvent('ea-skill-content', {
                detail: data || {}
            }));
        };

        // Plan mode callbacks
        window.__ea_onPlanCreated = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-created', { detail: data || {} }));
        };

        window.__ea_onPlanList = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-list', { detail: data || {} }));
        };

        window.__ea_onPlanDetail = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-detail', { detail: data || {} }));
        };

        window.__ea_onPlanTaskUpdated = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-task-updated', { detail: data || {} }));
        };

        window.__ea_onPlanTaskStatus = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-task-status', { detail: data || {} }));
        };

        window.__ea_onPlanDeleted = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-deleted', { detail: data || {} }));
        };

        window.__ea_onPlanConfig = (data) => {
            if (window.EAStore && data) {
                EAStore.planConcurrentTasks = data.planConcurrentTasks || 1;
            }
            window.dispatchEvent(new CustomEvent('ea-plan-config', { detail: data || {} }));
        };

        window.__ea_onPlanConfigSaved = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-config-saved', { detail: data || {} }));
        };

        window.__ea_onPlanOverviewUpdated = (data) => {
            window.dispatchEvent(new CustomEvent('ea-plan-overview-updated', { detail: data || {} }));
        };

        // ========== Hot Reload (dev only) ==========
        if (window.__EA_DEV_MODE__) {
            this._initHotReload();
        }

        if (window.cefQuery) {
            this.send('pageReady');
        }
    },

    _hotReloadLastTs: '',
    _hotReloadTimer: null,

    _initHotReload() {
        var self = this;
        var baseUrl = window.location.href.substring(0, window.location.href.lastIndexOf('/') + 1);
        var markerUrl = baseUrl + '.hotreload';
        function poll() {
            var xhr = new XMLHttpRequest();
            xhr.open('GET', markerUrl + '?t=' + Date.now(), true);
            xhr.onload = function () {
                if (xhr.status === 200) {
                    var ts = xhr.responseText.trim();
                    if (self._hotReloadLastTs && self._hotReloadLastTs !== ts) {
                        console.log('[hot-reload] Change detected, reloading...');
                        location.reload();
                        return;
                    }
                    self._hotReloadLastTs = ts;
                }
                self._hotReloadTimer = setTimeout(poll, 1000);
            };
            xhr.onerror = function () {
                self._hotReloadTimer = setTimeout(poll, 3000);
            };
            xhr.send();
        }
        poll();
    },

    /**
     * 向 Java 后端发送消息。
     *
     * @param {string} action - 动作名称
     * @param {Object} [data] - 附加数据对象
     */
    send(action, data) {
        var payload;
        if (typeof action === 'object' && action !== null) {
            payload = action;
        } else {
            payload = Object.assign({ action: action }, data || {});
        }
        var msg = JSON.stringify(payload);
        if (window.cefQuery) {
            window.cefQuery({ request: msg, onSuccess: function() {}, onFailure: function() {} });
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
     * @param {boolean} [forceReload] - 是否强制从后端重新加载
     */
    loadHistory(sessionId, cliType, forceReload) {
        if (window.EAStore) {
            EAStore._saveCurrentToCache();
            EAStore.setLoading(sessionId);
            if (!forceReload && EAStore.isSessionCached(sessionId)) {
                EAStore.loadHistory({ sessionId: sessionId, messages: [] });
                return;
            }
            EAStore.sessionId = sessionId;
            EAStore.messages = [];
            EAStore.messagesVersion++;
        }
        this.send('loadHistory', { sessionId: sessionId, cliType: cliType, forceReload: !!forceReload });
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
        var reasoningLevel = window.EAStore ? EAStore.selectedReasoningLevel : '';
        if (reasoningLevel) data.reasoningLevel = reasoningLevel;
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
      * @param {Object} fileEdit - 文件编辑元数据
      */
     openFileEditDiff(fileEdit) {
         if (!fileEdit) {
             return;
         }
         this.send('openFileEditDiff', {
             editId: fileEdit.editId || '',
             toolCallId: fileEdit.toolCallId || '',
             path: fileEdit.path || ''
         });
     },

     /**
      * 回撤指定 AI 文件编辑。
      *
      * @param {Object} fileEdit - 文件编辑元数据
      */
     revertFileEdit(fileEdit) {
         if (!fileEdit) {
             return;
         }
         this.send('revertFileEdit', {
             editId: fileEdit.editId || '',
             toolCallId: fileEdit.toolCallId || '',
             path: fileEdit.path || ''
         });
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
      },

      /**
       * 获取 CLI 配置数据。
       */
      getCliConfigs() {
          this.send('getCliConfigs');
      },

      /**
       * 保存 CLI 配置。
       *
       * @param {string} cliType - CLI 类型名称
       * @param {Object} config - 配置数据
       */
      saveCliConfigs(cliType, config) {
          var data = { cliType: cliType };
          if (cliType === 'CLAUDE') data.claude = config;
          else if (cliType === 'OPENCODE') data.opencode = config;
          else if (cliType === 'CODEX') data.codex = config;
          this.send('saveCliConfigs', data);
      },

      /**
       * 保存 CLI 配置档案（新增或更新）。
       *
       * @param {string} cliType - CLI 类型名称
       * @param {Object} profile - 档案数据
       */
      saveCliProfile(cliType, profile) {
          this.send('saveCliProfile', { cliType: cliType, profile: profile });
      },

      /**
       * 删除 CLI 配置档案。
       *
       * @param {string} cliType - CLI 类型名称
       * @param {string} profileId - 档案 ID
       */
      deleteCliProfile(cliType, profileId) {
          this.send('deleteCliProfile', { cliType: cliType, profileId: profileId });
      },

      /**
       * 应用 CLI 配置档案（切换到指定档案）。
       *
       * @param {string} cliType - CLI 类型名称
       * @param {string} profileId - 档案 ID
       */
       applyCliProfile(cliType, profileId) {
           this.send('applyCliProfile', { cliType: cliType, profileId: profileId });
       },

       /**
        * 获取 Skills 技能列表。
        *
        * @param {string} cliType - CLI 类型名称
        */
       getSkills(cliType) {
           this.send('getSkills', { cliType: cliType });
       },

       /**
        * 从 GitHub 安装 Skill。
        *
        * @param {string} cliType - CLI 类型名称
        * @param {string} githubUrl - GitHub 仓库地址
        * @param {string} [skillPath] - skill 在仓库中的路径
        * @param {string} [scope] - 安装作用域：user 或 project
        */
       installSkill(cliType, githubUrl, skillPath, scope) {
           this.send('installSkill', {
               cliType: cliType,
               githubUrl: githubUrl,
               skillPath: skillPath || '',
               scope: scope || 'user'
           });
       },

       /**
        * 删除指定 Skill。
        *
        * @param {string} cliType - CLI 类型名称
        * @param {string} skillName - skill 名称
        * @param {string} skillPath - skill 目录路径
        */
       deleteSkill(cliType, skillName, skillPath) {
           this.send('deleteSkill', {
               cliType: cliType,
               skillName: skillName,
               skillPath: skillPath
           });
       },

       /**
        * 读取 Skill 的完整 SKILL.md 内容。
        *
        * @param {string} skillPath - skill 目录路径
        */
       readSkillContent(skillPath) {
            this.send('readSkillContent', { skillPath: skillPath });
       },

       // ========== Plan Mode APIs ==========

       createPlan(planName, description, cliType, minTaskCount) {
           this.send('createPlan', {
               planName: planName,
               description: description,
               cliType: cliType || 'CLAUDE',
               minTaskCount: minTaskCount || 5
           });
       },

       listPlans() {
           this.send('listPlans');
       },

       getPlanDetail(planId) {
           this.send('getPlanDetail', { planId: planId });
       },

       updatePlan(planId, planName, description) {
           this.send('updatePlan', {
               planId: planId,
               planName: planName,
               description: description
           });
       },

       deletePlan(planId) {
           this.send('deletePlan', { planId: planId });
       },

       updatePlanTask(planId, taskId, updates) {
           var data = Object.assign({ planId: planId, taskId: taskId }, updates || {});
           this.send('updatePlanTask', data);
       },

       executePlanTask(planId, taskId) {
           this.send('executePlanTask', { planId: planId, taskId: taskId });
       },

       stopPlanTask(planId, taskId) {
           this.send('stopPlanTask', { planId: planId, taskId: taskId });
       },

       aiEditTasks(planId, instruction) {
           this.send('aiEditTasks', { planId: planId, instruction: instruction });
       },

        savePlanTasks(planId, tasksJson) {
            this.send('savePlanTasks', { planId: planId, tasksJson: tasksJson });
        },

        startPlanSplit(planId) {
            this.send('startPlanSplit', { planId: planId });
        },

        getPlanConfig() {
           this.send('getPlanConfig');
       },

       savePlanConfig(planConcurrentTasks) {
           this.send('savePlanConfig', { planConcurrentTasks: planConcurrentTasks });
       }
   };
