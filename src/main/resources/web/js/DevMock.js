/**
 * 开发模式 Mock 数据管理器。
 * <p>
 * 提供完整的模拟数据集，覆盖所有消息类型：
 * 用户消息、AI文本、思考块、工具调用、待办列表、错误、步骤信息。
 * 通过 {@code EAMockData.enabled} 开关控制是否自动加载模拟数据。
 * </p>
 *
 * @namespace EAMockData
 */
window.EAMockData = {
    /** 是否启用模拟数据自动加载。设为 false 则进入空白欢迎页。 */
    enabled: true,

    /** 模拟会话列表（字段与后端 SessionInfo 对齐）。 */
    sessions: [
        { sessionId: 'demo-full', cliType: 'CLAUDE', title: '完整消息类型演示', model: 'claude-3.5-sonnet', projectPath: '/Users/demo/project1', gitBranch: 'main', createdAt: Date.now() - 1800000, updatedAt: Date.now() - 1800000, messageCount: 4 },
        { sessionId: 'demo-refactor', cliType: 'CLAUDE', title: '重构 SessionReader 模块', model: 'claude-3.5-sonnet', projectPath: '/Users/demo/project1', gitBranch: 'feature/refactor', createdAt: Date.now() - 3600000, updatedAt: Date.now() - 3600000, messageCount: 2 },
        { sessionId: 'demo-bug', cliType: 'OPENCODE', title: '修复 SQLite 驱动加载问题', model: 'gpt-4o', projectPath: '/Users/demo/project2', gitBranch: 'fix/sqlite', createdAt: Date.now() - 7200000, updatedAt: Date.now() - 7200000, messageCount: 2 },
        { sessionId: 'demo-codex', cliType: 'CODEX', title: '添加 Codex CLI 支持', model: 'codex-1', projectPath: '/Users/demo/project3', gitBranch: 'main', createdAt: Date.now() - 86400000, updatedAt: Date.now() - 86400000, messageCount: 3 }
    ],

    /**
     * 获取指定会话的完整历史消息。
     *
     * @param {string} sessionId - 会话 ID
     * @returns {Array} 消息数组
     */
    getHistory(sessionId) {
        if (sessionId === 'demo-full') return this.fullDemoMessages;
        if (sessionId === 'demo-refactor') return this.refactorMessages;
        if (sessionId === 'demo-bug') return this.bugFixMessages;
        return this.refactorMessages;
    },
    /** 完整消息类型演示（包含所有内容块类型）。 */
    fullDemoMessages: [
        {
            role: 'USER',
            contents: [{ type: 'TEXT', text: '请演示所有消息类型的渲染效果，包括思考、工具调用、待办列表和错误信息' }]
        },
        {
            role: 'ASSISTANT',
            contents: [
                {
                    type: 'THINKING',
                    thinking: '用户希望看到所有消息类型的渲染效果。我需要依次展示：\n1. 思考块（就是现在这个）\n2. 普通文本消息\n3. 工具调用（bash 和文件编辑）\n4. 待办任务列表\n5. 错误信息\n让我逐一展示每种类型的渲染效果。',
                    collapsed: false
                },
                {
                    type: 'TEXT',
                    text: '好的，我来演示所有消息类型的渲染效果。首先展示 **Markdown 文本渲染**：\n\n### 文本样式\n\n- **粗体文本** 和 *斜体文本*\n- `行内代码` 渲染效果\n- [链接文本](https://example.com)\n\n### 代码块\n\n```java\n@Service\npublic class ChatManager {\n\n    private final Map<CLIType, AIProvider> providers = new ConcurrentHashMap<>();\n\n    public void registerProvider(CLIType type, AIProvider provider) {\n        providers.put(type, provider);\n    }\n}\n```\n\n```sql\nCREATE TABLE ai_session (\n    id          BIGINT PRIMARY KEY AUTO_INCREMENT,\n    cli_type    VARCHAR(20) NOT NULL,\n    title       VARCHAR(200),\n    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n);\n```\n\n### 表格\n\n| 字段 | 类型 | 说明 |\n|------|------|------|\n| id | BIGINT | 主键 |\n| cli_type | VARCHAR(20) | CLI 类型 |\n| title | VARCHAR(200) | 会话标题 |\n\n### 引用\n\n> 这是一段引用文本，用于展示 blockquote 的渲染效果。\n> 支持多行显示。'
                },
                {
                    type: 'TOOL_USE',
                    toolName: 'bash',
                    title: 'bash',
                    toolInput: JSON.stringify({ command: 'find /src -name "*.java" -path "*/session/*" | head -10' }, null, 2),
                    toolOutput: '/src/main/java/io/github/easyagent/session/SessionService.java\n/src/main/java/io/github/easyagent/session/reader/OpenCodeSessionReader.java\n/src/main/java/io/github/easyagent/session/reader/CodexSessionReader.java\n/src/main/java/io/github/easyagent/session/entity/SessionMessage.java\n/src/main/java/io/github/easyagent/session/entity/ContentBlock.java',
                    status: 'COMPLETED',
                    collapsed: true
                },
                {
                    type: 'TOOL_USE',
                    toolName: 'edit_file',
                    title: 'edit_file',
                    toolInput: JSON.stringify({ path: '/src/SessionReader.java', old_string: 'public interface SessionReader {\n    List<SessionMessage> read(String path);\n}', new_string: 'public interface SessionReader {\n    List<SessionMessage> read(String path);\n    int count(String path);\n}' }, null, 2),
                    toolOutput: 'Successfully edited /src/SessionReader.java',
                    status: 'COMPLETED',
                    collapsed: true
                },
                {
                    type: 'TOOL_USE',
                    toolName: 'bash',
                    title: 'bash',
                    toolInput: JSON.stringify({ command: 'gradle test --tests "*SessionReader*" 2>&1 | tail -20' }, null, 2),
                    toolOutput: '> Task :compileJava UP-TO-DATE\n> Task :compileTestJava UP-TO-DATE\n> Task :processResources NO-SOURCE\n\nSessionReaderTest > testReadSessions() PASSED\nSessionReaderTest > testEmptyDatabase() PASSED\nSessionReaderTest > testCorruptData() FAILED\n    org.sqlite.SQLiteException: unable to open database file\n        at session.reader.CodexSessionReader.read(CodexSessionReader.java:45)\n\n3 tests completed, 1 failed',
                    status: 'FAILED',
                    collapsed: true
                },
                {
                    type: 'TEXT',
                    text: '工具调用测试完成。可以看到文件搜索和编辑都成功了，但有一个测试失败。\n\n下面展示待办任务列表：'
                },
                {
                    type: 'TODO_LIST',
                    items: [
                        { id: '1', title: '修复 SQLite 驱动加载问题', status: 'COMPLETED' },
                        { id: '2', title: '提取 SessionReader 公共接口', status: 'COMPLETED' },
                        { id: '3', title: '实现 OpenCode 会话读取器', status: 'IN_PROGRESS' },
                        { id: '4', title: '实现 Codex 会话读取器', status: 'PENDING' },
                        { id: '5', title: '编写单元测试', status: 'PENDING' },
                        { id: '6', title: '更新文档说明', status: 'CANCELLED' }
                    ]
                },
                {
                    type: 'TEXT',
                    text: '最后展示一个错误信息：'
                },
                {
                    type: 'ERROR',
                    text: 'Connection refused: 无法连接到 AI 服务。请检查网络配置和 API Key 是否正确。'
                },
                {
                    type: 'TEXT',
                    text: '以上就是所有消息类型的完整渲染演示！'
                }
            ],
            finishReason: 'end_turn',
            tokenUsage: { input: 1247, output: 3856, total: 5103 }
        }
    ],

    /** 重构场景消息。 */
    refactorMessages: [
        {
            role: 'USER',
            contents: [{ type: 'TEXT', text: '请帮我重构 SessionReader 模块，让它支持多种 CLI 类型' }]
        },
        {
            role: 'ASSISTANT',
            contents: [
                {
                    type: 'THINKING',
                    thinking: '用户要求重构 SessionReader 模块。我需要先了解现有的代码结构，然后设计一个通用的接口来支持多种 CLI 类型。让我先看看当前的实现...',
                    collapsed: false
                },
                {
                    type: 'TEXT',
                    text: '我来帮你重构 SessionReader 模块。首先让我了解一下当前的代码结构。'
                },
                {
                    type: 'TOOL_USE',
                    toolName: 'bash',
                    title: 'bash',
                    toolInput: JSON.stringify({ command: 'find src -name "*SessionReader*" -type f' }, null, 2),
                    toolOutput: 'src/main/java/io/github/easyagent/session/reader/SessionReader.java\nsrc/main/java/io/github/easyagent/session/reader/OpenCodeSessionReader.java\nsrc/main/java/io/github/easyagent/session/reader/CodexSessionReader.java',
                    status: 'COMPLETED',
                    collapsed: true
                },
                {
                    type: 'TEXT',
                    text: '## 重构方案\n\n现有结构已经有良好的抽象，我建议做以下改进：\n\n1. **提取公共接口** - 定义 `SessionReader` 接口\n2. **使用工厂模式** - `SessionReaderFactory` 根据类型创建实例\n3. **统一数据模型** - 使用 record 类型\n\n```java\npublic interface SessionReader {\n    List<SessionMessage> readSessions(String dbPath);\n}\n```\n\n> 注意：需要处理 SQLite JDBC 驱动加载的问题\n\n| 模块 | 状态 |\n|------|------|\n| OpenCode | ✅ |\n| Codex | ✅ |\n| Claude | 🔧 |'
                }
            ],
            finishReason: 'end_turn',
            tokenUsage: { input: 156, output: 423, total: 579 }
        }
    ],

    /** Bug 修复场景消息。 */
    bugFixMessages: [
        {
            role: 'USER',
            contents: [{ type: 'TEXT', text: '插件启动时报错：SQLite JDBC 驱动找不到，你看看是什么问题？' }]
        },
        {
            role: 'ASSISTANT',
            contents: [
                {
                    type: 'THINKING',
                    thinking: 'SQLite JDBC 驱动找不到，这是 IntelliJ 插件中常见的问题。插件使用独立的 ClassLoader，DriverManager 无法找到通过 plugin.xml 注册的驱动。需要手动加载驱动类。',
                    collapsed: false
                },
                {
                    type: 'TOOL_USE',
                    toolName: 'bash',
                    title: 'bash',
                    toolInput: JSON.stringify({ command: 'grep -r "DriverManager" src/ --include="*.java" -l' }, null, 2),
                    toolOutput: 'src/main/java/io/github/easyagent/session/reader/OpenCodeSessionReader.java\nsrc/main/java/io/github/easyagent/session/reader/CodexSessionReader.java',
                    status: 'COMPLETED',
                    collapsed: true
                },
                {
                    type: 'TEXT',
                    text: '找到问题了！`DriverManager.getConnection()` 在插件的 ClassLoader 环境中无法自动发现 SQLite 驱动。\n\n**修复方案**：手动实例化 JDBC 驱动：\n\n```java\nConnection conn = new org.sqlite.JDBC().connect(\n    "jdbc:sqlite:" + dbPath, \n    new Properties()\n);\n```\n\n这样可以绕过 `DriverManager` 的类加载器限制。'
                }
            ],
            finishReason: 'end_turn',
            tokenUsage: { input: 89, output: 234, total: 323 }
        }
    ]
};

