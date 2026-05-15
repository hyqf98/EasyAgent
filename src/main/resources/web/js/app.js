/**
 * EasyAgent 前端应用入口。
 * <p>
 * 初始化国际化、主题管理、Markdown 渲染器和 JCEF 通信桥。
 * 通过 {@link EATemplateLoader} 异步加载所有组件模板后挂载 Vue3 应用。
 * </p>
 *
 * @namespace
 */
(async function () {
    'use strict';

    function escapeHtml(value) {
        return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function getErrorMessage(error) {
        if (!error) {
            return 'Unknown error';
        }
        if (error instanceof Error) {
            return error.message || error.name || 'Unknown error';
        }
        if (typeof error === 'string') {
            return error;
        }
        try {
            return JSON.stringify(error);
        } catch (e) {
            return String(error);
        }
    }

    function getErrorStack(error) {
        if (!error) {
            return '';
        }
        if (error instanceof Error && error.stack) {
            return String(error.stack);
        }
        return '';
    }

    function reportRuntimeError(scope, error, info) {
        var payload = {
            scope: scope || 'ui',
            message: getErrorMessage(error),
            info: info || '',
            stack: getErrorStack(error),
            timestamp: Date.now()
        };
        window.__EA_LAST_RUNTIME_ERROR__ = payload;
        console.error('[EasyAgent][' + payload.scope + ']', error, info || '');
        return payload;
    }

    function renderBootstrapError(error) {
        const target = document.getElementById('app');
        if (!target) {
            return;
        }

        const message = escapeHtml(getErrorMessage(error));
        const stack = escapeHtml(getErrorStack(error));
        target.innerHTML = ''
            + '<div style="padding:24px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;">'
            + '<div style="max-width:720px;margin:48px auto;padding:20px 24px;border:1px solid var(--ea-error-border, #FCA5A5);'
            + 'border-radius:12px;background:var(--ea-error-bg, #FEF2F2);color:var(--ea-text, #991B1B);">'
            + '<h2 style="margin:0 0 8px;font-size:18px;">EasyAgent UI failed to start</h2>'
            + '<div style="font-size:13px;line-height:1.6;white-space:pre-wrap;">'
            + message
            + '</div>'
            + (stack ? '<details style="margin-top:12px;"><summary style="cursor:pointer;">Stack</summary>'
                + '<pre style="margin:8px 0 0;white-space:pre-wrap;">' + stack + '</pre></details>' : '')
            + '</div>'
            + '</div>';
    }

    function createPageErrorBoundary() {
        return {
            name: 'EAPageErrorBoundary',
            props: {
                pageName: {
                    type: String,
                    default: 'page'
                },
                resetKey: {
                    type: [String, Number, Boolean],
                    default: ''
                }
            },
            data() {
                return {
                    errorState: null,
                    renderKey: 0
                };
            },
            watch: {
                resetKey() {
                    this.resetBoundary();
                }
            },
            methods: {
                resetBoundary() {
                    this.errorState = null;
                    this.renderKey++;
                },
                retryRender() {
                    this.resetBoundary();
                }
            },
            errorCaptured(error, instance, info) {
                this.errorState = reportRuntimeError(this.pageName, error, info);
                return false;
            },
            template: `
                <div v-if="errorState" style="padding:16px;">
                    <div style="border:1px solid var(--ea-error-border, #FCA5A5);border-radius:12px;background:var(--ea-error-bg, #FEF2F2);color:var(--ea-text, #991B1B);padding:16px 18px;">
                        <div style="font-size:16px;font-weight:600;margin-bottom:6px;">
                            {{ pageName }} failed to render
                        </div>
                        <div style="font-size:13px;line-height:1.6;white-space:pre-wrap;">
                            {{ errorState.message }}
                        </div>
                        <div v-if="errorState.info" style="margin-top:8px;font-size:12px;opacity:.85;">
                            {{ errorState.info }}
                        </div>
                        <details v-if="errorState.stack" style="margin-top:10px;">
                            <summary style="cursor:pointer;">Stack</summary>
                            <pre style="margin:8px 0 0;white-space:pre-wrap;font-size:12px;">{{ errorState.stack }}</pre>
                        </details>
                        <div style="margin-top:12px;">
                            <button
                                type="button"
                                @click="retryRender"
                                style="border:0;border-radius:8px;background:#DC2626;color:#fff;padding:8px 12px;cursor:pointer;font-size:12px;"
                            >
                                Retry
                            </button>
                        </div>
                    </div>
                </div>
                <div v-else :key="renderKey" style="display: contents;">
                    <slot></slot>
                </div>
            `
        };
    }

    try {
        window.addEventListener('error', function (event) {
            reportRuntimeError('window', event.error || event.message, event.filename || '');
        });
        window.addEventListener('unhandledrejection', function (event) {
            reportRuntimeError('promise', event.reason, 'unhandledrejection');
        });

        EAStorage.init();
        EAi18n.init();
        EATheme.init();
        EAMarkdown.init();
        EABridge.init();

        const app = Vue.createApp({
            data() {
                return { store: window.EAStore };
            },
            template: `
                <ea-page-error-boundary
                    v-if="store.appMode !== 'plan'"
                    page-name="chat-page"
                    :reset-key="'chat-' + store.appMode"
                >
                    <chat-view></chat-view>
                </ea-page-error-boundary>
                <ea-page-error-boundary
                    v-if="store.appMode === 'plan'"
                    page-name="plan-page"
                    :reset-key="'plan-' + store.appMode"
                >
                    <plan-view></plan-view>
                </ea-page-error-boundary>
            `
        });

        app.component('ea-page-error-boundary', createPageErrorBoundary());
        app.config.errorHandler = function (error, instance, info) {
            reportRuntimeError('vue-app', error, info);
        };

        await EATemplateLoader.loadAll(app);

        app.mount('#app');
        EABridge.getTheme();
    } catch (error) {
        console.error('[EasyAgent] Bootstrap failed:', error);
        renderBootstrapError(error);
    }
})();
