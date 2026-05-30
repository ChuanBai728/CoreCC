<p align="center">
  <h1 align="center">CoreCC</h1>
  <p align="center">轻量级本地 AI 编程助手 — Java 版</p>
  <p align="center">
    <img src="https://img.shields.io/badge/version-0.3.0-blue" alt="version">
    <img src="https://img.shields.io/badge/java-17+-orange" alt="java">
    <img src="https://img.shields.io/badge/license-MIT-green" alt="license">
  </p>
</p>

---

CoreCC 是一款面向终端的轻量级本地 AI 编程智能体，基于 Java 实现，兼容所有 OpenAI 格式 API。它在终端中运行，赋予大语言模型文件读写、Shell 执行、代码搜索和子智能体调度等能力，使其能够自主完成代码编写、Bug 修复和项目管理等任务。

> 本项目架构设计受 Anthropic Claude Code 启发。

---

## 目录

- [核心特性](#核心特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [使用指南](#使用指南)
- [内置工具](#内置工具)
- [项目架构](#项目架构)
- [技术栈](#技术栈)
- [开发指南](#开发指南)
- [License](#license)

---

## 核心特性

- **多模型兼容** — 支持 OpenAI、DeepSeek、Kimi、Qwen、Ollama 等所有 OpenAI 格式 API
- **流式输出** — 基于 SSE 的实时流式响应，逐 token 输出
- **交互式 REPL** — 基于 JLine3 的终端界面，支持历史记录、括号匹配和命令补全
- **七项内置工具** — 文件读写、搜索替换编辑、Shell 执行、正则搜索、文件匹配、子智能体
- **只读工具并行执行** — 自动识别只读工具并通过线程池并发执行（最多 8 线程）
- **四级上下文压缩** — 50% / 70% / 90% 三级阈值自动压缩，支持 LLM 摘要和紧急折叠
- **长期记忆** — 按工作区持久化偏好、项目约定和常用上下文，跨会话复用
- **会话管理** — 保存 / 恢复对话历史，支持多会话并行
- **运行时反馈** — 工具错误自动分类（8 类）、恢复建议生成、智能输出压缩
- **安全防护** — 16 种危险命令模式检测、API 密钥泄露拦截、唯一匹配编辑校验

---

## 环境要求

| 依赖 | 版本 |
|---|---|
| Java | 17+ |
| Maven | 3.6+ |

---

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/ChuanBai728/CoreCC.git
cd CoreCC
```

### 2. 构建

```bash
mvn clean package
```

构建产物位于 `target/corecc-0.3.0.jar`。

### 3. 运行

```bash
# 交互式模式
java -jar target/corecc-0.3.0.jar

# 单次任务模式
java -jar target/corecc-0.3.0.jar -p "读取 Main.java 并修复拼写错误的 import"
```

---

## 配置说明

CoreCC 的配置优先级：**命令行参数 > 环境变量 > `.env` 文件 > 默认值**

### 环境变量

| 变量 | 说明 | 默认值 |
|---|---|---|
| `OPENAI_API_KEY` | API 密钥 | — |
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | — |
| `OPENAI_BASE_URL` | API 地址 | `https://api.openai.com/v1` |
| `CORECC_MODEL` | 模型名称 | `gpt-4o` |
| `CORECC_MAX_TOKENS` | 单次输出 token 上限 | `4096` |
| `CORECC_TEMPERATURE` | 采样温度 | `0` |
| `CORECC_MAX_CONTEXT` | 上下文窗口上限 | `128000` |

### .env 文件示例

在项目根目录创建 `.env` 文件：

```env
OPENAI_API_KEY=sk-your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
CORECC_MODEL=gpt-5.4
```

### 常见 API 配置

```bash
# OpenAI
export OPENAI_API_KEY=sk-xxx

# DeepSeek
export OPENAI_BASE_URL=https://api.deepseek.com
export DEEPSEEK_API_KEY=sk-xxx

# Ollama (本地)
export OPENAI_BASE_URL=http://localhost:11434/v1
export OPENAI_API_KEY=ollama
```

### 命令行参数

| 参数 | 说明 |
|---|---|
| `-m, --model <name>` | 指定模型 |
| `--base-url <url>` | 指定 API 地址 |
| `--api-key <key>` | 指定 API 密钥 |
| `-p, --prompt <text>` | 单次任务模式（执行后退出） |
| `-r, --resume <id>` | 恢复指定会话 |
| `-v, --version` | 显示版本信息 |

---

## 使用指南

### 交互式 REPL

启动后进入交互式终端：

```bash
java -jar target/corecc-0.3.0.jar
```

### 斜杠命令

| 命令 | 说明 |
|---|---|
| `/help` | 显示帮助信息 |
| `/model` | 查看当前模型 |
| `/model <name>` | 切换模型 |
| `/tokens` | 查看 token 使用量（prompt / completion / total） |
| `/status` | 查看运行时状态（上下文压力、工具统计、压缩记录） |
| `/compact` | 手动触发上下文压缩 |
| `/remember <text>` | 保存一条长期记忆 |
| `/memory [query]` | 查看或搜索长期记忆 |
| `/forget <id>` | 删除一条长期记忆 |
| `/diff` | 查看本次会话修改过的文件 |
| `/save` | 保存当前会话 |
| `/sessions` | 列出已保存会话 |
| `/reset` | 清空当前对话历史 |
| `quit` / `exit` | 退出 |

### 会话恢复

```bash
java -jar target/corecc-0.3.0.jar -r session_20260530_120000_abcd1234
```

---

## 内置工具

CoreCC 提供 7 项内置工具，大语言模型可在对话中自动调用：

| 工具 | 类型 | 说明 |
|---|---|---|
| `read_file` | 只读 | 读取文件内容，支持行号显示、偏移量和分页 |
| `write_file` | 写入 | 创建新文件或完全覆盖，自动创建父目录 |
| `edit_file` | 写入 | 搜索替换式编辑，要求匹配字符串唯一，输出 unified diff |
| `bash` | 写入 | 执行 Shell 命令，支持超时控制和危险命令拦截 |
| `grep` | 只读 | 正则表达式内容搜索，跳过 `.git` / `node_modules` 等目录 |
| `glob` | 只读 | 文件路径模式匹配，支持 `**` 递归，按修改时间排序 |
| `agent` | 写入 | 生成子智能体处理复杂子任务，禁止递归调用 |

### 并行执行机制

- **只读工具**（`read_file`、`grep`、`glob`）：自动批量并行执行，线程池上限 8，单任务超时 30 秒
- **写入工具**（`write_file`、`edit_file`、`bash`、`agent`）：顺序执行，避免竞态条件

### 自定义工具

实现 `Tool` 接口即可扩展自定义工具：

```java
import com.corecc.tools.Tool;
import java.util.List;
import java.util.Map;

public class HttpTool implements Tool {

    @Override
    public String getName() { return "http"; }

    @Override
    public String getDescription() { return "Fetch a URL and return the response body."; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of("type", "string", "description", "The URL to fetch")
            ),
            "required", List.of("url")
        );
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        // 实现逻辑
        return "Response content...";
    }
}
```

然后在 `ToolRegistry` 中注册即可。

---

## 项目架构

### 目录结构

```
com.corecc/
├── CoreCC.java                入口（picocli 参数解析）
├── cli/
│   └── CLI.java               用户交互层（REPL + 流式输出 + 斜杠命令）
├── agent/
│   └── Agent.java             核心引擎（Agent 循环 + 工具调度 + 压缩触发）
├── llm/
│   ├── LLM.java               OpenAI API 客户端（SSE 流式 + 重试逻辑）
│   ├── LLMResponse.java       响应数据类
│   ├── ToolCall.java          工具调用数据类
│   └── JsonUtils.java         JSON 工具类
├── tools/
│   ├── Tool.java              工具接口
│   ├── ToolRegistry.java      工具注册中心
│   ├── ReadFileTool.java      文件读取
│   ├── WriteFileTool.java     文件写入
│   ├── EditFileTool.java      搜索替换编辑
│   ├── BashTool.java          Shell 命令执行
│   ├── GrepTool.java          正则内容搜索
│   ├── GlobTool.java          文件路径匹配
│   └── AgentTool.java         子智能体
├── context/
│   ├── ContextManager.java    四级上下文压缩引擎
│   └── CompressionReport.java 压缩报告
├── runtime/
│   ├── RuntimeStats.java      运行时统计
│   ├── RuntimeReview.java     错误分类 + 恢复建议 + 输出压缩
│   └── ToolReview.java        工具审查
├── session/
│   └── SessionManager.java    会话持久化
├── memory/
│   ├── MemoryStore.java       长期记忆存储
│   └── MemoryEntry.java       记忆条目
├── prompt/
│   └── PromptBuilder.java     系统提示词构建
└── config/
    └── Config.java            配置加载
```

### 双层架构

```
┌─────────────────────────────────────────────────┐
│                   Layer 1: CLI                   │
│  用户输入 → 斜杠命令 → 流式输出 → 会话管理        │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│                  Layer 2: Agent                  │
│  LLM 调用 → 工具执行 → 上下文压缩 → 记忆注入     │
│  错误恢复 → 子智能体 → 产物追踪                   │
└─────────────────────────────────────────────────┘
```

### 核心流程

```
用户输入
  → Agent 调用 LLM（附带工具定义）
  → LLM 返回文本或工具调用
  → 若工具调用：执行工具 → RuntimeReview 后处理 → 循环
  → 若文本响应：返回结果
  → ContextManager 按需压缩上下文
  → MemoryStore 检索相关记忆注入系统提示
```

### 上下文压缩策略

| 层级 | 触发条件 | 策略 |
|---|---|---|
| Layer 1 (tool_snip) | 50% 容量 | 截断过长工具输出，保留首尾 |
| Layer 1.5 (microcompact) | 消息超过 6 轮 | 将旧工具结果压缩为摘要 |
| Layer 2 (summarize) | 70% 容量 | 调用 LLM 总结历史对话 |
| Layer 3 (hard_collapse) | 90% 容量 | 紧急折叠，仅保留 4 条近期消息 + 摘要 |

---

## 技术栈

| 组件 | 技术 | 用途 |
|---|---|---|
| HTTP 客户端 | OkHttp 4.12 | OpenAI API 通信 |
| 流式传输 | OkHttp SSE | Server-Sent Events 实时输出 |
| JSON 处理 | Jackson 2.17 | 请求 / 响应序列化 |
| 终端界面 | JLine 3.25 | REPL、历史记录、命令补全 |
| 参数解析 | Picocli 4.7 | CLI 参数和子命令 |
| 配置加载 | dotenv-java 3.0 | `.env` 文件读取 |
| 终端颜色 | Jansi 2.4 | ANSI 彩色输出 |
| 构建工具 | Maven Shade | 可执行 fat JAR 打包 |
| 测试框架 | JUnit 5.10 | 单元测试 |

---

## 开发指南

### 编译

```bash
mvn compile
```

### 运行测试

```bash
mvn test
```

### 打包

```bash
mvn package
```

### 本地运行

```bash
java -jar target/corecc-0.3.0.jar
```

---

## License

[MIT](LICENSE)
