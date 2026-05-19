/**
 * 面板（Pane）管理方法。
 * <p>
 * 从 {@link store.js} 拆分，将面板的增删、移动、聚焦、
 * 网格布局计算等职责集中到本文件。
 * </p>
 *
 * @namespace EAStore
 */

Object.assign(window.EAStore, {

    getPaneCount() {
        return this.activePanes.length;
    },

    getPaneById(paneId) {
        for (var i = 0; i < this.activePanes.length; i++) {
            if (this.activePanes[i].paneId === paneId) return this.activePanes[i];
        }
        return null;
    },

    getFocusedPane() {
        return this.getPaneById(this.focusedPaneId);
    },

    getPaneRowIndex(paneId) {
        for (var r = 0; r < this.paneGrid.length; r++) {
            for (var c = 0; c < this.paneGrid[r].length; c++) {
                if (this.paneGrid[r][c] === paneId) return r;
            }
        }
        return -1;
    },

    getPanePosition(paneId) {
        for (var r = 0; r < this.paneGrid.length; r++) {
            for (var c = 0; c < this.paneGrid[r].length; c++) {
                if (this.paneGrid[r][c] === paneId) return { row: r, col: c };
            }
        }
        return null;
    },

    addPane(sessionId, cliType, title) {
        this._paneSeq++;
        var pane = {
            paneId: 'pane-' + this._paneSeq,
            sessionId: sessionId || 'new-' + Date.now(),
            cliType: cliType || this.cliType,
            title: title || ''
        };
        this.activePanes.push(pane);
        if (this.paneGrid.length === 0) {
            this.paneGrid.push([]);
        }
        this.paneGrid[this.paneGrid.length - 1].push(pane.paneId);
        this.focusedPaneId = pane.paneId;
        this._recalcSizes();
        return pane;
    },

    addPaneToNewRow(sessionId, cliType, title) {
        this._paneSeq++;
        var pane = {
            paneId: 'pane-' + this._paneSeq,
            sessionId: sessionId || 'new-' + Date.now(),
            cliType: cliType || this.cliType,
            title: title || ''
        };
        this.activePanes.push(pane);
        this.paneGrid.push([pane.paneId]);
        this.focusedPaneId = pane.paneId;
        this._recalcSizes();
        return pane;
    },

    removePane(paneId) {
        var idx = -1;
        for (var i = 0; i < this.activePanes.length; i++) {
            if (this.activePanes[i].paneId === paneId) { idx = i; break; }
        }
        if (idx < 0) return;
        this.activePanes.splice(idx, 1);
        for (var r = 0; r < this.paneGrid.length; r++) {
            for (var c = this.paneGrid[r].length - 1; c >= 0; c--) {
                if (this.paneGrid[r][c] === paneId) {
                    this.paneGrid[r].splice(c, 1);
                    break;
                }
            }
        }
        this.paneGrid = this.paneGrid.filter(function (row) { return row.length > 0; });
        if (this.focusedPaneId === paneId) {
            if (this.activePanes.length > 0) {
                var nextIdx = Math.min(idx, this.activePanes.length - 1);
                this.focusedPaneId = this.activePanes[nextIdx].paneId;
            } else {
                this.focusedPaneId = null;
            }
        }
        this._recalcSizes();
    },

    movePaneToNewRow(paneId) {
        var pos = this.getPanePosition(paneId);
        if (!pos) return;
        this.paneGrid[pos.row].splice(pos.col, 1);
        if (this.paneGrid[pos.row].length === 0) {
            this.paneGrid.splice(pos.row, 1);
        }
        this.paneGrid.push([paneId]);
        this.paneGrid = this.paneGrid.filter(function (row) { return row.length > 0; });
        this.focusedPaneId = paneId;
        this._recalcSizes();
    },

    movePaneToRow(paneId, targetRow) {
        var pos = this.getPanePosition(paneId);
        if (!pos) return;
        if (pos.row === targetRow) return;
        this.paneGrid[pos.row].splice(pos.col, 1);
        if (this.paneGrid[pos.row].length === 0) {
            this.paneGrid.splice(pos.row, 1);
            if (targetRow > pos.row) targetRow--;
        }
        if (targetRow >= this.paneGrid.length) {
            this.paneGrid.push([paneId]);
        } else {
            this.paneGrid[targetRow].push(paneId);
        }
        this.focusedPaneId = paneId;
        this._recalcSizes();
    },

    movePaneBefore(targetPaneId, beforePaneId) {
        if (targetPaneId === beforePaneId) return;
        var srcPos = this.getPanePosition(targetPaneId);
        if (!srcPos) return;
        this.paneGrid[srcPos.row].splice(srcPos.col, 1);
        var destPos = this.getPanePosition(beforePaneId);
        if (!destPos) {
            if (this.paneGrid[srcPos.row].length === 0) {
                this.paneGrid.splice(srcPos.row, 1);
            }
            this.paneGrid.push([targetPaneId]);
            this.focusedPaneId = targetPaneId;
            this._recalcSizes();
            return;
        }
        var insertCol = destPos.col;
        if (srcPos.row === destPos.row && srcPos.col < destPos.col) {
            insertCol = destPos.col - 1;
        }
        if (this.paneGrid[srcPos.row].length === 0) {
            this.paneGrid.splice(srcPos.row, 1);
            if (srcPos.row < destPos.row) {
                destPos.row--;
            }
        }
        this.paneGrid[destPos.row].splice(insertCol, 0, targetPaneId);
        this.paneGrid = this.paneGrid.filter(function (row) { return row.length > 0; });
        this.focusedPaneId = targetPaneId;
        this._recalcSizes();
    },

    movePaneAfter(targetPaneId, afterPaneId) {
        if (targetPaneId === afterPaneId) return;
        var srcPos = this.getPanePosition(targetPaneId);
        if (!srcPos) return;
        this.paneGrid[srcPos.row].splice(srcPos.col, 1);
        var destPos = this.getPanePosition(afterPaneId);
        if (!destPos) {
            if (this.paneGrid[srcPos.row].length === 0) {
                this.paneGrid.splice(srcPos.row, 1);
            }
            this.paneGrid.push([targetPaneId]);
            this.focusedPaneId = targetPaneId;
            this._recalcSizes();
            return;
        }
        var insertCol = destPos.col + 1;
        if (srcPos.row === destPos.row && srcPos.col <= destPos.col) {
            insertCol = destPos.col;
        }
        if (this.paneGrid[srcPos.row].length === 0) {
            this.paneGrid.splice(srcPos.row, 1);
            if (srcPos.row < destPos.row) {
                destPos.row--;
            }
        }
        this.paneGrid[destPos.row].splice(insertCol, 0, targetPaneId);
        this.paneGrid = this.paneGrid.filter(function (row) { return row.length > 0; });
        this.focusedPaneId = targetPaneId;
        this._recalcSizes();
    },

    focusPane(paneId) {
        var pane = this.getPaneById(paneId);
        if (pane) this.focusedPaneId = paneId;
    },

    updatePaneSession(paneId, sessionId, title) {
        var pane = this.getPaneById(paneId);
        if (!pane) return;
        if (sessionId) pane.sessionId = sessionId;
        if (title !== undefined) pane.title = title;
    },

    ensureDefaultPane() {
        if (this.activePanes.length === 0) {
            var pane = this.addPane(null, this.cliType, '');
            this.sessionId = pane.sessionId;
        }
    },

    /**
     * 重新计算所有行高和列宽比例，保持均匀分布。
     * 在面板增删或行列变化后自动调用。
     */
    _recalcSizes() {
        var rowCount = this.paneGrid.length;
        if (rowCount === 0) {
            this.rowSizes = [];
            this.colSizes = {};
            return;
        }
        var rowPct = 100 / rowCount;
        this.rowSizes = [];
        for (var r = 0; r < rowCount; r++) {
            this.rowSizes.push(rowPct);
            var colCount = this.paneGrid[r].length;
            var colPct = 100 / colCount;
            this.colSizes[r] = [];
            for (var c = 0; c < colCount; c++) {
                this.colSizes[r].push(colPct);
            }
        }
    },

    /**
     * 更新行高度比例数组（拖拽调整行高后调用）。
     *
     * @param {Array} sizes 行高度百分比数组
     */
    updateRowSizes(sizes) {
        this.rowSizes = sizes.slice();
    },

    /**
     * 更新指定行的列宽度比例数组（拖拽调整列宽后调用）。
     *
     * @param {number} rowIdx 行索引
     * @param {Array} sizes 列宽度百分比数组
     */
    updateColSizes(rowIdx, sizes) {
        this.colSizes[rowIdx] = sizes.slice();
    },

    activatePaneAsPrimary(paneId) {
        var pane = this.getPaneById(paneId);
        if (!pane) return;
        this._saveCurrentToCache();
        var cached = EA_SESSION_CACHE[pane.sessionId];
        if (cached && cached.loaded) {
            this.messages = cached.messages;
            this.retryStatus = cached.retryStatus || null;
            this.lastTokenUsage = cached.lastTokenUsage || null;
        } else {
            this.messages = [];
            this.retryStatus = null;
            this.lastTokenUsage = null;
        }
        this.sessionId = pane.sessionId;
        this.cliType = pane.cliType;
        this.sessionTitle = pane.title || '';
        this.streamingText = '';
        this.messagesVersion++;
        this.focusedPaneId = paneId;
    }
});
