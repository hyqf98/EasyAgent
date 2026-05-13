/**
 * 多面板标签栏组件。
 * <p>
 * 显示当前所有打开的面板标签，支持点击切换、关闭、新增面板。
 * 多面板时显示，单面板时隐藏以节省空间。支持标签拖拽重排序。
 * </p>
 *
 * @component tab-bar
 */
window.EARegisterComponent('tab-bar', 'TabBar', {
    emits: ['select-pane', 'close-pane', 'add-pane'],
    data() {
        return {
            dragTabId: null,
            dropTabId: null,
            dropTabPosition: null,
            _onTabMouseMove: null,
            _onTabMouseUp: null
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        panes() { return this.store.activePanes; },
        focusedPaneId() { return this.store.focusedPaneId; }
    },
    methods: {
        onTabClick(paneId) {
            if (this.dragTabId) return;
            this.$emit('select-pane', paneId);
        },
        onTabClose(paneId) {
            this.$emit('close-pane', paneId);
        },
        getTabTitle(pane) {
            if (pane.title) return pane.title;
            var sid = pane.sessionId || '';
            if (sid.indexOf('new-') === 0) return this.i18n.t('tab.newChat');
            if (sid.length <= 10) return sid;
            return sid.substring(0, 8) + '...';
        },
        onTabMouseDown(paneId, event) {
            if (event.button !== 0) return;
            if (this.panes.length <= 1) return;
            this.dragTabId = paneId;
            this.dropTabId = null;
            this.dropTabPosition = null;

            this._onTabMouseMove = function (e) {
                if (!this.dragTabId) return;
                this._updateTabDropTarget(e.clientX);
            }.bind(this);

            this._onTabMouseUp = function () {
                this._finishTabDrag();
            }.bind(this);

            document.addEventListener('mousemove', this._onTabMouseMove);
            document.addEventListener('mouseup', this._onTabMouseUp);
            document.body.style.userSelect = 'none';
        },
        _updateTabDropTarget(clientX) {
            var tabBar = this.$refs.tabBar;
            if (!tabBar) return;

            var items = tabBar.querySelectorAll('.tab-item');
            for (var i = 0; i < items.length; i++) {
                var pid = items[i].getAttribute('data-pane-id');
                if (pid === this.dragTabId) continue;
                var rect = items[i].getBoundingClientRect();
                if (clientX >= rect.left && clientX <= rect.right) {
                    var midX = (rect.left + rect.right) / 2;
                    this.dropTabId = pid;
                    this.dropTabPosition = clientX < midX ? 'before' : 'after';
                    return;
                }
            }
            this.dropTabId = null;
            this.dropTabPosition = null;
        },
        _finishTabDrag() {
            document.removeEventListener('mousemove', this._onTabMouseMove);
            document.removeEventListener('mouseup', this._onTabMouseUp);
            document.body.style.userSelect = '';

            if (this.dragTabId && this.dropTabId && this.dropTabId !== this.dragTabId) {
                this._reorderPanes(this.dragTabId, this.dropTabId, this.dropTabPosition);
            }

            this.dragTabId = null;
            this.dropTabId = null;
            this.dropTabPosition = null;
            this._onTabMouseMove = null;
            this._onTabMouseUp = null;
        },
        _reorderPanes(fromId, toId, position) {
            var panes = this.store.activePanes;
            var fromIdx = -1;
            var toIdx = -1;
            for (var i = 0; i < panes.length; i++) {
                if (panes[i].paneId === fromId) fromIdx = i;
                if (panes[i].paneId === toId) toIdx = i;
            }
            if (fromIdx < 0 || toIdx < 0) return;

            var moved = panes.splice(fromIdx, 1)[0];
            var insertIdx = toIdx;
            if (fromIdx < toIdx) insertIdx--;
            if (position === 'after') insertIdx++;
            panes.splice(insertIdx, 0, moved);

            this._syncGridOrder();
        },
        _syncGridOrder() {
            var paneOrderMap = {};
            this.store.activePanes.forEach(function (p, idx) {
                paneOrderMap[p.paneId] = idx;
            });

            var changed = false;
            this.store.paneGrid.forEach(function (row) {
                row.sort(function (a, b) {
                    var oa = paneOrderMap[a] !== undefined ? paneOrderMap[a] : 999;
                    var ob = paneOrderMap[b] !== undefined ? paneOrderMap[b] : 999;
                    return oa - ob;
                });
                changed = true;
            });

            if (changed) {
                this.store.paneGrid = this.store.paneGrid.slice();
            }
        }
    },
    beforeUnmount() {
        if (this._onTabMouseMove) document.removeEventListener('mousemove', this._onTabMouseMove);
        if (this._onTabMouseUp) document.removeEventListener('mouseup', this._onTabMouseUp);
    }
});
