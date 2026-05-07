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

    function renderBootstrapError(error) {
        const target = document.getElementById('app');
        if (!target) {
            return;
        }

        const message = error && error.message ? error.message : String(error);
        target.innerHTML = ''
            + '<div style="padding:24px;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;">'
            + '<div style="max-width:720px;margin:48px auto;padding:20px 24px;border:1px solid #FCA5A5;'
            + 'border-radius:12px;background:#FEF2F2;color:#991B1B;">'
            + '<h2 style="margin:0 0 8px;font-size:18px;">EasyAgent UI failed to start</h2>'
            + '<div style="font-size:13px;line-height:1.6;white-space:pre-wrap;">'
            + message
            + '</div>'
            + '</div>'
            + '</div>';
    }

    try {
        EAStorage.init();
        EAi18n.init();
        EATheme.init();
        EAMarkdown.init();
        EABridge.init();

        const app = Vue.createApp({
            template: '<chat-view></chat-view>'
        });

        await EATemplateLoader.loadAll(app);

        app.mount('#app');
        EABridge.getTheme();
    } catch (error) {
        console.error('[EasyAgent] Bootstrap failed:', error);
        renderBootstrapError(error);
    }
})();
