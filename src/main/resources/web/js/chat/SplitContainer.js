/**
 * 多行网格分屏容器组件。
 * <p>
 * 使用 paneGrid 二维数组渲染多行多列的 SessionPane 网格。
 * 纯鼠标事件实现拖拽：面板上半区=同行左右插入，下半区=移到新行。
 * 支持行间和列间拖拽调整大小。
 * </p>
 *
 * @component split-container
 */

/** 拖拽时 Y 轴分区阈值：上半部分为同行插入，下半部分为新行。 */
var EA_DRAG_Y_SPLIT = 0.5;

/** 调整大小时最小面板尺寸占行/列的百分比。 */
var EA_RESIZE_MIN_PCT = 15;

window.EARegisterComponent('split-container', 'SplitContainer', {
    emits: ['close-pane', 'focus-pane'],
    data() {
        return {
            draggingPaneId: null,
            dropTarget: null,
            _onMouseMove: null,
            _onMouseUp: null,
            resizing: null,
            _onResizeMove: null,
            _onResizeUp: null
        };
    },
    computed: {
        store() { return window.EAStore; },
        i18n() { void this.store.i18nVersion; return window.EAi18n; },
        paneGrid() { return this.store.paneGrid; },
        rowCount() { return this.paneGrid.length; },
        rowSizes() { return this.store.rowSizes; },
        colSizes() { return this.store.colSizes; }
    },
    methods: {
        getPaneProp(paneId, prop) {
            var pane = this.store.getPaneById(paneId);
            return pane ? pane[prop] : '';
        },

        getRowHeightStyle(rowIdx) {
            var sizes = this.rowSizes;
            if (!sizes || sizes.length === 0) return { flex: '1 1 0' };
            return { flex: '0 0 ' + sizes[rowIdx] + '%' };
        },

        getColWidthStyle(rowIdx, colIdx) {
            var sizes = this.colSizes[rowIdx];
            if (!sizes || sizes.length === 0) return { flex: '1 1 0' };
            return { flex: '0 0 ' + sizes[colIdx] + '%' };
        },

        hasRowResizer(rowIdx) {
            return rowIdx < this.rowCount - 1;
        },

        hasColResizer(rowIdx, colIdx) {
            return colIdx < this.paneGrid[rowIdx].length - 1;
        },

        onPaneClose(paneId) {
            this.$emit('close-pane', paneId);
        },

        onPaneFocus(paneId) {
            this.$emit('focus-pane', paneId);
        },

        onPaneDragStart(paneId) {
            this.draggingPaneId = paneId;
            this.dropTarget = null;

            this._onMouseMove = function (e) {
                if (!this.draggingPaneId) return;
                this._updateDropTarget(e.clientX, e.clientY);
            }.bind(this);

            this._onMouseUp = function () {
                this._finishDrag();
            }.bind(this);

            document.addEventListener('mousemove', this._onMouseMove);
            document.addEventListener('mouseup', this._onMouseUp);
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'grabbing';
        },

        onRowResizeStart(rowIdx, event) {
            event.preventDefault();
            var container = this.$refs.container;
            if (!container) return;
            var containerRect = container.getBoundingClientRect();
            var startSizes = (this.rowSizes || []).slice();
            if (startSizes.length === 0) {
                var equal = 100 / this.rowCount;
                for (var r = 0; r < this.rowCount; r++) startSizes.push(equal);
            }
            var startY = event.clientY;

            this._onResizeMove = function (e) {
                var deltaPx = e.clientY - startY;
                var totalH = containerRect.height;
                if (totalH <= 0) return;
                var deltaPct = (deltaPx / totalH) * 100;

                var newSizes = startSizes.slice();
                var newAbove = newSizes[rowIdx] + deltaPct;
                var newBelow = newSizes[rowIdx + 1] - deltaPct;

                if (newAbove < EA_RESIZE_MIN_PCT || newBelow < EA_RESIZE_MIN_PCT) return;

                newSizes[rowIdx] = newAbove;
                newSizes[rowIdx + 1] = newBelow;
                this.store.updateRowSizes(newSizes);
            }.bind(this);

            this._onResizeUp = function () {
                document.removeEventListener('mousemove', this._onResizeMove);
                document.removeEventListener('mouseup', this._onResizeUp);
                document.body.style.userSelect = '';
                document.body.style.cursor = '';
                this._onResizeMove = null;
                this._onResizeUp = null;
            }.bind(this);

            document.addEventListener('mousemove', this._onResizeMove);
            document.addEventListener('mouseup', this._onResizeUp);
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'row-resize';
        },

        onColResizeStart(rowIdx, colIdx, event) {
            event.preventDefault();
            var rowEl = this.$refs.container.querySelectorAll('.split-row')[rowIdx];
            if (!rowEl) return;
            var rowRect = rowEl.getBoundingClientRect();
            var startSizes = (this.colSizes[rowIdx] || []).slice();
            if (startSizes.length === 0) {
                var colCount = this.paneGrid[rowIdx].length;
                var equal = 100 / colCount;
                for (var c = 0; c < colCount; c++) startSizes.push(equal);
            }
            var startX = event.clientX;

            this._onResizeMove = function (e) {
                var deltaPx = e.clientX - startX;
                var totalW = rowRect.width;
                if (totalW <= 0) return;
                var deltaPct = (deltaPx / totalW) * 100;

                var newSizes = startSizes.slice();
                var newLeft = newSizes[colIdx] + deltaPct;
                var newRight = newSizes[colIdx + 1] - deltaPct;

                if (newLeft < EA_RESIZE_MIN_PCT || newRight < EA_RESIZE_MIN_PCT) return;

                newSizes[colIdx] = newLeft;
                newSizes[colIdx + 1] = newRight;
                this.store.updateColSizes(rowIdx, newSizes);
            }.bind(this);

            this._onResizeUp = function () {
                document.removeEventListener('mousemove', this._onResizeMove);
                document.removeEventListener('mouseup', this._onResizeUp);
                document.body.style.userSelect = '';
                document.body.style.cursor = '';
                this._onResizeMove = null;
                this._onResizeUp = null;
            }.bind(this);

            document.addEventListener('mousemove', this._onResizeMove);
            document.addEventListener('mouseup', this._onResizeUp);
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'col-resize';
        },

        _updateDropTarget(clientX, clientY) {
            var container = this.$refs.container;
            if (!container) return;

            var rows = container.querySelectorAll('.split-row');
            if (rows.length === 0) return;

            var containerRect = container.getBoundingClientRect();
            var relY = clientY - containerRect.top;
            var totalH = containerRect.height;

            if (totalH <= 0) return;

            var lastRowRect = rows[rows.length - 1].getBoundingClientRect();
            if (clientY > lastRowRect.bottom) {
                this.dropTarget = { row: this.paneGrid.length, col: 0, position: 'new-row' };
                return;
            }

            var hitRow = -1;
            for (var r = 0; r < rows.length; r++) {
                var rr = rows[r].getBoundingClientRect();
                if (clientY >= rr.top && clientY <= rr.bottom) {
                    hitRow = r;
                    break;
                }
            }

            if (hitRow < 0) {
                this.dropTarget = null;
                return;
            }

            var rowWrappers = rows[hitRow].querySelectorAll('.split-pane-wrapper');
            var paneCount = rowWrappers.length;

            var hitCol = -1;
            var hitRect = null;

            for (var c = 0; c < paneCount; c++) {
                var pid = rowWrappers[c].getAttribute('data-pane-id');
                if (pid === this.draggingPaneId) continue;
                var wr = rowWrappers[c].getBoundingClientRect();
                if (clientX >= wr.left && clientX <= wr.right) {
                    hitCol = c;
                    hitRect = wr;
                    break;
                }
            }

            if (hitCol < 0 && paneCount > 0) {
                var firstW = rowWrappers[0].getBoundingClientRect();
                var lastW = rowWrappers[paneCount - 1].getBoundingClientRect();
                if (clientX < firstW.left) {
                    hitCol = 0;
                    hitRect = firstW;
                } else if (clientX > lastW.right) {
                    hitCol = paneCount - 1;
                    hitRect = lastW;
                }
            }

            if (hitCol < 0 || !hitRect) {
                this.dropTarget = null;
                return;
            }

            var hitPaneId = rowWrappers[hitCol].getAttribute('data-pane-id');
            if (hitPaneId === this.draggingPaneId) {
                this.dropTarget = null;
                return;
            }

            var yRatio = (clientY - hitRect.top) / hitRect.height;

            if (yRatio > EA_DRAG_Y_SPLIT) {
                var srcPos = this.store.getPanePosition(this.draggingPaneId);
                var isLastInRow = srcPos && srcPos.row === hitRow && srcPos.col === rowWrappers.length - 1;
                if (isLastInRow && paneCount <= 1) {
                    this.dropTarget = null;
                    return;
                }
                this.dropTarget = { row: hitRow, col: hitCol, position: 'new-row-below' };
            } else {
                var midX = (hitRect.left + hitRect.right) / 2;
                var pos = clientX < midX ? 'before' : 'after';
                this.dropTarget = { row: hitRow, col: parseInt(rowWrappers[hitCol].getAttribute('data-col')), position: pos };
            }
        },

        _finishDrag() {
            document.removeEventListener('mousemove', this._onMouseMove);
            document.removeEventListener('mouseup', this._onMouseUp);
            document.body.style.userSelect = '';
            document.body.style.cursor = '';

            if (this.draggingPaneId && this.dropTarget) {
                var dt = this.dropTarget;
                if (dt.position === 'new-row' || dt.position === 'new-row-below') {
                    this.store.movePaneToNewRow(this.draggingPaneId);
                } else if (dt.position === 'before') {
                    var targetId = this.paneGrid[dt.row][dt.col];
                    if (targetId && targetId !== this.draggingPaneId) {
                        this.store.movePaneBefore(this.draggingPaneId, targetId);
                    }
                } else if (dt.position === 'after') {
                    var targetId2 = this.paneGrid[dt.row][dt.col];
                    if (targetId2 && targetId2 !== this.draggingPaneId) {
                        this.store.movePaneAfter(this.draggingPaneId, targetId2);
                    }
                }
            }

            this.draggingPaneId = null;
            this.dropTarget = null;
            this._onMouseMove = null;
            this._onMouseUp = null;
        }
    },
    beforeUnmount() {
        if (this._onMouseMove) document.removeEventListener('mousemove', this._onMouseMove);
        if (this._onMouseUp) document.removeEventListener('mouseup', this._onMouseUp);
        if (this._onResizeMove) document.removeEventListener('mousemove', this._onResizeMove);
        if (this._onResizeUp) document.removeEventListener('mouseup', this._onResizeUp);
    }
});
