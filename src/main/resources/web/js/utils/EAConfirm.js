/**
 * 全局确认对话框工具。
 * <p>
 * 替代原生 confirm()，在 JCEF 环境中避免出现 "JavaScript Confirm" 标题栏。
 * 使用 Vue reactive 状态驱动，在任何组件内外均可调用。
 * </p>
 *
 * @namespace EAConfirm
 */
window.EAConfirm = (function () {
    var _state = Vue.reactive({
        visible: false,
        title: '',
        message: '',
        confirmText: '',
        cancelText: '',
        danger: false,
        _resolve: null
    });

    function show(opts) {
        return new Promise(function (resolve) {
            _state.title = opts.title || 'Confirm';
            _state.message = opts.message || '';
            _state.confirmText = opts.confirmText || 'OK';
            _state.cancelText = opts.cancelText || 'Cancel';
            _state.danger = !!opts.danger;
            _state._resolve = resolve;
            _state.visible = true;
        });
    }

    function _confirm() {
        _state.visible = false;
        if (_state._resolve) { _state._resolve(true); _state._resolve = null; }
    }

    function _cancel() {
        _state.visible = false;
        if (_state._resolve) { _state._resolve(false); _state._resolve = null; }
    }

    function getState() { return _state; }

    return { show: show, getState: getState, _confirm: _confirm, _cancel: _cancel };
})();

window.EARegisterComponent('ea-confirm-dialog', 'EAConfirmDialog', {
    data() {
        return { s: EAConfirm.getState() };
    },
    computed: {
        i18n() { void this.s.visible; return window.EAi18n; }
    },
    methods: {
        onConfirm() { EAConfirm._confirm(); },
        onCancel() { EAConfirm._cancel(); },
        onOverlayClick(e) {
            if (e.target === e.currentTarget) EAConfirm._cancel();
        },
        onKeydown(e) {
            if (!this.s.visible) return;
            if (e.key === 'Escape') EAConfirm._cancel();
            if (e.key === 'Enter') EAConfirm._confirm();
        }
    },
    watch: {
        's.visible'(v) {
            if (v) {
                document.addEventListener('keydown', this._keyHandler || (this._keyHandler = this.onKeydown.bind(this)));
            } else if (this._keyHandler) {
                document.removeEventListener('keydown', this._keyHandler);
            }
        }
    }
});
