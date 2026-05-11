/**
 * 计划看板视图组件。
 * <p>
 * 支持计划 CRUD、任务拆分（collect 视图）、看板拖拽（kanban 视图）、
 * 并发控制、自动开始、停止/恢复任务等。
 * </p>
 *
 * @component plan-view
 */

var EA_SCROLL_BOTTOM_THRESHOLD = 80;

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

            _dragging: false,
            _sortableInstances: null
        };
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
                .sort(function (a, b) { return (b.completedAt || 0) - (a.completedAt || 0); });
        },
        failedTasks() {
            return this.currentTasks
                .filter(function (t) { return (t.status || '').toUpperCase() === 'FAILED'; })
                .sort(function (a, b) { return (b.completedAt || 0) - (a.completedAt || 0); });
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

                if (this._dragging || incomingTasks.length === 0) {
                    if (this.currentPlan.status === 'KANBAN') {
                        this.viewMode = 'kanban';
                    } else if (this.currentPlan.status === 'TASK_SPLITTING' || this.currentPlan.status === 'DRAFT') {
                        this.viewMode = 'collect';
                    }
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

                if (this.currentPlan.status === 'KANBAN') {
                    this.viewMode = 'kanban';
                } else if (this.currentPlan.status === 'TASK_SPLITTING' || this.currentPlan.status === 'DRAFT') {
                    this.viewMode = 'collect';
                }
            }
        }.bind(this);

        this._onPlanTaskUpdated = function (e) {
            var detail = e.detail;
            if (!detail || !detail.taskId) return;
            var found = false;
            for (var i = 0; i < this.currentTasks.length; i++) {
                if (this.currentTasks[i].taskId === detail.taskId) {
                    var localStatus = (this.currentTasks[i].status || '').toUpperCase();
                    if (localStatus === 'QUEUED' || localStatus === 'RUNNING') {
                        found = true;
                        break;
                    }
                    Object.assign(this.currentTasks[i], this.normalizeTask(detail));
                    found = true;
                    break;
                }
            }
            if (!found) {
                this.currentTasks.push(this.normalizeTask(detail));
            }
        }.bind(this);

        this._onPlanTaskStatus = function (e) {
            var detail = e.detail || {};
            var taskId = detail.taskId;
            var status = (detail.status || '').toUpperCase();
            if (!taskId) return;
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

        window.addEventListener('ea-plan-created', this._onPlanCreated);
        window.addEventListener('ea-plan-list', this._onPlanList);
        window.addEventListener('ea-plan-detail', this._onPlanDetail);
        window.addEventListener('ea-plan-task-updated', this._onPlanTaskUpdated);
        window.addEventListener('ea-plan-task-status', this._onPlanTaskStatus);
        window.addEventListener('ea-plan-deleted', this._onPlanDeleted);
        window.addEventListener('ea-stream-complete', this._onStreamComplete);

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

        getStatusLabel(status) {
            var key = EA_STATUS_LABELS[status];
            return key ? this.i18n.t(key) : (status || '');
        },

        getCliLabelFromEnum(cliType) {
            return EA_CLI_LABELS[cliType] || cliType || '';
        },

        formatTime(ts) { return EATimeFormat.relative(ts); },

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
            if (this.viewMode === 'kanban' || this.viewMode === 'collect') {
                this.currentPlan = null;
                this.currentTasks = [];
                this.viewMode = 'list';
                this._destroySortable();
                return;
            }
            this.store.appMode = 'welcome';
        },

        onGoSettings() {
            this.store.appMode = 'settings';
        },

        onGoHome() {
            this.currentPlan = null;
            this.currentTasks = [];
            this.taskDetailView = null;
            this.viewMode = 'list';
            this._destroySortable();
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
            this.currentTasks = (plan.tasks || []).map(this.normalizeTask);
            EABridge.getPlanDetail(plan.planId);
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

        onEditTask(task) {
            this.onOpenTaskDialog(task);
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
            this._persistTasks();
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

        _initSortable() {
            this._tryInitSortable();
        },

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
                        var oldIndex = evt.oldIndex;
                        var newIndex = evt.newIndex;

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

        _handleDragEnd(evt) {
            var fromColumn = evt.from.getAttribute('data-column');
            var toColumn = evt.to.getAttribute('data-column');
            var taskId = evt.item.getAttribute('data-task-id');

            if (!taskId || !this.currentPlan) return;

            if (fromColumn === toColumn) {
                this._reorderInColumn(evt);
                return;
            }

            this.onTaskDragEnd(taskId, fromColumn, toColumn);
        },

        _handleDragEndFromData(taskId, fromColumn, toColumn) {
            if (!taskId || !this.currentPlan) return;

            if (fromColumn === toColumn) {
                return;
            }

            this.onTaskDragEnd(taskId, fromColumn, toColumn);
        },

        _reorderInColumn(evt) {
            var column = evt.from.getAttribute('data-column');
            var items = evt.to.querySelectorAll('.kanban-card[data-task-id]');
            var newOrder = [];
            for (var i = 0; i < items.length; i++) {
                newOrder.push(items[i].getAttribute('data-task-id'));
            }

            for (var j = 0; j < newOrder.length; j++) {
                for (var k = 0; k < this.currentTasks.length; k++) {
                    if (this.currentTasks[k].taskId === newOrder[j]) {
                        this.currentTasks[k].sortOrder = j;
                        break;
                    }
                }
            }
            this._persistTasks();
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

            this._updateTaskStatus(taskId, newStatus);
        },

        _updateTaskStatus(taskId, newStatus) {
            var task = this.currentTasks.find(function (t) { return t.taskId === taskId; });
            if (!task) return;

            var self = this;
            var oldStatus = (task.status || '').toUpperCase();
            task.status = newStatus;

            if (newStatus === 'PENDING') {
                task.completedAt = 0;
                task.startedAt = 0;
                var pendingCount = 0;
                this.currentTasks.forEach(function (t) {
                    if (t.taskId !== taskId && (t.status || '').toUpperCase() === 'PENDING') {
                        pendingCount++;
                    }
                });
                task.sortOrder = pendingCount;
                this.currentTasks.forEach(function (t) {
                    if ((t.status || '').toUpperCase() === 'PENDING') {
                        t.sortOrder = self._indexOfTask(t.taskId, 'PENDING');
                    }
                });
            }
            if (newStatus === 'STOPPED') {
                task.completedAt = 0;
            }
            if (newStatus === 'RUNNING' || newStatus === 'QUEUED') {
                task.startedAt = Date.now();
                task.completedAt = 0;
                var runningCount = 0;
                this.currentTasks.forEach(function (t) {
                    if (t.taskId !== taskId) {
                        var s = (t.status || '').toUpperCase();
                        if (s === 'RUNNING' || s === 'QUEUED' || s === 'STOPPED') {
                            runningCount++;
                        }
                    }
                });
                task.sortOrder = runningCount;
            }
            if (newStatus === 'COMPLETED' || newStatus === 'FAILED') {
                task.completedAt = Date.now();
            }

            var backendStatus = newStatus === 'QUEUED' ? 'PENDING' : newStatus;
            EABridge.updatePlanTask(this.currentPlan.planId, taskId, {
                status: backendStatus,
                sortOrder: task.sortOrder
            });

            if (newStatus === 'RUNNING') {
                task.startedAt = Date.now();
                EABridge.executePlanTask(this.currentPlan.planId, taskId);
            }

            this._persistTasks();

            if (newStatus === 'QUEUED' || newStatus === 'RUNNING') {
                this.$nextTick(this._tryAutoStartNext);
            }
        },

        _indexOfTask(taskId, statusFilter) {
            var idx = 0;
            for (var i = 0; i < this.currentTasks.length; i++) {
                var t = this.currentTasks[i];
                if ((t.status || '').toUpperCase() === statusFilter) {
                    if (t.taskId === taskId) return idx;
                    idx++;
                }
            }
            return idx;
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
                var runningTasks = this.currentTasks.filter(function (t) {
                    return (t.status || '').toUpperCase() === 'RUNNING';
                });
                var self = this;
                runningTasks.forEach(function (t) {
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
            this._updateTaskStatus(task.taskId, 'QUEUED');
        },

        _tryAutoStartNext() {
            if (!this.autoStart || !this.currentPlan) return;

            var concurrentLimit = this.store.planConcurrentTasks || 1;
            var runningCount = this.currentTasks.filter(function (t) {
                return (t.status || '').toUpperCase() === 'RUNNING';
            }).length;

            if (runningCount >= concurrentLimit) return;

            var nextTask = null;
            var sorted = this.currentTasks
                .filter(function (t) {
                    var s = (t.status || '').toUpperCase();
                    return s === 'QUEUED' || s === 'STOPPED';
                })
                .sort(function (a, b) { return (a.sortOrder || 0) - (b.sortOrder || 0); });

            if (sorted.length > 0) {
                nextTask = sorted[0];
            }

            if (!nextTask) return;

            nextTask.status = 'RUNNING';
            nextTask.startedAt = Date.now();
            EABridge.updatePlanTask(this.currentPlan.planId, nextTask.taskId, {
                status: 'RUNNING'
            });
            EABridge.executePlanTask(this.currentPlan.planId, nextTask.taskId);
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
