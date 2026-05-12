# EasyAgent

<p align="center">
  <strong>IntelliJ IDEA AI 助手插件 — 聚合 Claude Code / OpenCode / Codex CLI，统一管理会话、计划与执行</strong>
</p>

---

EasyAgent 是一个 IntelliJ IDEA 插件，将多个 AI CLI 编码助手统一接入 IDE，提供会话管理、计划看板、任务编排与自动执行等能力，同时保持贴近 IDEA 原生的交互体验。

## 核心特性

### AI 会话

- 同时接入 **Claude Code**、**OpenCode**、**Codex** 三大 CLI 工具
- 读取和管理本地 CLI 会话历史，在 IDEA 内实时渲染
- 支持 `@` 文件引用、编辑器选区引用、图片粘贴发送
- 支持 `/new`、`/compact`、`/init` 等斜杠命令
- 思考过程、工具调用、文件变更实时流式渲染
- AI 修改文件后可直接在 IDEA 中查看 diff 并一键回撤
- CLI 命令路径自动检测 + 手动覆盖

### 计划看板

- 创建计划后 AI 自动拆分为可执行子任务
- **看板视图**：待办 / 执行中 / 已完成 / 异常 四列拖拽管理
- 跨列拖拽自动变更任务状态，列内拖拽自由排序
- 支持**并发执行**多个任务（可配置并发数）
- 自动开始 / 手动控制，执行中可随时停止
- 任务完成后自动生成执行概要
- COMPLETED / FAILED 任务支持**重试**（复用会话上下文）

### 设置管理

- CLI 工具配置（API Key、模型选择、命令路径）
- MCP 服务管理
- AI Skills 管理
- 主题跟随 IDEA 外观（深色 / 浅色）

## 支持的 CLI 工具

| 工具 | 说明 | 安装方式 |
|------|------|----------|
| Claude Code | Anthropic 官方 CLI 编码助手 | `npm install -g @anthropic-ai/claude-code` |
| OpenCode | 开源终端 AI 编码工具 | `npm install -g opencode` |
| Codex | OpenAI 官方 CLI 编码助手 | `npm install -g @openai/codex` |

## 快速开始

### 环境要求

- JDK 17+
- IntelliJ IDEA 2026.1+（Build 261.*）
- 至少安装一个支持的 CLI 工具

### 构建与运行

```bash
# 运行插件（启动沙箱 IDEA）
./gradlew runIde

# 编译
./gradlew compileJava

# 打包
./gradlew buildPlugin

# 测试
./gradlew test
```

## 项目结构

```
src/main/java/io/github/easyagent/
├── ai/                         # AI 模块：CLI 适配与流式响应解析
│   ├── provider/               # 抽象基类（进程管理、重试机制）
│   ├── claude/                 # Claude Code 专属实现
│   ├── opencode/               # OpenCode 专属实现
│   ├── codex/                  # Codex 专属实现
│   └── entity/                 # 统一响应实体
├── plan/                       # 计划模块：计划与任务的 CRUD、执行概要生成
├── session/                    # 会话模块：各 CLI 本地会话读取与管理
├── enums/                      # 通用枚举（CLI 类型、消息类型、任务状态等）
├── settings/                   # 配置管理（AppState、CLI 配置、MCP、Skills）
├── ui/                         # UI 层：JCEF 浏览器组件、消息桥接、交互服务
│   ├── jcef/                   # JCEF WebView 与 JS Bridge
│   └── service/                # ChatManager、对话管理
└── util/                       # 工具类（JSON、枚举适配）

src/main/resources/web/         # 前端资源（Vue.js + 原生 JS）
├── js/plan/                    # 计划看板视图
├── js/settings/                # 设置页面
├── js/chat/                    # 聊天视图
├── css/                        # 样式文件
└── index.html                  # 入口页面
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 插件框架 | IntelliJ Platform Plugin SDK |
| 构建 | Gradle + IntelliJ Platform Gradle Plugin 2.x |
| JDK | 17（虚拟线程、record、switch 箭头语法） |
| 前端 | Vue 3 (CDN) + Sortable.js |
| 内嵌浏览器 | JCEF (Chromium Embedded Framework) |
| 序列化 | Gson + 自定义 TypeAdapter |
| 数据存储 | SQLite (CLI 会话历史) |
| 日志 | SLF4J + Logback |

## 许可证

MIT License
