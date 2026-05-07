/**
 * Vue 组件模板加载器。
 * <p>
 * 支持三种模板加载策略：
 * <ol>
 *   <li><b>内联模板</b>（JCEF 生产环境）：从 {@code window.__EA_TEMPLATES} 读取，
 *       由 Java 端在加载页面时注入，无需网络请求</li>
 *   <li><b>fetch 加载</b>（HTTP 开发服务器）：使用 fetch API 异步加载</li>
 *   <li><b>XHR 加载</b>（兜底）：使用 XMLHttpRequest 兼容 file:// 协议</li>
 * </ol>
 * </p>
 * <p>
 * 目录结构：
 * <pre>
 * js/
 * ├── chat/           -- 对话模块
 * │   ├── ChatView.vue.html / ChatView.js
 * │   ├── ChatHeader.vue.html / ChatHeader.js
 * │   ├── InputBar.vue.html / InputBar.js
 * │   └── EmptyState.vue.html / EmptyState.js
 * ├── message/        -- 消息渲染模块
 * │   ├── MessageBubble.vue.html / MessageBubble.js
 * │   ├── ThinkingBlock.vue.html / ThinkingBlock.js
 * │   └── ...
 * └── settings/       -- 设置模块
 *     ├── SettingsPage.vue.html / SettingsPage.js
 *     └── ...
 * </pre>
 * </p>
 *
 * @namespace EATemplateLoader
 */
window.EATemplateLoader = {

    _cache: {},

    /** 模块搜索路径列表。 */
    searchPaths: ['js/chat/', 'js/message/', 'js/settings/'],

    /**
     * 批量加载所有注册组件的模板并注册到 Vue 应用。
     *
     * @param {Vue.App} app - Vue 应用实例
     */
    async loadAll(app) {
        const tasks = this._componentList.map((entry) => this._loadAndRegister(app, entry));
        await Promise.all(tasks);
    },

    /**
     * 加载单个组件模板并注册到 Vue 应用。
     *
     * @param {Vue.App} app - Vue 应用实例
     * @param {Object} entry - 组件注册项
     */
    async _loadAndRegister(app, entry) {
        const template = await this.fetchTemplate(entry);
        const component = Object.assign({}, entry.def);
        component.template = template;
        app.component(entry.name, component);
    },

    /**
     * 获取组件模板内容。
     * <p>
     * 优先从 {@code window.__EA_TEMPLATES} 内联模板读取（JCEF 生产模式），
     * 其次按 path → searchPaths 顺序尝试文件加载。
     * </p>
     *
     * @param {Object} entry - 组件注册项
     * @returns {Promise<string>} 模板 HTML 内容
     */
    async fetchTemplate(entry) {
        const cacheKey = entry.name;
        if (this._cache[cacheKey]) return this._cache[cacheKey];

        if (window.__EA_TEMPLATES) {
            const tpl = this._findInlineTemplate(entry);
            if (tpl !== null) return tpl;
        }

        if (entry.path) {
            const tpl = await this._loadUrl(entry.path);
            if (tpl !== null) return tpl;
        }

        for (const basePath of this.searchPaths) {
            const url = basePath + entry.className + '.vue.html';
            const tpl = await this._loadUrl(url);
            if (tpl !== null) return tpl;
        }

        console.error('[EATemplateLoader] Template not found: ' + entry.name);
        return '<div>Template not found: ' + entry.name + '</div>';
    },

    /**
     * 从内联模板映射中查找组件模板。
     * <p>
     * 支持多种 key 格式匹配：{@code js/chat/ChatView.vue.html}、
     * {@code chat/ChatView.vue.html}、{@code ChatView.vue.html}。
     * </p>
     *
     * @param {Object} entry - 组件注册项
     * @returns {string|null} 模板内容，未找到返回 null
     */
    _findInlineTemplate(entry) {
        var templates = window.__EA_TEMPLATES;
        var fileName = entry.className + '.vue.html';

        for (var key in templates) {
            if (key === fileName || key.endsWith('/' + fileName)) {
                this._cache[entry.name] = templates[key];
                return templates[key];
            }
        }
        return null;
    },

    /**
     * 通过文件加载模板。
     * <p>
     * HTTP 环境优先使用 fetch，失败后兜底 XHR。
     * file:// 环境直接使用 XHR。
     * </p>
     *
     * @param {string} url - 模板文件 URL
     * @returns {Promise<string|null>} 模板 HTML 内容（已去除 template 标签），失败返回 null
     */
    async _loadUrl(url) {
        if (this._cache[url]) return this._cache[url];

        var html = await this._fetchLoad(url);
        if (html === null) {
            html = await this._xhrLoad(url);
        }

        if (html !== null) {
            var cleaned = this._unwrapTemplate(html);
            this._cache[url] = cleaned;
            return cleaned;
        }
        return null;
    },

    /**
     * 仅移除最外层 template 包裹，避免破坏内部的 template/v-if/v-for 结构。
     *
     * @param {string} html - 模板原始内容
     * @returns {string} 处理后的模板内容
     */
    _unwrapTemplate(html) {
        var trimmed = html.trim();
        var startTag = '<template>';
        var endTag = '</template>';
        if (trimmed.startsWith(startTag) && trimmed.endsWith(endTag)) {
            return trimmed.slice(startTag.length, trimmed.length - endTag.length).trim();
        }
        return trimmed;
    },

    /**
     * 使用 fetch API 加载文件（HTTP 环境）。
     *
     * @param {string} url - 文件 URL
     * @returns {Promise<string|null>} 文件内容，加载失败返回 null
     */
    async _fetchLoad(url) {
        try {
            var resp = await fetch(url);
            if (resp.ok) {
                return await resp.text();
            }
        } catch (e) { }
        return null;
    },

    /**
     * 使用 XMLHttpRequest 加载文件（兼容 file:// 协议）。
     *
     * @param {string} url - 文件 URL
     * @returns {Promise<string|null>} 文件内容，加载失败返回 null
     */
    _xhrLoad(url) {
        return new Promise(function (resolve) {
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', url, true);
                xhr.overrideMimeType('text/html');
                xhr.onreadystatechange = function () {
                    if (xhr.readyState === 4) {
                        if (xhr.status === 200 || xhr.status === 0) {
                            resolve(xhr.responseText || null);
                        } else {
                            resolve(null);
                        }
                    }
                };
                xhr.onerror = function () { resolve(null); };
                xhr.send();
            } catch (e) {
                resolve(null);
            }
        });
    },

    _componentList: []
};

/**
 * 注册组件到加载器。
 *
 * @param {string} name - kebab-case 组件名（如 'chat-view'）
 * @param {string} className - PascalCase 类名（如 'ChatView'），用于定位模板文件
 * @param {Object} definition - Vue 组件选项对象（不含 template）
 */
window.EARegisterComponent = function (name, className, definition) {
    definition.name = name;
    EATemplateLoader._componentList.push({ name: name, className: className, def: definition });
};
