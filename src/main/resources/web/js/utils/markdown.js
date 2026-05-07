/**
 * Markdown 渲染工具。
 * <p>
 * 封装 marked.js 和 highlight.js，提供 Markdown 文本渲染、
 * 代码块高亮和复制功能。为代码块自动添加语言标签和复制按钮。
 * </p>
 *
 * @namespace EAMarkdown
 */
window.EAMarkdown = {
    /** marked.js 渲染器实例。 */
    renderer: null,

    /**
     * 初始化 Markdown 渲染器。配置自定义代码块渲染器，
     * 支持语法高亮和复制按钮。
     */
    init() {
        if (typeof marked === 'undefined') return;

        this.renderer = new marked.Renderer();

        /**
         * 自定义代码块渲染：添加语言标签栏和复制按钮。
         *
         * @param {string} code - 代码文本
         * @param {string} language - 编程语言标识
         * @returns {string} 渲染后的 HTML 字符串
         */
        this.renderer.code = function (codeOrObj, language) {
            let code, lang;
            if (typeof codeOrObj === 'object') {
                code = codeOrObj.text || '';
                lang = (codeOrObj.lang || 'text').toLowerCase();
            } else {
                code = codeOrObj;
                lang = (language || 'text').toLowerCase();
            }
            let highlighted = code;
            if (typeof hljs !== 'undefined' && hljs.getLanguage(lang)) {
                try {
                    highlighted = hljs.highlight(code, { language: lang }).value;
                } catch (e) {
                    highlighted = code;
                }
            }
            const escaped = code.replace(/'/g, "\\'").replace(/\n/g, '\\n');
            return '<div class="code-block-wrapper">' +
                '<div class="code-block-header">' +
                '<span>' + lang + '</span>' +
                '<button class="code-block-copy" onclick="EAMarkdown.copyCode(this, \'' + escaped + '\')">Copy</button>' +
                '</div>' +
                '<pre><code class="hljs language-' + lang + '">' + highlighted + '</code></pre>' +
                '</div>';
        };

        marked.setOptions({
            renderer: this.renderer,
            breaks: true,
            gfm: true
        });
    },

    /**
     * 将 Markdown 文本渲染为 HTML。
     *
     * @param {string} text - Markdown 格式的文本
     * @returns {string} 渲染后的 HTML 字符串
     */
    render(text) {
        if (!text) return '';
        if (typeof marked === 'undefined') return this.escapeHtml(text);
        try {
            const result = marked.parse(text);
            if (typeof result !== 'string') return this.escapeHtml(text);
            return result;
        } catch (e) {
            console.error('[EAMarkdown] Render error:', e);
            return this.escapeHtml(text);
        }
    },

    /**
     * 对文本进行 HTML 转义，防止 XSS。
     *
     * @param {string} str - 原始文本
     * @returns {string} 转义后的安全文本
     */
    escapeHtml(str) {
        return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    },

    /**
     * 复制代码到剪贴板。
     *
     * @param {HTMLButtonElement} btn - 复制按钮元素
     * @param {string} code - 要复制的代码文本（已转义）
     */
    copyCode(btn, code) {
        const decoded = code.replace(/\\n/g, '\n').replace(/\\'/g, "'");
        navigator.clipboard.writeText(decoded).then(() => {
            btn.textContent = 'Copied!';
            setTimeout(() => { btn.textContent = 'Copy'; }, 1500);
        });
    }
};
