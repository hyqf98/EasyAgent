/**
 * 计划看板视图组件。
 * <p>
 * 支持计划 CRUD、任务拆分（collect 视图）、看板拖拽（kanban 视图）、
 * 并发控制、自动开始、停止/恢复任务等。
 * </p>
 *
 * @component plan-view
 */

var EA_TASK_STATUS_VALUES = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'STOPPED'];
var EA_TASK_PRIORITY_VALUES = ['HIGH', 'MEDIUM', 'LOW'];
var EA_CLI_LABELS = { CLAUDE: 'Claude', OPENCODE: 'OpenCode', CODEX: 'Codex' };
var EA_STATUS_LABELS = {
    DRAFT: 'plan.status.draft',
    TASK_SPLITTING: 'plan.status.task_splitting',
    KANBAN: 'plan.status.kanban',
    COMPLETED: 'plan.status.completed'
};

window.EARegisterComponent('plan-view', 'PlanView', {
    data() {
        return {
            viewMode: 'list',
            plans: [],
            currentPlan: null,
            currentTasks: [],
            taskDetailView: null,
            autoStart: true,
            toastMessage: '',
            toastTimer: null,

            showTaskDialog: false,
            isAddTask: true,
            editTaskId: null,
            editTaskTitle: '',
            editTaskDesc: '',
            editTaskPriority: 'MEDIUM',
            editTaskCliType: '',
            editTaskModelId: '',
            showTaskCliDropdown: false,
            showTaskModelDropdown: false,

            showDeleteDialog: false,
            deleteTargetPlan: null,

            showCreateDialog: false,
            newPlanName: '',
            newPlanDesc: '',
            newPlanCli: 'CLAUDE',
            newPlanMinTasks: 5,

            taskPanelCollapsed: false,
            taskPanelWidth: 280,
            showScrollBottom: false,
            splitPending: false,
            enteredFromKanban: false
        };
    },
    created() {
        this._dragging = false;
        this._sortableInstances = null;
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },

        pendingTasks() {
            return this.currentTasks
                .filter(function (t) { return (t.status || '').toUpperCase() === 'PENDING'; })
                .sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });
        },
        runningTasks() {
            var tasks = this.currentTasks.filter(function (t) {
                var s = (t.status || '').toUpperCase();
                return s === 'RUNNING' || s === 'QUEUED' || s === 'STOPPED';
            });
            tasks.sort(function (a, b) {
                var sa = (a.status || '').toUpperCase();
                var sb = (b.status || '').toUpperCase();
                if (sa === 'RUNNING' && sb !== 'RUNNING') return -1;
                if (sb === 'RUNNING' && sa !== 'RUNNING') return 1;
                return (a.sortOrder || 0) - (b.sortOrder || 0);
            });
            return tasks;
        },
        completedTasks() {
            return this.currentTasks
                .filter(function (t) { return (t.status || '').toUpperCase() === 'COMPLETED'; })
                .sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });
        },
        failedTasks() {
            return this.currentTasks
                .filter(function (t) { return (t.status || '').toUpperCase() === 'FAILED'; })
                .sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });
        },

        taskDialogCliLabel() {
            return EA_CLI_LABELS[this.editTaskCliType] || EA_CLI_LABELS[this.currentPlanCliType] || 'Claude';
        },
        taskDialogModelLabel() {
            if (!this.editTaskModelId) return this.i18n.t('plan.task.modelPlaceholder') || '默认';
            var models = this.taskDialogModels;
            for (var i = 0; i < models.length; i++) {
                if (models[i].modelId === this.editTaskModelId) {
                    return models[i].displayName || models[i].modelId;
                }
            }
            return this.editTaskModelId;
        },
        taskDialogModels() {
            var list = this.store.modelsList || [];
            var cliType = this.editTaskCliType || this.currentPlanCliType;
            return list.filter(function (m) { return m.cliType === cliType; });
        },
        currentPlanCliType() {
            return this.currentPlan ? (this.currentPlan.cliType || 'CLAUDE') : 'CLAUDE';
        }
    },
    mounted() {
        this._onPlanCreated = function (e) {
            var detail = e.detail;
            if (detail && detail.planId) {
                this.plans.unshift(detail);
            }
            this.showToast(this.i18n.t('plan.toast.created') || '计划已创建');
        }.bind(this);

        this._onPlanList = function (e) {
            var detail = e.detail;
            if (Array.isArray(detail)) {
                this.plans = detail;
            } else if (detail && Array.isArray(detail.plans)) {
                this.plans = detail.plans;
            } else {
                this.plans = [];
            }
        }.bind(this);

        this._onPlanDetail = function (e) {
            var detail = e.detail || {};
            if (detail.plan) {
                var incomingTasks = (detail.tasks || (detail.plan.tasks || [])).map(this.normalizeTask);
                this.currentPlan = detail.plan;

                if (this.currentPlan.status === 'KANBAN') {
                    this.viewMode = 'kanban';
                    this.splitPending = false;
                } else if (this.currentPlan.status === 'TASK_SPLITTING' || this.currentPlan.status === 'DRAFT') {
                    this.viewMode = 'collect';
                }

                if (this._dragging || incomingTasks.length === 0) {
                    this._loadPlanSessionHistory();
                    return;
                }

                var hasActive = this.currentTasks.some(function (t) {
                    var s = (t.status || '').toUpperCase();
                    return s === 'QUEUED' || s === 'RUNNING';
                });
                if (hasActive) {
                    for (var i = 0; i < incomingTasks.length; i++) {
                        var found = false;
                        for (var j = 0; j < this.currentTasks.length; j++) {
                            if (this.currentTasks[j].taskId === incomingTasks[i].taskId) {
                                var localStatus = (this.currentTasks[j].status || '').toUpperCase();
                                if (localStatus === 'QUEUED' || localStatus === 'RUNNING') {
                                    found = true;
                                    break;
                                }
                                Object.assign(this.currentTasks[j], incomingTasks[i]);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            this.currentTasks.push(incomingTasks[i]);
                        }
                    }
                } else {
                    this.currentTasks = incomingTasks;
                }

                this._loadPlanSessionHistory();
            }
        }.bind(this);

        this._onPlanTaskUpdated = function (e) {
            var detail = e.detail;
            if (!detail) return;
            var updatedTaskId = detail.taskId;
            if (!updatedTaskId) {
                var tasks = detail.tasks;
                if (Array.isArray(tasks) && this.currentPlan) {
                    EABridge.getPlanDetail(this.currentPlan.planId);
                }
                return;
            }
            var found = false;
            for (var i = 0; i < this.currentTasks.length; i++) {
                if (this.currentTasks[i].taskId === updatedTaskId) {
                    var localStatus = (this.currentTasks[i].status || '').toUpperCase();
                    if (localStatus === 'QUEUED' || localStatus === 'RUNNING') {
                        found = true;
                        break;
                    }
                    var incoming = this.normalizeTask(detail);
                    if (incoming && incoming.status) {
                        var preservedSortOrder = this.currentTasks[i].sortOrder;
                        Object.assign(this.currentTasks[i], incoming);
                        if (this._dragging) {
                            this.currentTasks[i].sortOrder = preservedSortOrder;
                        }
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                var normalized = this.normalizeTask(detail);
                if (normalized && normalized.status) {
                    this.currentTasks.push(normalized);
                }
            }
        }.bind(this);

        this._onPlanTaskStatus = function (e) {
            var detail = e.detail || {};
            var taskId = detail.taskId;
            if (!taskId) return;
            var status = detail.status;
            if (!status) return;
            status = String(status).toUpperCase();
            if (status !== 'PENDING' && status !== 'RUNNING' && status !== 'QUEUED'
                && status !== 'COMPLETED' && status !== 'FAILED' && status !== 'STOPPED') return;
            for (var i = 0; i < this.currentTasks.length; i++) {
                if (this.currentTasks[i].taskId === taskId) {
                    this.currentTasks[i].status = status;
                    if (detail.executeSessionId) {
                        this.currentTasks[i].executeSessionId = detail.executeSessionId;
                    }
                    break;
                }
            }
        }.bind(this);

        this._onPlanDeleted = function (e) {
            var detail = e.detail || {};
            var planId = detail.planId;
            if (planId) {
                this.plans = this.plans.filter(function (p) { return p.planId !== planId; });
            }
            if (this.currentPlan && this.currentPlan.planId === planId) {
                this.currentPlan = null;
                this.currentTasks = [];
                this.viewMode = 'list';
            }
        }.bind(this);

        this._onStreamComplete = function (e) {
            var detail = e.detail || {};
            var sessionId = detail.sessionId;
            if (!sessionId || !this.taskDetailView) return;
            if (this.taskDetailView.executeSessionId === sessionId) {
                if (this.currentPlan) {
                    EABridge.getPlanDetail(this.currentPlan.planId);
                }
            }
        }.bind(this);

        this._onPlanOverviewUpdated = function (e) {
            var detail = e.detail || {};
            if (detail.planId && this.currentPlan && this.currentPlan.planId === detail.planId) {
                this.currentPlan.executionOverview = detail.executionOverview || '';
            }
        }.bind(this);

        this._onPlanSplitResult = function (e) {
            var detail = e.detail || {};
            if (!detail.planId || !this.currentPlan || this.currentPlan.planId !== detail.planId) return;
            var tasks = detail.tasks;
            if (!Array.isArray(tasks) || tasks.length === 0) return;
            this.currentTasks = tasks.map(this.normalizeTask);
            this.taskPanelCollapsed = false;
        }.bind(this);

        window.addEventListener('ea-plan-created', this._onPlanCreated);
        window.addEventListener('ea-plan-list', this._onPlanList);
        window.addEventListener('ea-plan-detail', this._onPlanDetail);
        window.addEventListener('ea-plan-task-updated', this._onPlanTaskUpdated);
        window.addEventListener('ea-plan-task-status', this._onPlanTaskStatus);
        window.addEventListener('ea-plan-deleted', this._onPlanDeleted);
        window.addEventListener('ea-stream-complete', this._onStreamComplete);
        window.addEventListener('ea-plan-overview-updated', this._onPlanOverviewUpdated);
        window.addEventListener('ea-plan-split-result', this._onPlanSplitResult);

        EABridge.listPlans();
    },
    beforeUnmount() {
        if (this._onPlanCreated) window.removeEventListener('ea-plan-created', this._onPlanCreated);
        if (this._onPlanList) window.removeEventListener('ea-plan-list', this._onPlanList);
        if (this._onPlanDetail) window.removeEventListener('ea-plan-detail', this._onPlanDetail);
        if (this._onPlanTaskUpdated) window.removeEventListener('ea-plan-task-updated', this._onPlanTaskUpdated);
        if (this._onPlanTaskStatus) window.removeEventListener('ea-plan-task-status', this._onPlanTaskStatus);
        if (this._onPlanDeleted) window.removeEventListener('ea-plan-deleted', this._onPlanDeleted);
        if (this._onStreamComplete) window.removeEventListener('ea-stream-complete', this._onStreamComplete);
        if (this._onPlanOverviewUpdated) window.removeEventListener('ea-plan-overview-updated', this._onPlanOverviewUpdated);
        if (this._onPlanSplitResult) window.removeEventListener('ea-plan-split-result', this._onPlanSplitResult);
        if (this.toastTimer) clearTimeout(this.toastTimer);
        this._destroySortable();
    },
    updated() {
        if (this.viewMode === 'kanban' && !this._sortableInstances && !this._dragging) {
            this._tryInitSortable();
        }
    },
    watch: {
        'store.messagesVersion'() {
            this.$nextTick(this.scrollToBottom);
        },
        viewMode(val) {
            if (val === 'kanban') {
                this._destroySortable();
                var self = this;
                this.$nextTick(function () {
                    setTimeout(function () {
                        self._tryInitSortable();
                    }, 80);
                });
            } else {
                this._destroySortable();
            }
        }
    },
    methods: {
        normalizeTask(task) {
            if (!task) return task;
            if (task.status) task.status = task.status.toUpperCase();
            if (task.priority) task.priority = task.priority.toUpperCase();
            if (!task.sortOrder && task.sortOrder !== 0) task.sortOrder = 0;
            if (!task.startedAt) task.startedAt = 0;
            if (!task.completedAt) task.completedAt = 0;
            return task;
        },

          _loadPlanSessionHistory() {
              if (!this.currentPlan) return;
              if (this.currentPlan.status === 'KANBAN' && !this.enteredFromKanban) return;
              var sessionId = this.currentPlan.sessionId;
              if (!sessionId || sessionId.startsWith('plan-')) return;
              var cliType = this.currentPlan.cliType || 'CLAUDE';
              EAStore.cliType = cliType;
              delete window.EA_STREAMING_MAP[sessionId];
              delete window.EA_SESSION_CACHE[sessionId];
              this.store.sessionId = sessionId;
              EABridge.loadHistory(sessionId, cliType, true);
          },

        getStatusLabel(status) {
            var key = EA_STATUS_LABELS[status];
            return key ? this.i18n.t(key) : (status || '');
        },

        getCliLabelFromEnum(cliType) {
            return EA_CLI_LABELS[cliType] || cliType || '';
        },

        formatTime(ts) { return EATimeFormat.relative(ts); },

        formatOverview(text) {
            if (!text) return '';
            return text
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/### (✅|❌)/g, '<strong>$1')
                .replace(/(\n### )/g, '<br><br>$1')
                .replace(/\n/g, '<br>')
                .replace(/- 状态: 成功/g, '<span style="color:var(--ea-success,#22c55e)">- 状态: 成功</span>')
                .replace(/- 状态: 失败/g, '<span style="color:var(--ea-danger,#ef4444)">- 状态: 失败</span>');
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

        // ========== Navigation ==========

        onBack() {
            if (this.taskDetailView) {
                this.taskDetailView = null;
                this.store.messages = [];
                this.store.sessionId = null;
                return;
            }
            if (this.enteredFromKanban && this.viewMode === 'collect') {
                this._backToKanban();
                return;
            }
            if (this.viewMode === 'kanban' || this.viewMode === 'collect') {
                this.currentPlan = null;
                this.currentTasks = [];
                this.viewMode = 'list';
                this.enteredFromKanban = false;
                this.store.messages = [];
                this.store.sessionId = null;
                this.store.isStreaming = false;
                this._destroySortable();
                return;
            }
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.isStreaming = false;
            this.store.appMode = 'welcome';
        },

        _backToKanban() {
            this.enteredFromKanban = false;
            this.viewMode = 'kanban';
            this.currentPlan.status = 'KANBAN';
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.isStreaming = false;
            this._destroySortable();
            this.$nextTick(function () {
                setTimeout(function () { this._tryInitSortable(); }.bind(this), 80);
            }.bind(this));
        },

        onReSplit() {
            if (!this.currentPlan) return;
            this.enteredFromKanban = true;
            this.viewMode = 'collect';
            this._destroySortable();
            var sessionId = this.currentPlan.sessionId;
            var cliType = this.currentPlan.cliType || 'CLAUDE';
            EAStore.cliType = cliType;
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.isStreaming = false;
            if (sessionId) {
                this.store.sessionId = sessionId;
                EABridge.loadHistory(sessionId, cliType, true);
            }
        },

        onGoSettings() {
            this.store.pendingOpenSettings = true;
            this.store.appMode = 'welcome';
        },

        onGoHome() {
            this.currentPlan = null;
            this.currentTasks = [];
            this.taskDetailView = null;
            this.viewMode = 'list';
            this._destroySortable();
            this.store.messages = [];
            this.store.sessionId = null;
            this.store.isStreaming = false;
            this.store.lastTokenUsage = null;
            this.store.appMode = 'welcome';
        },

        // ========== Plan CRUD ==========

        onNewPlan() {
            this.newPlanName = '';
            this.newPlanDesc = '';
            this.newPlanCli = 'CLAUDE';
            this.newPlanMinTasks = 5;
            this.showCreateDialog = true;
        },

        onCreatePlan(data) {
            if (!data.planName || !data.planName.trim()) return;
            EABridge.createPlan(data.planName, data.description, data.cliType, data.minTaskCount);
            this.showCreateDialog = false;
        },

        onOpenPlan(plan) {
            this.currentPlan = plan;
            this.currentTasks = (plan.tasks || []).map(this.normalizeTask);
            EABridge.getPlanDetail(plan.planId);
        },

        onStartPlanSplitting(plan) {
            this.currentPlan = plan;
            this.currentPlan.status = 'TASK_SPLITTING';
            this.currentTasks = (plan.tasks || []).map(this.normalizeTask);
            this.viewMode = 'collect';
            var cliType = plan.cliType || 'CLAUDE';
            EAStore.cliType = cliType;
            this.store.messages = [];
            this.store.sessionId = 'plan-' + plan.planId;

            var planName = plan.planName || '';
            var planDesc = plan.description || '';
            var minTasks = plan.minTaskCount || 5;
            var promptParts = [];
            promptParts.push(this.i18n.t('plan.splitPrompt.title') + planName);
            if (planDesc) promptParts.push(this.i18n.t('plan.splitPrompt.content') + planDesc);
            promptParts.push(this.i18n.t('plan.splitPrompt.granularity').replace('{n}', minTasks));
            var promptText = promptParts.join('\n');
            this.store.addUserMessage(promptText, []);
            this.store.beginAssistantTurn();
            this.store.messagesVersion++;
            this.$nextTick(this.scrollToBottom);
            EABridge.startPlanSplit(plan.planId);
        },

        onDeletePlan(planId) {
            var plan = this.plans.find(function (p) { return p.planId === planId; });
            this.deleteTargetPlan = plan || null;
            this.showDeleteDialog = true;
        },

        onCancelDelete() {
            this.showDeleteDialog = false;
            this.deleteTargetPlan = null;
        },

        onConfirmDelete() {
            if (!this.deleteTargetPlan) return;
            EABridge.deletePlan(this.deleteTargetPlan.planId);
            this.showDeleteDialog = false;
            this.deleteTargetPlan = null;
        },

        // ========== Task CRUD ==========

        onAddTask() {
            this.isAddTask = true;
            this.editTaskId = null;
            this.editTaskTitle = '';
            this.editTaskDesc = '';
            this.editTaskPriority = 'MEDIUM';
            this.editTaskCliType = '';
            this.editTaskModelId = '';
            this.showTaskCliDropdown = false;
            this.showTaskModelDropdown = false;
            this.showTaskDialog = true;
        },

        onEditTask(task) {
            this.onOpenTaskDialog(task);
        },

        onOpenTaskDialog(task) {
            this.isAddTask = false;
            this.editTaskId = task.taskId;
            this.editTaskTitle = task.title || '';
            this.editTaskDesc = task.description || '';
            this.editTaskPriority = (task.priority || 'MEDIUM').toUpperCase();
            this.editTaskCliType = task.cliType || '';
            this.editTaskModelId = task.modelId || '';
            this.showTaskCliDropdown = false;
            this.showTaskModelDropdown = false;
            this.showTaskDialog = true;
        },

        onSaveTaskDialog() {
            if (!this.editTaskTitle || !this.editTaskTitle.trim()) return;
            if (!this.currentPlan) return;

            if (this.isAddTask) {
                var newTask = {
                    taskId: 'task-' + Date.now() + '-' + Math.random().toString(36).substr(2, 6),
                    title: this.editTaskTitle.trim(),
                    description: this.editTaskDesc || '',
                    priority: this.editTaskPriority,
                    status: 'PENDING',
                    sortOrder: this.currentTasks.length,
                    cliType: this.editTaskCliType || this.currentPlanCliType,
                    modelId: this.editTaskModelId || ''
                };
                this.currentTasks.push(this.normalizeTask(newTask));
                this._persistTasks();
            } else {
                for (var i = 0; i < this.currentTasks.length; i++) {
                    if (this.currentTasks[i].taskId === this.editTaskId) {
                        this.currentTasks[i].title = this.editTaskTitle.trim();
                        this.currentTasks[i].description = this.editTaskDesc || '';
                        this.currentTasks[i].priority = this.editTaskPriority;
                        this.currentTasks[i].cliType = this.editTaskCliType || this.currentPlanCliType;
                        this.currentTasks[i].modelId = this.editTaskModelId || '';
                        break;
                    }
                }
                EABridge.updatePlanTask(this.currentPlan.planId, this.editTaskId, {
                    title: this.editTaskTitle.trim(),
                    description: this.editTaskDesc || '',
                    priority: this.editTaskPriority,
                    cliType: this.editTaskCliType || undefined,
                    modelId: this.editTaskModelId || undefined
                });
            }
            this.showTaskDialog = false;
        },

        onDeleteTask(taskId) {
            this.currentTasks = this.currentTasks.filter(function (t) { return t.taskId !== taskId; });
            if (this.viewMode === 'collect') {
                this._persistTasks();
            }
        },

        onConfirmSplit() {
            if (!this.currentPlan || this.currentTasks.length === 0) return;
            this.splitPending = true;
            var wasFromKanban = this.enteredFromKanban;
            this._persistTasks();
            if (wasFromKanban) {
                this._backToKanban();
                this.splitPending = false;
            }
        },

        selectTaskCli(cli) {
            this.editTaskCliType = cli;
            this.editTaskModelId = '';
            this.showTaskCliDropdown = false;
        },

        selectTaskModel(modelId) {
            this.editTaskModelId = modelId;
            this.showTaskModelDropdown = false;
        },

        _persistTasks() {
            if (!this.currentPlan) return;
            var tasks = this.currentTasks.map(function (t) {
                var status = (t.status || 'PENDING').toUpperCase();
                if (status === 'QUEUED') status = 'PENDING';
                return {
                    taskId: t.taskId,
                    title: t.title,
                    description: t.description || '',
                    priority: t.priority || 'MEDIUM',
                    status: status,
                    sortOrder: t.sortOrder || 0,
                    cliType: t.cliType || '',
                    modelId: t.modelId || ''
                };
            });
            EABridge.savePlanTasks(this.currentPlan.planId, JSON.stringify(tasks));
        },

        // ========== Kanban Drag & Drop ==========

        _tryInitSortable() {
            if (this._sortableInstances || this._dragging) return;
            if (typeof window.Sortable === 'undefined') {
                console.warn('[PlanView] Sortable.js not loaded');
                return;
            }

            var root = this.$el;
            var bodies = root ? root.querySelectorAll('.kanban-column-body') : [];
            if (!bodies || bodies.length === 0) {
                return;
            }

            var self = this;
            var instances = [];

            bodies.forEach(function (body) {
                var sortable = new Sortable(body, {
                    group: 'kanban-tasks',
                    animation: 150,
                    ghostClass: 'sortable-ghost',
                    chosenClass: 'sortable-chosen',
                    dragClass: 'sortable-drag',
                    filter: '.no-drag',
                    preventOnFilter: false,
                    forceFallback: true,
                    fallbackOnBody: true,
                    fallbackTolerance: 3,
                    swapThreshold: 0.5,
                    fallbackClass: 'kanban-fallback',
                    onStart: function () {
                        self._dragging = true;
                    },
                    onEnd: function (evt) {
                        self._dragging = false;
                        if (!evt.item || !evt.from || !evt.to) return;

                        var taskId = evt.item.getAttribute('data-task-id');
                        var fromColumn = evt.from.getAttribute('data-column');
                        var toColumn = evt.to.getAttribute('data-column');

                        self._destroySortable();

                        if (fromColumn === toColumn) {
                            self._reorderInColumnByIndex(fromColumn, taskId, oldIndex, newIndex);
                        } else {
                            self._handleDragEndFromData(taskId, fromColumn, toColumn);
                        }

                        self.$nextTick(function () {
                            self._tryInitSortable();
                        });
                    }
                });
                instances.push(sortable);
            });

            this._sortableInstances = instances;
        },

        _destroySortable() {
            if (this._sortableInstances) {
                this._sortableInstances.forEach(function (s) {
                    if (s && s.destroy) s.destroy();
                });
                this._sortableInstances = null;
            }
        },

        _handleDragEndFromData(taskId, fromColumn, toColumn) {
            if (!taskId || !this.currentPlan || fromColumn === toColumn) return;
            this.onTaskDragEnd(taskId, fromColumn, toColumn);
        },

        _reorderInColumnByIndex(column, taskId, oldIndex, newIndex) {
            if (oldIndex === newIndex) return;
            var statusFilter;
            switch (column) {
                case 'PENDING': statusFilter = 'PENDING'; break;
                case 'RUNNING': statusFilter = 'RUNNING_GROUP'; break;
                case 'COMPLETED': statusFilter = 'COMPLETED'; break;
                case 'FAILED': statusFilter = 'FAILED'; break;
                default: return;
            }
            var tasks;
            if (statusFilter === 'RUNNING_GROUP') {
                tasks = this.runningTasks;
            } else if (statusFilter === 'PENDING') {
                tasks = this.pendingTasks;
            } else if (statusFilter === 'COMPLETED') {
                tasks = this.completedTasks;
            } else {
                tasks = this.failedTasks;
            }
            var movedTask = tasks[oldIndex];
            if (!movedTask) return;
            tasks.splice(oldIndex, 1);
            tasks.splice(newIndex, 0, movedTask);
            for (var i = 0; i < tasks.length; i++) {
                tasks[i].sortOrder = i;
            }
            this._persistTasks();
        },

        onTaskDragEnd(taskId, fromColumn, toColumn) {
            var task = this.currentTasks.find(function (t) { return t.taskId === taskId; });
            if (!task || !this.currentPlan) return;

            var newStatus;
            switch (toColumn) {
                case 'PENDING': newStatus = 'PENDING'; break;
                case 'RUNNING': newStatus = 'QUEUED'; break;
                case 'COMPLETED': newStatus = 'COMPLETED'; break;
                case 'FAILED': newStatus = 'FAILED'; break;
                default: return;
            }

            if (newStatus === 'QUEUED') {
                var concurrentLimit = this.store.planConcurrentTasks || 1;
                var runningCount = this.currentTasks.filter(function (t) {
                    var s = (t.status || '').toUpperCase();
                    return s === 'RUNNING';
                }).length;
                if (runningCount < concurrentLimit) {
                    newStatus = 'RUNNING';
                }
            }

            this._updateTaskStatus(taskId, newStatus, true);
        },

        _updateTaskStatus(taskId, newStatus, fromDrag) {
            var task = this.currentTasks.find(function (t) { return t.taskId === taskId; });
            if (!task) return;

            var self = this;
            var oldStatus = (task.status || '').toUpperCase();
            task.status = newStatus;

            if (newStatus === 'PENDING') {
                task.completedAt = 0;
                task.startedAt = 0;
                this._assignSortOrder(task, taskId, 'PENDING', fromDrag);
            }
            if (newStatus === 'STOPPED') {
                task.completedAt = 0;
                this._assignSortOrder(task, taskId, 'RUNNING_GROUP', fromDrag);
            }
            if (newStatus === 'RUNNING' || newStatus === 'QUEUED') {
                task.startedAt = Date.now();
                task.completedAt = 0;
                this._assignSortOrder(task, taskId, 'RUNNING_GROUP', fromDrag);
            }
            if (newStatus === 'COMPLETED' || newStatus === 'FAILED') {
                this._assignSortOrder(task, taskId, newStatus, fromDrag);
            }

            var backendStatus = newStatus === 'QUEUED' ? 'PENDING' : newStatus;
            var updateData = {
                status: backendStatus,
                sortOrder: task.sortOrder
            };
            if (newStatus === 'COMPLETED' || newStatus === 'FAILED') {
                updateData.completedAt = task.completedAt;
            }
            EABridge.updatePlanTask(this.currentPlan.planId, taskId, updateData);

            if (newStatus === 'RUNNING') {
                task.startedAt = Date.now();
                EABridge.executePlanTask(this.currentPlan.planId, taskId);
            } else {
                this._persistTasks();
            }

            if (newStatus === 'QUEUED' || newStatus === 'RUNNING') {
                this.$nextTick(this._tryAutoStartNext);
            }
        },

        _getSortOrderGroup(statusKey) {
            if (statusKey === 'RUNNING_GROUP') {
                return function (s) { return s === 'RUNNING' || s === 'QUEUED' || s === 'STOPPED'; };
            }
            return function (s) { return s === statusKey; };
        },

        _assignSortOrder(task, taskId, statusKey, fromDrag) {
            var matchStatus = this._getSortOrderGroup(statusKey);
            if (fromDrag) {
                var maxOrder = -1;
                this.currentTasks.forEach(function (t) {
                    if (t.taskId !== taskId && matchStatus((t.status || '').toUpperCase())) {
                        if ((t.sortOrder || 0) > maxOrder) maxOrder = t.sortOrder || 0;
                    }
                });
                task.sortOrder = maxOrder + 1;
            } else {
                this.currentTasks.forEach(function (t) {
                    if (t.taskId !== taskId && matchStatus((t.status || '').toUpperCase())) {
                        t.sortOrder = (t.sortOrder || 0) + 1;
                    }
                });
                task.sortOrder = 0;
            }
        },

        // ========== Task Actions ==========

        onStartAll() {
            var self = this;
            var pendingTasks = this.currentTasks.filter(function (t) {
                return (t.status || '').toUpperCase() === 'PENDING';
            });
            pendingTasks.forEach(function (t) {
                self._updateTaskStatus(t.taskId, 'QUEUED');
            });
            this.autoStart = true;
            this.$nextTick(this._tryAutoStartNext);
        },

        onToggleAutoStart() {
            this.autoStart = !this.autoStart;
            if (this.autoStart) {
                this.$nextTick(this._tryAutoStartNext);
            } else {
                var activeTasks = this.currentTasks.filter(function (t) {
                    var s = (t.status || '').toUpperCase();
                    return s === 'RUNNING' || s === 'QUEUED';
                });
                var self = this;
                activeTasks.forEach(function (t) {
                    self.onStopTask(t);
                });
            }
        },

        onStopTask(task) {
            if (!this.currentPlan) return;
            EABridge.stopPlanTask(this.currentPlan.planId, task.taskId);
            task.status = 'STOPPED';
        },

        onResumeTask(task) {
            this._updateTaskStatus(task.taskId, 'RUNNING');
        },

        _tryAutoStartNext() {
            if (!this.autoStart || !this.currentPlan) return;

            var concurrentLimit = this.store.planConcurrentTasks || 1;
            var runningCount = this.currentTasks.filter(function (t) {
                return (t.status || '').toUpperCase() === 'RUNNING';
            }).length;

            if (runningCount >= concurrentLimit) return;

            var sorted = this.currentTasks
                .filter(function (t) {
                    var s = (t.status || '').toUpperCase();
                    return s === 'QUEUED' || s === 'STOPPED';
                })
                .sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });

            if (sorted.length === 0) return;

            this._updateTaskStatus(sorted[0].taskId, 'RUNNING');
        },

        // ========== Task Detail Session ==========

        onViewTaskSession(task) {
            if (!task || !task.executeSessionId) return;
            this.taskDetailView = task;
            this.store.messages = [];
            this.store.sessionId = task.executeSessionId;
            var cliType = task.cliType || this.currentPlanCliType;
            EABridge.loadHistory(task.executeSessionId, cliType, true);
        },

        onCloseTaskDetail() {
            this.taskDetailView = null;
            this.store.messages = [];
            this.store.sessionId = null;
        },

        // ========== Collect View Chat ==========

        onPlanSend(payload) {
            if (!this.currentPlan) return;
            var text = (payload && payload.text) ? payload.text : '';
            if (!text.trim()) return;
            var planCliType = this.currentPlan.cliType || 'CLAUDE';
            EAStore.cliType = planCliType;
            this.store.addUserMessage(text, []);
            this.store.beginAssistantTurn();
            this.store.setStreaming(true);
            this.$nextTick(this.scrollToBottom);
            var sid = this.store.sessionId || ('plan-' + this.currentPlan.planId);
            this.store.sessionId = sid;
            EABridge.sendMessage(text, null, []);
        },

        onStopGeneration() {
            EABridge.stopGeneration();
            this.store.setStreaming(false);
        },

        // ========== Scroll ==========

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

        // ========== Resize ==========

        onResizeStart(e) {
            e.preventDefault();
            var self = this;
            var startX = e.clientX;
            var startWidth = self.taskPanelWidth;

            function onMouseMove(ev) {
                var diff = startX - ev.clientX;
                self.taskPanelWidth = Math.max(180, Math.min(500, startWidth + diff));
            }

            function onMouseUp() {
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
            }

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        }
    }
});
