/**
 * 安全存储工具。
 * <p>
 * JCEF 通过 {@code loadHTML} 加载页面时，浏览器上下文可能不允许直接访问
 * {@code localStorage}。该工具统一封装存取逻辑，避免因 {@code SecurityError}
 * 导致前端启动阶段直接白屏。
 * </p>
 *
 * @namespace EAStorage
 */
window.EAStorage = {
    /** 是否允许直接访问 localStorage。 */
    available: false,

    /** 内存兜底存储。 */
    _memoryStore: {},

    /**
     * 初始化存储能力检测。
     */
    init() {
        try {
            const testKey = '__ea_storage_probe__';
            window.localStorage.setItem(testKey, '1');
            window.localStorage.removeItem(testKey);
            this.available = true;
        } catch (error) {
            this.available = false;
            console.warn('[EAStorage] localStorage unavailable, fallback to in-memory store.', error);
        }
    },

    /**
     * 读取指定键的值。
     *
     * @param {string} key - 存储键
     * @returns {string|null} 读取到的值，不存在时返回 null
     */
    getItem(key) {
        if (this.available) {
            try {
                return window.localStorage.getItem(key);
            } catch (error) {
                this.available = false;
                console.warn('[EAStorage] localStorage read failed, fallback to in-memory store.', error);
            }
        }
        return Object.prototype.hasOwnProperty.call(this._memoryStore, key)
            ? this._memoryStore[key]
            : null;
    },

    /**
     * 写入指定键值。
     *
     * @param {string} key - 存储键
     * @param {string} value - 存储值
     */
    setItem(key, value) {
        if (this.available) {
            try {
                window.localStorage.setItem(key, value);
                return;
            } catch (error) {
                this.available = false;
                console.warn('[EAStorage] localStorage write failed, fallback to in-memory store.', error);
            }
        }
        this._memoryStore[key] = value;
    },

    /**
     * 删除指定键。
     *
     * @param {string} key - 存储键
     */
    removeItem(key) {
        if (this.available) {
            try {
                window.localStorage.removeItem(key);
            } catch (error) {
                this.available = false;
                console.warn('[EAStorage] localStorage remove failed, fallback to in-memory store.', error);
            }
        }
        delete this._memoryStore[key];
    }
};
