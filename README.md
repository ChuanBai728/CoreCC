# CoreCC Java

CoreCC Java 是 [CoreCC](https://github.com/he-yufeng/CoreCC) 的 Java 实现版本，保留了原 Python 版本的所有核心功能。

## 特性

- **OpenAI API 兼容**：支持 OpenAI、DeepSeek、Kimi、Qwen、Ollama 等接口
- **交互式 REPL**：基于 JLine3 的终端界面，支持历史记录和命令补全
- **长期记忆**：按工作区持久化偏好、项目约定和常用上下文
- **工具系统**：内置读取、写入、编辑、搜索、命令执行和子 Agent 工具
- **安全编辑**：`edit_file` 使用唯一字符串匹配并返回 diff
- **上下文压缩**：自动截断冗长工具输出，并在接近上下文上限时压缩旧消息
- **运行时反馈**：记录工具成功率、失败类型、压缩次数和恢复建议

## 环境要求

- Java 17 或更高版本
- Maven 3.6 或更高版本

## 安装

### 从源码构建

```bash
git clone https://github.com/he-yufeng/CoreCC.git
cd CoreCC/corecc-java
mvn clean package
```

构建完成后，可执行 JAR 文件位于 `target/corecc-0.3.0.jar`。

### 运行

```bash
java -jar target/corecc-0.3.0.jar
```

## 配置

CoreCC 从环境变量或项目根目录的 `.env` 文件读取配置。

### 环境变量

| 变量 | 说明 |
|---|---|
| `OPENAI_API_KEY` | API 密钥 |
| `OPENAI_BASE_URL` | OpenAI API 地址 |
| `CORECC_MODEL` | 默认模型名，默认 `gpt-4o` |
| `CORECC_MAX_TOKENS` | 单次输出 token 上限，默认 `4096` |
| `CORECC_TEMPERATURE` | 温度，默认 `0` |
| `CORECC_MAX_CONTEXT` | 上下文窗口估算上限，默认 `128000` |

### .env 文件示例

```env
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
CORECC_MODEL=gpt-4o
```

### 命令行参数

```bash
# 指定模型
java -jar corecc-0.3.0.jar -m gpt-4o

# 指定 API 地址
java -jar corecc-0.3.0.jar --base-url https://api.deepseek.com

# 单次任务模式
java -jar corecc-0.3.0.jar -p "读取 main.java，修复拼写错误的 import"

# 恢复会话
java -jar corecc-0.3.0.jar -r session_20260530_120000_abcd1234
```

## 使用

### 启动交互式 REPL

```bash
java -jar corecc-0.3.0.jar
```

### REPL 命令

| 命令 | 说明 |
|---|---|
| `/help` | 显示帮助 |
| `/model` | 查看当前模型 |
| `/model <name>` | 在当前会话中切换模型 |
| `/tokens` | 查看 prompt、completion、total token 数 |
| `/status` | 查看运行时状态 |
| `/compact` | 手动压缩对话上下文 |
| `/remember <text>` | 保存一条长期记忆 |
| `/memory [query]` | 查看或搜索长期记忆 |
| `/forget <id>` | 删除一条长期记忆 |
| `/diff` | 查看本次会话修改过的文件 |
| `/save` | 保存当前会话 |
| `/sessions` | 列出已保存会话 |
| `/reset` | 清空当前对话历史 |
| `quit` / `exit` | 退出 |

## 架构

```
com.corecc/
├── CoreCC.java              主入口（CLI 参数解析）
├── agent/
│   └── Agent.java           Agent 循环、工具调度、上下文压缩触发
├── llm/
│   ├── LLM.java             OpenAI 流式客户端和重试逻辑
│   ├── LLMResponse.java     LLM 响应数据类
│   ├── ToolCall.java        工具调用数据类
│   └── JsonUtils.java       JSON 工具类
├── config/
│   └── Config.java          环境变量和 .env 配置
├── context/
│   ├── ContextManager.java  token 估算和多层上下文压缩
│   └── CompressionReport.java 压缩报告
├── runtime/
│   ├── RuntimeStats.java    运行时统计
│   └── RuntimeReview.java   错误分类、恢复建议、智能输出压缩
├── session/
│   └── SessionManager.java  会话保存和恢复
├── memory/
│   ├── MemoryStore.java     长期记忆存储
│   └── MemoryEntry.java     记忆条目
├── prompt/
│   └── PromptBuilder.java   系统提示词生成
├── cli/
│   └── CLI.java             终端 REPL
└── tools/
    ├── Tool.java            工具接口
    ├── ToolRegistry.java    工具注册中心
    ├── ReadFileTool.java    文件读取
    ├── WriteFileTool.java   文件写入
    ├── EditFileTool.java    搜索替换式编辑
    ├── BashTool.java        Shell 命令执行
    ├── GrepTool.java        正则内容搜索
    ├── GlobTool.java        文件路径搜索
    └── AgentTool.java       子 Agent 工具
```

## 核心流程

```text
用户输入
  -> LLM 带工具调用
  -> Agent 执行工具
  -> Runtime 记录状态、压缩输出、追加错误恢复建议
  -> ContextManager 按需压缩上下文
  -> MemoryStore 检索相关长期记忆并注入系统提示
  -> 继续循环，直到模型返回最终文本
```

## 工具系统

### 内置工具

1. **read_file** - 读取文件内容（带行号，支持偏移量）
2. **write_file** - 创建新文件或完全覆盖
3. **edit_file** - 搜索替换式编辑（唯一匹配）
4. **bash** - Shell 命令执行（含安全检查）
5. **grep** - 正则表达式内容搜索
6. **glob** - 文件路径模式匹配
7. **agent** - 子智能体工具

### 添加自定义工具

实现 `Tool` 接口即可添加自定义工具：

```java
import com.corecc.tools.Tool;
import java.util.Map;

public class HttpTool implements Tool {
    @Override
    public String getName() { return "http"; }

    @Override
    public String getDescription() { return "Fetch a URL and return the first part of the response."; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "url", Map.of("type", "string")
            ),
            "required", List.of("url")
        );
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String execute(Map<String, Object> args) {
        String url = (String) args.get("url");
        // Implementation here
        return "Response content...";
    }
}
```

## 开发

### 运行测试

```bash
mvn test
```

### 编译检查

```bash
mvn compile
```

### 打包

```bash
mvn package
```

## License

MIT
