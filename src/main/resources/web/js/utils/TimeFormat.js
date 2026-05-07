/**
 * 时间格式化工具。
 * <p>
 * 提供相对时间和持续时间的格式化方法，
 * 用于会话列表和消息气泡中的时间展示。
 * </p>
 *
 * @namespace EATimeFormat
 */
window.EATimeFormat = {
    /**
     * 将毫秒时间戳格式化为相对时间字符串。
     *
     * @param {number} timestamp - 毫秒时间戳
     * @returns {string} 相对时间字符串，如 "just now"、"5m ago"、"2h ago"、"1d ago"
     */
    relative(timestamp) {
        if (!timestamp) return '';
        const now = Date.now();
        const diff = now - timestamp;
        const seconds = Math.floor(diff / 1000);
        const minutes = Math.floor(seconds / 60);
        const hours = Math.floor(minutes / 60);
        const days = Math.floor(hours / 24);

        if (seconds < 60) return 'just now';
        if (minutes < 60) return minutes + 'm ago';
        if (hours < 24) return hours + 'h ago';
        if (days < 30) return days + 'd ago';

        const date = new Date(timestamp);
        return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    },

    /**
     * 将毫秒持续时间格式化为可读字符串。
     *
     * @param {number} ms - 毫秒数
     * @returns {string} 格式化后的时间字符串，如 "500ms"、"1.5s"
     */
    duration(ms) {
        if (!ms) return '';
        if (ms < 1000) return ms + 'ms';
        return (ms / 1000).toFixed(1) + 's';
    }
};
