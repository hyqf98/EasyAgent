# AGENTS.md - EasyAgent 项目代码开发风格约束

## 项目概述

EasyAgent 是一个 IntelliJ IDEA 插件，用于聚合管理多种 AI CLI 工具（OpenCode、Claude Code、Codex）的会话数据和实时交互。

- **JDK 版本**：JDK 17
- **构建工具**：Gradle
- **核心依赖**：IntelliJ Platform SDK、Gson、Lombok、SQLite JDBC、SLF4J + Logback

## 模块结构

```
io.github.easyagent
├── enums/                        # 通用枚举（AI 和 Session 模块共用）
│   ├── CLIType                   # CLI 类型（OPENCODE/CLAUDE/CODEX）
│   ├── MessageType               # 消息类型（THINKING/TEXT）
│   ├── ResponseType              # 响应类型（STEP_START/MESSAGE/TOOL_USE/STEP_FINISH/COMPACT/ERROR）
│   ├── ToolCallStatus            # 工具调用状态（CALLING/COMPLETED/FAILED）
│   ├── TodoStatus                # 待办状态（PENDING/IN_PROGRESS/COMPLETED/CANCELLED）
│   ├── ContentBlockType          # 内容块类型（TEXT/THINKING/TOOL_USE/TOOL_RESULT/...）
│   └── SessionRole               # 会话角色（USER/ASSISTANT/SYSTEM/DEVELOPER/TOOL_RESULT）
├── ai/                           # AI 模块
│   ├── AIProvider                # AI 提供者接口
│   ├── StreamEventListener       # 流式事件监听器接口
│   ├── provider/                 # 提供者抽象层
│   │   ├── AbstractCLIProvider   # CLI 提供者抽象基类（含重试机制）
│   │   └── RetryConfig           # 重试配置（record）
│   ├── entity/                   # 统一实体（优先使用 record）
│   ├── claude/                   # Claude CLI 专属实现
│   │   ├── entity/               # Claude 原始实体
│   │   └── enums/                # Claude 专属枚举
│   ├── opencode/                 # OpenCode CLI 专属实现
│   │   ├── entity/               # OpenCode 原始实体（全部 record）
│   │   └── enums/                # OpenCode 专属枚举
│   └── codex/                    # Codex CLI 专属实现
│       ├── entity/               # Codex 原始实体
│       └── enums/                # Codex 专属枚举
├── session/                      # 会话读取模块
│   ├── SessionService            # 会话管理服务
│   ├── entity/                   # 会话实体（全部 record）
│   └── reader/                   # 各 CLI 会话读取器
└── util/                         # 工具类
    ├── GsonUtils                 # JSON 工具
    └── FlexibleEnumTypeAdapter   # 灵活枚举适配器
```

## 编码规范

### 1. Java 版本特性

- **必须使用 JDK 17 语法**
- 实体类优先使用 `record`，不可变数据用 `@Builder record`
- 需要继承的类使用 `@Getter @Setter @Builder` 的 Lombok 类
- switch 语句使用箭头语法：`case FOO -> ...`
- 类型判断使用 `instanceof` 模式匹配：`if (obj instanceof String s) { ... }`
- 多行字符串使用 `""" """` 文本块

### 2. 导包规则

- **禁止通配符导包**（`import xxx.*`）
- **禁止内联完全限定名**（如 `java.time.Instant.parse(...)`）
- 所有类必须显式导入

### 3. Javadoc 注释

- 所有类和接口必须有标准 Javadoc：`@author`、`@date`、`@since`
- 所有 public/protected 方法必须有 Javadoc
- record 类型使用行内 `@param` 注释每个字段
- Javadoc 中使用 `{@link}` 引用关联类

### 4. 枚举管理

- 通用枚举放到 `io.github.easyagent.enums` 包
- 各 CLI 专属枚举放在各自的 `enums` 子包
- 禁止在实体类中内联定义枚举，必须提取为独立枚举文件
- 枚举类使用 `@Getter`，有 `value` 字段的枚举提供 `fromValue()` 静态方法

### 5. 实体类定义

- 纯数据载体使用 `@Builder record`
- record 字段使用行内 Javadoc `@param` 注释
- 需要继承或 Lombok SuperBuilder 的类保留 `@Getter @Setter` class 形式

### 6. 线程与异步

- 使用 `Executors.newVirtualThreadPerTaskExecutor()` 创建虚拟线程池
- 禁止使用 `Executors.newCachedThreadPool()` 等平台线程池
- 异步任务通过线程池 `submit()` 提交

### 7. 重试机制

- CLI 调用支持通过 `RetryConfig` 配置重试
- 重试间隔使用指数退避策略
- 构造 Provider 时可传入 `RetryConfig`，默认不重试

### 8. 三层 CLI 抽象

- **外层抽象**：`AbstractCLIProvider` 定义通用流程（进程执行、行分发、响应构建）
- **中层适配**：各 `XXXCLIProvider` 实现 CLI 特定的命令构建和事件解析
- **内层实体**：各 `xxx/entity` 和 `xxx/enums` 管理 CLI 特有的原始数据结构

### 9. 代码风格（阿里巴巴 Java 开发手册）

- 类名使用 UpperCamelCase，方法名/变量名使用 lowerCamelCase
- 常量使用全大写下划线分隔：`MAX_RETRY_COUNT`
- 花括号不换行
- 缩进使用 4 个空格
- 方法总长度不超过 80 行
- 禁止魔法值，使用枚举或常量

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **EasyAgent** (4255 symbols, 9977 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/EasyAgent/context` | Codebase overview, check index freshness |
| `gitnexus://repo/EasyAgent/clusters` | All functional areas |
| `gitnexus://repo/EasyAgent/processes` | All execution flows |
| `gitnexus://repo/EasyAgent/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
