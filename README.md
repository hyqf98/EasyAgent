# EasyAgent

EasyAgent 是一个 IntelliJ IDEA 插件，专门用来把多个 AI CLI 工具统一接入到 IDEA 内部使用。

它不是再造一个聊天窗口，而是把会话历史、流式输出、文件引用、模型管理、文件改动查看和回撤这些能力，尽量贴近 IDEA 原生体验整合起来。

## Features

- 同时接入 `Claude`、`Codex`、`OpenCode`
- 读取和管理本地 CLI 会话历史
- 在 IDEA 内实时渲染思考、工具调用和最终回复
- 支持 `@` 文件引用、编辑器选区引用、图片粘贴发送
- 支持 `/new`、`/compact`、`/init` 等斜杠命令扩展
- AI 改文件后可直接在 IDEA 中查看变动并执行回撤
- 主题跟随 IDEA 外观，统一深色/浅色体验

## Supported CLI

- Claude Code
- OpenAI Codex CLI
- OpenCode

## Model Config

仓库根目录的 `models.json` 用于维护默认模型和静态候选模型。

当前只保留最近两代模型：

- Claude: `claude-opus-4-1-20250805` / `claude-sonnet-4-20250514`
- Codex: `gpt-5.5` / `gpt-5.4`

当前默认上下文窗口：

- Claude: `200000`
- Codex: `1050000`

## Quick Start

环境要求：

- JDK 17
- IntelliJ IDEA
- Gradle

本地运行插件：

```bash
./gradlew runIde
```

编译：

```bash
./gradlew compileJava
```

测试：

```bash
./gradlew test
```

## Project Structure

- `src/main/java/io/github/easyagent/ai`：CLI 适配与流式响应解析
- `src/main/java/io/github/easyagent/session`：本地会话读取与会话管理
- `src/main/java/io/github/easyagent/ui`：IDEA UI、JCEF bridge、交互服务
- `src/main/resources/web`：前端页面与样式资源
- `models.json`：默认模型与静态模型列表

## Status

项目当前以 IntelliJ IDEA 插件形态持续迭代，重点放在：

- 多 CLI 会话统一管理
- 原生文件交互体验
- 文件编辑追踪、差异查看和回撤
- 可扩展命令和模型配置