/**
 * 开发模式 Mock Bridge。
 * <p>
 * 模拟 JCEF Java 后端，让前端可以在浏览器中独立运行。
 * 所有模拟数据来自 {@link EAMockData}。
 * </p>
 *
 * @namespace EABridgeMock
 */
window.EABridgeMock = {
    init() {
        if (window.cefQuery) return;

        window.cefQuery = (params) => {
            let data;
            try { data = JSON.parse(params.request); } catch (e) { return; }

            switch (data.action) {
                case 'getTheme':
                    setTimeout(() => {
                        if (window.__ea_onThemeChanged) window.__ea_onThemeChanged({ isDark: false });
                    }, 50);
                    break;
                case 'pageReady':
                    setTimeout(() => {
                        if (window.__ea_onThemeChanged) window.__ea_onThemeChanged({ isDark: false });
                        if (window.__ea_onAvailableCLIs) window.__ea_onAvailableCLIs([
                            { type: 'CLAUDE', available: true },
                            { type: 'OPENCODE', available: true },
                            { type: 'CODEX', available: true }
                        ]);
                    }, 50);
                    break;
                case 'listAllSessions':
                case 'listSessions':
                    setTimeout(() => {
                        if (window.__ea_onSessionList) window.__ea_onSessionList({ sessions: EAMockData.sessions });
                    }, 80);
                    break;
                case 'loadHistory':
                    setTimeout(() => {
                        if (window.__ea_onHistoryLoaded) {
                            window.__ea_onHistoryLoaded({
                                sessionId: data.sessionId,
                                messages: EAMockData.getHistory(data.sessionId)
                            });
                        }
                    }, 150);
                    break;
                case 'sendMessage':
                    this.mockStreamResponse(data.text);
                    break;
                case 'stopGeneration':
                    break;
                case 'createSession':
                    setTimeout(() => {
                        if (window.__ea_onSessionCreated) window.__ea_onSessionCreated({ cliType: data.cliType || 'CLAUDE' });
                    }, 50);
                    break;
                case 'deleteSessions':
                    setTimeout(() => {
                        const deletedIds = (data.sessionIds || '').split(',').filter(Boolean);
                        EAMockData.sessions = EAMockData.sessions.filter((item) => deletedIds.indexOf(item.sessionId) < 0);
                        if (window.__ea_onSessionList) {
                            window.__ea_onSessionList({ sessions: EAMockData.sessions });
                        }
                        if (window.__ea_onSessionsDeleted) {
                            window.__ea_onSessionsDeleted({
                                deletedCount: deletedIds.length,
                                sessionIds: deletedIds
                            });
                        }
                    }, 80);
                    break;
            }
        };

        if (EAMockData.enabled) {
            this.autoLoadDemo();
        }
    },

    /**
     * 自动加载演示会话，直接进入聊天界面。
     */
    autoLoadDemo() {
        setTimeout(() => {
            const store = window.EAStore;
            if (!store) return;
            store.cliType = 'CLAUDE';
            store.sessionId = 'demo-full';
            store.sessionTitle = '完整消息类型演示';
            store.model = 'claude-3.5-sonnet';
            const history = EAMockData.getHistory('demo-full');
            for (const msg of history) {
                store.messages.push(store.convertMessage(msg));
            }
        }, 200);
    },

    mockStreamResponse(userText) {
        const resp = {
            thinking: '让我分析一下用户的问题...',
            text: '收到你的消息了。这是一个模拟回复，用于测试流式响应效果。\n\n**功能测试：**\n\n- Markdown 渲染 ✓\n- 流式输出 ✓\n\n```java\nSystem.out.println(\"Hello, EasyAgent!\");\n```'
        };
        let delay = 300;
        setTimeout(() => {
            if (window.__ea_onStreamEvent) window.__ea_onStreamEvent({ type: 'STEP_START', sessionId: 'mock-stream' });
        }, delay);
        delay += 200;
        setTimeout(() => {
            if (window.__ea_onStreamEvent) window.__ea_onStreamEvent({ type: 'MESSAGE', messageType: 'THINKING', text: resp.thinking });
        }, delay);
        delay += 600;
        for (let i = 0; i < resp.text.length; i += 6) {
            const chunk = resp.text.substring(i, Math.min(i + 6, resp.text.length));
            setTimeout(((c) => () => {
                if (window.__ea_onStreamEvent) window.__ea_onStreamEvent({ type: 'MESSAGE', messageType: 'TEXT', text: c });
            })(chunk), delay);
            delay += 25;
        }
        delay += 200;
        setTimeout(() => {
            if (window.__ea_onStreamEvent) window.__ea_onStreamEvent({
                type: 'STEP_FINISH', reason: 'end_turn',
                tokenUsage: { input: 156, output: 423, total: 579 }
            });
        }, delay);
    }
};
