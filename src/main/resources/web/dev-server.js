/**
 * Browser-sync 开发服务器。
 *
 * 启动: npm run dev
 *
 * 功能:
 * - 自动打开浏览器预览 index-dev.html
 * - 监听 CSS/JS 文件变更，自动刷新浏览器
 * - 内置 Mock 后端，无需启动 IDEA
 * - 浏览器窗口大小模拟 IDEA 插件面板 (400x600)
 */
const browserSync = require('browser-sync');

browserSync.create().init({
    server: {
        baseDir: __dirname,
        index: 'index-dev.html'
    },
    files: [
        'css/**/*.css',
        'js/**/*.js',
        'js/**/*.vue.html',
        'index-dev.html'
    ],
    port: 3100,
    open: true,
    notify: false,
    reloadDelay: 100,
    reloadDebounce: 200,
    startPath: '/index-dev.html',
    snippetOptions: {
        rule: {
            match: /<\/body>/i,
            fn: (snippet, match) => snippet + match
        }
    }
});
